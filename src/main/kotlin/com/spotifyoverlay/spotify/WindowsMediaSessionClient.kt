package com.spotifyoverlay.spotify

import com.spotifyoverlay.SpotifyOverlay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Now-playing metadata from Windows System Media Transport Controls (SMTC). */
object WindowsMediaSessionClient {
	private val json = Json { ignoreUnknownKeys = true }
	private val trackRef = AtomicReference<TrackState?>(null)
	private val running = AtomicBoolean(false)
	private var process: Process? = null

	private val executor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "spotify-overlay-smtc").apply { isDaemon = true }
	}

	fun currentTrack(): TrackState? = trackRef.get()

	fun start() {
		if (!running.compareAndSet(false, true)) return
		if (!isWindows()) {
			SpotifyOverlay.LOGGER.warn("SMTC now-playing requires Windows")
			running.set(false)
			return
		}
		executor.execute { runDaemonLoop() }
	}

	fun stop() {
		running.set(false)
		process?.destroyForcibly()
		process = null
	}

	fun reload() {
		// SMTC stream keeps updating; nothing to restart.
	}

	private fun runDaemonLoop() {
		while (running.get()) {
			try {
				val script = extractScript()
				val pb = ProcessBuilder(
					"powershell.exe",
					"-NoProfile",
					"-ExecutionPolicy", "Bypass",
					"-File", script.toAbsolutePath().toString(),
				)
				pb.redirectErrorStream(true)
				val proc = pb.start()
				process = proc
				BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
					while (running.get()) {
						val line = reader.readLine() ?: break
						handleLine(line)
					}
				}
				proc.waitFor(1, TimeUnit.SECONDS)
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.warn("SMTC poller failed; retrying", e)
				try {
					Thread.sleep(2000)
				} catch (_: InterruptedException) {
					Thread.currentThread().interrupt()
					return
				}
			} finally {
				process?.destroyForcibly()
				process = null
			}
		}
	}

	private fun handleLine(line: String) {
		val trimmed = line.trim()
		if (trimmed.isEmpty()) return
		try {
			val element = json.parseToJsonElement(trimmed)
			val asObject = element as? JsonObject
			if (asObject?.get("error") != null) {
				SpotifyOverlay.LOGGER.debug("SMTC error: {}", asObject["error"]?.jsonPrimitive?.content)
				return
			}
			val sessions = when (element) {
				is JsonArray -> element
				else -> json.parseToJsonElement("[$trimmed]").jsonArray
			}
			val chosen = pickSpotifySession(sessions) ?: run {
				trackRef.set(null)
				return
			}
			val title = chosen["title"]?.jsonPrimitive?.content.orEmpty().ifBlank { "Unknown" }
			val artist = mergeArtists(
				chosen["artist"]?.jsonPrimitive?.content.orEmpty(),
				chosen["albumArtist"]?.jsonPrimitive?.content.orEmpty(),
			).ifBlank { "Unknown Artist" }
			val durationMs = chosen["durationMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
			val progressMs = chosen["positionMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
			val lastUpdatedMs = chosen["lastUpdatedMs"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0L }
			val status = chosen["status"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
			val isPlaying = status == 4
			val album = chosen["album"]?.jsonPrimitive?.content
			val id = syntheticId(title, artist)

			val previous = trackRef.get()
			val artists = when {
				previous?.id == id ->
					TrackMetadataEnricher.preferRicherArtists(
						artist,
						TrackMetadataEnricher.displayArtists(id, previous.artists),
					)
				else -> TrackMetadataEnricher.displayArtists(id, artist)
			}
			val state = TrackState.withSmoothedProgress(
				previous = previous,
				id = id,
				title = title,
				artists = artists,
				album = album,
				durationMs = durationMs,
				rawProgressMs = progressMs,
				isPlaying = isPlaying,
				positionValidAtMs = lastUpdatedMs,
			)
			trackRef.set(state)

			if (previous?.id != id) {
				SpotifyLyricsClient.fetchForMetadata(title, artist, durationMs, id)
				AlbumArtCache.prefetchForTrack(id, title, artist, album, durationMs)
			} else {
				val enriched = TrackMetadataEnricher.displayArtists(id, artist)
				if (enriched != state.artists) {
					trackRef.set(state.copy(artists = enriched))
				}
			}
		} catch (e: Exception) {
			SpotifyOverlay.LOGGER.debug("Failed to parse SMTC line: {}", trimmed, e)
		}
	}

	private fun mergeArtists(artist: String, albumArtist: String): String {
		val parts = LinkedHashSet<String>()
		TrackMetadataNormalizer.splitArtists(artist).forEach { parts.add(it) }
		TrackMetadataNormalizer.splitArtists(albumArtist).forEach { parts.add(it) }
		return parts.joinToString(", ")
	}

	private fun pickSpotifySession(sessions: JsonArray): JsonObject? {
		val objects = sessions.mapNotNull { it.jsonObject }
		if (objects.isEmpty()) return null
		val spotify = objects.filter { session ->
			session["app"]?.jsonPrimitive?.content.orEmpty().contains("Spotify", ignoreCase = true)
		}
		val pool = spotify.ifEmpty { objects }
		return pool.firstOrNull { it["status"]?.jsonPrimitive?.content?.toIntOrNull() == 4 }
			?: pool.firstOrNull()
	}

	private fun syntheticId(title: String, artist: String): String {
		val digest = MessageDigest.getInstance("SHA-1")
			.digest("$artist|$title".toByteArray(StandardCharsets.UTF_8))
		return digest.joinToString("") { "%02x".format(it) }.take(20)
	}

	private fun extractScript(): Path {
		val target = Path.of(System.getProperty("java.io.tmpdir"), "spotify-overlay-smtc_poll.ps1")
		val resource = WindowsMediaSessionClient::class.java
			.getResourceAsStream("/assets/spotify-overlay/smtc_poll.ps1")
			?: error("Missing smtc_poll.ps1 resource")
		resource.use { input ->
			Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
		}
		return target
	}

	private fun isWindows(): Boolean =
		System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)
}

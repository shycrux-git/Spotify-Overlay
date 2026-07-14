package com.spotifyoverlay.spotify

import com.spotifyoverlay.SpotifyOverlay
import io.github.humbleui.skija.Image
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Downloads album art off-thread; decodes to Skija Image on the render thread. */
object AlbumArtCache {
	private val http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	private val executor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "spotify-overlay-art").apply { isDaemon = true }
	}

	private val pendingBytes = ConcurrentHashMap<String, ByteArray>()
	private val gpu = ConcurrentHashMap<String, Image>()
	private val inFlight = ConcurrentHashMap.newKeySet<String>()

	@Volatile
	private var activeKey: String? = null

	fun prefetchForTrack(trackKey: String, title: String, artist: String, album: String?, durationMs: Long = 0L) {
		activeKey = trackKey
		TrackMetadataEnricher.prefetch(trackKey, title, artist, album, durationMs)
	}

	fun queueDownload(trackKey: String, artworkUrl: String) {
		if (gpu.containsKey(trackKey) || pendingBytes.containsKey(trackKey) || !inFlight.add(trackKey)) return
		executor.execute {
			try {
				val response = http.send(
					HttpRequest.newBuilder(URI.create(artworkUrl))
						.header("User-Agent", "spotify-overlay/1.0")
						.GET()
						.timeout(Duration.ofSeconds(20))
						.build(),
					HttpResponse.BodyHandlers.ofByteArray(),
				)
				if (response.statusCode() in 200..299 && response.body().isNotEmpty()) {
					pendingBytes[trackKey] = response.body()
				}
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.warn("Album art download failed for {}: {}", trackKey, e.message)
			} finally {
				inFlight.remove(trackKey)
			}
		}
	}

	fun currentImage(): Image? {
		val key = activeKey ?: return null
		return gpu[key]
	}

	fun flushPending() {
		for ((key, bytes) in pendingBytes.entries.toList()) {
			if (gpu.containsKey(key)) {
				pendingBytes.remove(key)
				continue
			}
			try {
				val image = Image.makeFromEncoded(bytes)
				gpu.put(key, image)?.close()
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.warn("Skija image decode failed for {}: {}", key, e.message)
			}
			pendingBytes.remove(key)
		}

		val keep = activeKey
		for (key in gpu.keys.toList()) {
			if (key == keep) continue
			gpu.remove(key)?.close()
		}
	}
}

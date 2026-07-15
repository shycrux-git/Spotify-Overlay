package com.spotifyoverlay.spotify

import com.spotifyoverlay.SpotifyOverlay
import com.spotifyoverlay.config.ModConfig
import com.spotifyoverlay.config.MxmConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object SpotifyLyricsClient {
	private const val APP_ID = "android-player-v1.0"
	private const val TOKEN_URL = "https://apic.musixmatch.com/ws/1.1/token.get"
	private const val MATCHER_URL = "https://apic.musixmatch.com/ws/1.1/matcher.track.get"
	private const val RICHSYNC_URL = "https://apic.musixmatch.com/ws/1.1/track.richsync.get"
	private const val SUBTITLE_URL = "https://apic.musixmatch.com/ws/1.1/track.subtitle.get"
	private const val LRCLIB_GET = "https://lrclib.net/api/get"
	private const val LRCLIB_SEARCH = "https://lrclib.net/api/search"

	private val http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(6))
		.executor(Executors.newCachedThreadPool { r ->
			Thread(r, "spotify-overlay-http").apply { isDaemon = true }
		})
		.build()

	private val json = Json { ignoreUnknownKeys = true }

	private val executor = Executors.newCachedThreadPool { r ->
		Thread(r, "spotify-overlay-lyrics").apply { isDaemon = true }
	}

	private val lyricsRef = AtomicReference<LyricsState?>(null)

	@Volatile
	private var mxmToken: String = ""

	@Volatile
	private var lastFetchKey: String = ""

	fun currentLyrics(): LyricsState? = lyricsRef.get()

	fun clear() {
		lyricsRef.set(null)
		lastFetchKey = ""
	}

	fun warmUp() {
		executor.execute {
			try {
				ensureToken()
			} catch (_: Exception) {
			}
		}
	}

	fun fetchForMetadata(title: String, artist: String, durationMs: Long, trackKey: String) {
		val fetchKey = "$trackKey|${TrackMetadataNormalizer.primaryArtist(artist)}|${TrackMetadataNormalizer.cleanTitle(title)}"
		val existing = lyricsRef.get()
		if (existing?.trackId == trackKey && existing.lines.isNotEmpty()) {
			lastFetchKey = fetchKey
			return
		}
		if (fetchKey == lastFetchKey && existing?.trackId == trackKey) {
			return
		}
		lastFetchKey = fetchKey
		lyricsRef.set(LyricsState.unavailable(trackKey, "Fetching lyrics..."))
		executor.execute {
			Thread.interrupted()
			try {
				val result = loadLyrics(title, artist, durationMs, trackKey)
				if (lastFetchKey.startsWith(trackKey)) {
					lyricsRef.set(result)
				}
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.warn("Lyrics fetch failed for {} - {}: {}", artist, title, e.message)
				if (lastFetchKey.startsWith(trackKey)) {
					lyricsRef.set(LyricsState.unavailable(trackKey, "Lyrics unavailable"))
				}
			}
		}
	}

	private fun loadLyrics(title: String, artist: String, durationMs: Long, trackKey: String): LyricsState {
		val mxm = CompletableFuture.supplyAsync({
			Thread.interrupted()
			loadFromMusixmatch(title, artist, durationMs, trackKey)
		}, executor)
		val lrc = CompletableFuture.supplyAsync({
			Thread.interrupted()
			loadFromLrclib(title, artist, durationMs, trackKey)
		}, executor)

		firstSynced(listOf(mxm, lrc), timeoutMs = 20_000)?.let { return it }
		return LyricsState.unavailable(trackKey, "No lyrics match")
	}

	private fun firstSynced(
		futures: List<CompletableFuture<LyricsState?>>,
		timeoutMs: Long,
	): LyricsState? {
		val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
		while (System.nanoTime() < deadline) {
			var allDone = true
			for (f in futures) {
				if (!f.isDone) {
					allDone = false
					continue
				}
				val r = runCatching { f.getNow(null) }.getOrNull()
				if (r != null && r.lines.isNotEmpty()) {
					futures.forEach { if (it !== f) it.cancel(false) }
					return r
				}
			}
			if (allDone) break
			try {
				Thread.sleep(20)
			} catch (_: InterruptedException) {
				Thread.interrupted()
				break
			}
		}
		for (f in futures) {
			val r = runCatching { f.getNow(null) }.getOrNull()
			if (r != null && r.lines.isNotEmpty()) return r
		}
		return null
	}

	private fun loadFromMusixmatch(title: String, artist: String, durationMs: Long, trackKey: String): LyricsState? {
		val token = ensureToken() ?: run {
			SpotifyOverlay.LOGGER.warn("Musixmatch token unavailable")
			return null
		}

		val matched = matchTrack(title, artist, durationMs, token) ?: return null
		val ids = listOfNotNull(matched.trackId, matched.commonTrackId).distinct()
		for (id in ids) {
			val useCommon = id == matched.commonTrackId
			fetchSubtitles(id, token, trackKey, useCommonId = useCommon)?.let { return it }
			fetchRichsync(id, token, trackKey, useCommonId = useCommon)?.let { return it }
		}
		SpotifyOverlay.LOGGER.info(
			"MXM matched '{}' / '{}' but no synced lyrics body (track={}, common={})",
			matched.title,
			matched.artist,
			matched.trackId,
			matched.commonTrackId,
		)
		return null
	}

	private fun ensureToken(): String? {
		if (mxmToken.isNotBlank()) return mxmToken
		val mxm = MxmConfig.get()
		mintToken(mxm.guid)?.let { return it }
		if (mxm.token.isNotBlank()) {
			mxmToken = mxm.token
			return mxmToken
		}
		return null
	}

	private fun mintToken(existingGuid: String): String? {
		val guid = existingGuid.ifBlank {
			UUID.randomUUID().toString().also { id ->
				MxmConfig.update { guid = id }
			}
		}
		val url = "$TOKEN_URL?format=json&guid=${enc(guid)}&app_id=${enc(APP_ID)}"
		val body = getJson(url) ?: return null
		val minted = body["message"]?.jsonObject
			?.get("body")?.jsonObject
			?.get("user_token")?.jsonPrimitive?.contentOrNull
			?: return null
		mxmToken = minted
		MxmConfig.update { token = minted }
		return minted
	}

	private data class MatchedTrack(
		val trackId: String?,
		val commonTrackId: String?,
		val title: String,
		val artist: String,
	)

	private fun matchTrack(title: String, artist: String, durationMs: Long, token: String): MatchedTrack? {
		val durationSec = (durationMs / 1000L).coerceAtLeast(0L)
		val titles = linkedSetOf(
			TrackMetadataNormalizer.cleanTitle(title),
			title.trim(),
		).filter { it.isNotBlank() }
		val artists = TrackMetadataNormalizer.artistQueryCandidates(artist)

		var best: MatchedTrack? = null
		var bestScore = -1

		for (qTitle in titles) {
			for (qArtist in artists) {
				val track = queryMatcher(qTitle, qArtist, durationSec, token) ?: continue
				val mxmTitle = stringField(track, "track_name")
				val mxmArtist = stringField(track, "artist_name")
				val score = scoreMatch(title, artist, mxmTitle, mxmArtist)
				if (score < 40) {
					SpotifyOverlay.LOGGER.debug(
						"MXM weak match skipped: wanted '{}'/ '{}' got '{}'/ '{}' score={}",
						title, artist, mxmTitle, mxmArtist, score,
					)
					continue
				}
				if (score > bestScore) {
					bestScore = score
					best = MatchedTrack(
						trackId = stringField(track, "track_id").ifBlank { null },
						commonTrackId = stringField(track, "commontrack_id").ifBlank { null },
						title = mxmTitle,
						artist = mxmArtist,
					)
					if (bestScore >= 75) return best
				}
			}
		}
		if (best == null) {
			SpotifyOverlay.LOGGER.info("MXM matcher: no usable hit for '{}'::'{}'", artist, title)
		}
		return best
	}

	private fun scoreMatch(oursTitle: String, oursArtist: String, theirsTitle: String, theirsArtist: String): Int {
		if (theirsTitle.isBlank()) return 0
		if (!TrackMetadataNormalizer.titlesMatch(oursTitle, theirsTitle)) return 0
		var score = 50
		val nOurs = TrackMetadataNormalizer.normalizeForCompare(TrackMetadataNormalizer.cleanTitle(oursTitle))
		val nTheirs = TrackMetadataNormalizer.normalizeForCompare(TrackMetadataNormalizer.cleanTitle(theirsTitle))
		if (nOurs == nTheirs) score += 30
		if (theirsArtist.isBlank() || TrackMetadataNormalizer.artistsMatch(oursArtist, theirsArtist)) {
			score += 25
		} else {
			score -= 15
		}
		return score
	}

	private fun queryMatcher(title: String, artist: String, durationSec: Long, token: String): JsonObject? {
		fun build(tok: String, withDuration: Boolean) = buildString {
			append(MATCHER_URL)
			append("?format=json&app_id=").append(enc(APP_ID))
			append("&usertoken=").append(enc(tok))
			append("&q_track=").append(enc(title))
			append("&q_artist=").append(enc(artist))
			if (withDuration && durationSec > 0) append("&q_duration=").append(durationSec)
		}

		fun attempt(tok: String): JsonObject? {
			queryBody(build(tok, withDuration = true))?.let { return it }
			if (durationSec > 0) {
				queryBody(build(tok, withDuration = false))?.let { return it }
			}
			return null
		}

		attempt(token)?.let { return it }
		if (mxmToken.isBlank()) {
			val refreshed = mintToken(MxmConfig.get().guid) ?: return null
			return attempt(refreshed)
		}
		return null
	}

	private fun queryBody(url: String): JsonObject? =
		getJson(url)?.get("message")?.jsonObject
			?.get("body")?.jsonObject
			?.get("track")?.jsonObject

	private fun fetchRichsync(
		id: String,
		token: String,
		trackKey: String,
		useCommonId: Boolean,
	): LyricsState? {
		val url = buildString {
			append(RICHSYNC_URL)
			append("?format=json&app_id=").append(enc(APP_ID))
			append("&usertoken=").append(enc(token))
			if (useCommonId) append("&commontrack_id=").append(enc(id))
			else append("&track_id=").append(enc(id))
		}
		val body = getJson(url) ?: return null
		val richsync = body["message"]?.jsonObject
			?.get("body")?.jsonObject
			?.get("richsync")?.jsonObject
			?: return null
		val richBody = richsync["richsync_body"]?.jsonPrimitive?.contentOrNull ?: return null
		val lines = parseRichsyncBody(richBody)
		if (lines.isEmpty()) return null
		return LyricsState(trackKey, lines)
	}

	private fun fetchSubtitles(
		id: String,
		token: String,
		trackKey: String,
		useCommonId: Boolean,
	): LyricsState? {
		val url = buildString {
			append(SUBTITLE_URL)
			append("?format=json&app_id=").append(enc(APP_ID))
			append("&usertoken=").append(enc(token))
			if (useCommonId) append("&commontrack_id=").append(enc(id))
			else append("&track_id=").append(enc(id))
			append("&subtitle_format=lrc")
		}
		val body = getJson(url) ?: return null
		val subtitle = body["message"]?.jsonObject
			?.get("body")?.jsonObject
			?.get("subtitle")?.jsonObject
			?: return null
		val lrc = subtitle["subtitle_body"]?.jsonPrimitive?.contentOrNull ?: return null
		return parseSyncedLrc(trackKey, lrc)
	}

	private fun loadFromLrclib(title: String, artist: String, durationMs: Long, trackKey: String): LyricsState? {
		val cleanTitle = TrackMetadataNormalizer.cleanTitle(title)
		val durationSec = (durationMs / 1000.0)
		for (qArtist in TrackMetadataNormalizer.artistQueryCandidates(artist)) {
			val getUrl = buildString {
				append(LRCLIB_GET)
				append("?track_name=").append(enc(cleanTitle))
				append("&artist_name=").append(enc(qArtist))
				if (durationSec > 0) append("&duration=").append(durationSec)
			}
			parseLrclibObject(getJsonElement(getUrl), trackKey)?.let { return it }

			val searchUrl = "$LRCLIB_SEARCH?track_name=${enc(cleanTitle)}&artist_name=${enc(qArtist)}"
			val arr = getJsonElement(searchUrl) as? JsonArray ?: continue
			val best = arr.mapNotNull { it as? JsonObject }
				.firstOrNull { obj ->
					val t = stringField(obj, "trackName")
					val a = stringField(obj, "artistName")
					TrackMetadataNormalizer.titlesMatch(title, t) &&
						TrackMetadataNormalizer.artistsMatch(artist, a)
				} ?: continue
			parseLrclibObject(best, trackKey)?.let { return it }
		}
		return null
	}

	private fun parseLrclibObject(element: JsonElement?, trackKey: String): LyricsState? {
		val obj = element as? JsonObject ?: return null
		val synced = obj["syncedLyrics"]?.jsonPrimitive?.contentOrNull
		if (!synced.isNullOrBlank()) {
			parseSyncedLrc(trackKey, synced)?.let { return it }
		}
		return null
	}

	private fun parseRichsyncBody(richBody: String): List<LyricLine> {
		val element = json.parseToJsonElement(richBody)
		val array = element as? JsonArray ?: return emptyList()
		return array.mapNotNull { el ->
			val obj = el as? JsonObject ?: return@mapNotNull null
			val ts = when (val v = obj["ts"]) {
				is JsonPrimitive -> v.content.toDoubleOrNull() ?: return@mapNotNull null
				else -> return@mapNotNull null
			}
			val text = obj["x"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
			if (text.isBlank()) return@mapNotNull null
			LyricLine((ts * 1000.0).toLong(), text)
		}
	}

	private fun parseSyncedLrc(trackKey: String, lrc: String): LyricsState? {
		val pattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]\s*(.*)""")
		val lines = lrc.lineSequence().mapNotNull { line ->
			val match = pattern.matchEntire(line.trim()) ?: return@mapNotNull null
			val minutes = match.groupValues[1].toLong()
			val seconds = match.groupValues[2].toLong()
			val frac = match.groupValues[3]
			val millis = when {
				frac.isEmpty() -> 0L
				frac.length == 1 -> frac.toLong() * 100L
				frac.length == 2 -> frac.toLong() * 10L
				else -> frac.take(3).toLong()
			}
			val text = match.groupValues[4].trim()
			if (text.isEmpty()) return@mapNotNull null
			LyricLine(minutes * 60_000 + seconds * 1000 + millis, text)
		}.toList()
		if (lines.isEmpty()) return null
		return LyricsState(trackKey, lines)
	}

	private fun stringField(obj: JsonObject, key: String): String {
		val el = obj[key] ?: return ""
		return when (el) {
			is JsonPrimitive -> el.contentOrNull?.trim().orEmpty()
			else -> el.toString().trim().trim('"')
		}
	}

	private fun getJson(url: String): JsonObject? {
		val element = getJsonElement(url) ?: return null
		val root = element as? JsonObject ?: return null
		val status = root["message"]?.jsonObject
			?.get("header")?.jsonObject
			?.get("status_code")
			?.let { el ->
				when (el) {
					is JsonPrimitive -> el.contentOrNull?.toIntOrNull()
					else -> null
				}
			}
		if (status != null && status != 200) {
			if (status == 401 || status == 403) {
				mxmToken = ""
				MxmConfig.update { token = "" }
			}
			SpotifyOverlay.LOGGER.debug("MXM status {} for {}", status, url.take(100))
			return null
		}
		return root
	}

	private fun getJsonElement(url: String): JsonElement? {
		return try {
			val response = http.send(
				HttpRequest.newBuilder(URI.create(url))
					.header("User-Agent", "spotify-overlay/1.0")
					.GET()
					.timeout(Duration.ofSeconds(8))
					.build(),
				HttpResponse.BodyHandlers.ofString(),
			)
			if (response.statusCode() !in 200..299 || response.body().isBlank()) {
				SpotifyOverlay.LOGGER.debug("HTTP {} for {}", response.statusCode(), url.take(100))
				return null
			}
			json.parseToJsonElement(response.body())
		} catch (e: Exception) {
			SpotifyOverlay.LOGGER.debug("HTTP failed for {}: {}", url.take(100), e.message)
			null
		}
	}

	private fun enc(value: String): String =
		URLEncoder.encode(value, StandardCharsets.UTF_8)
}

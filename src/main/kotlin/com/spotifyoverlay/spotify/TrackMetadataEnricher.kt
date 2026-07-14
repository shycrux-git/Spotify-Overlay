package com.spotifyoverlay.spotify

import com.spotifyoverlay.SpotifyOverlay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/** Resolves fuller artist credits and artwork from iTunes / Deezer. */
object TrackMetadataEnricher {
	data class Enrichment(
		val artists: String,
		val artworkUrl: String?,
	)

	private val http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	private val json = Json { ignoreUnknownKeys = true }

	private val executor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "spotify-overlay-meta").apply { isDaemon = true }
	}

	private val cache = ConcurrentHashMap<String, Enrichment>()
	private val inFlight = ConcurrentHashMap.newKeySet<String>()

	fun displayArtists(trackKey: String, smtcArtists: String): String {
		val enriched = cache[trackKey]?.artists ?: return smtcArtists
		return preferRicherArtists(smtcArtists, enriched)
	}

	fun prefetch(trackKey: String, title: String, artist: String, album: String?, durationMs: Long) {
		if (cache.containsKey(trackKey) || !inFlight.add(trackKey)) return
		executor.execute {
			try {
				val match = findBestMatch(title, artist, album, durationMs) ?: return@execute
				cache[trackKey] = match
				match.artworkUrl?.let { AlbumArtCache.queueDownload(trackKey, it) }
				if (match.artists.isNotBlank() &&
					TrackMetadataNormalizer.splitArtists(match.artists).size >
					TrackMetadataNormalizer.splitArtists(artist).size
				) {
					SpotifyLyricsClient.fetchForMetadata(title, match.artists, durationMs, trackKey)
				}
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.warn("Metadata enrich failed for {}: {}", trackKey, e.message)
			} finally {
				inFlight.remove(trackKey)
			}
		}
	}

	private fun findBestMatch(title: String, artist: String, album: String?, durationMs: Long): Enrichment? {
		itunesCandidates(title, artist, album)
			.maxByOrNull { scoreItunes(it, title, artist, durationMs) }
			?.takeIf { scoreItunes(it, title, artist, durationMs) >= MIN_SCORE }
			?.let { hit ->
				val art = hit["artworkUrl100"]?.jsonPrimitive?.contentOrNull
					?.replace("100x100bb", "600x600bb")
					?.replace("100x100", "600x600")
				val artists = hit["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
				if (artists.isNotBlank()) return Enrichment(artists, art)
			}

		deezerCandidates(title, artist)
			.maxByOrNull { scoreDeezer(it, title, artist, durationMs) }
			?.takeIf { scoreDeezer(it, title, artist, durationMs) >= MIN_SCORE }
			?.let { hit ->
				val art = hit["album"]?.jsonObject
					?.get("cover_xl")?.jsonPrimitive?.contentOrNull
					?: hit["album"]?.jsonObject?.get("cover_big")?.jsonPrimitive?.contentOrNull
				val artists = hit["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
				if (art != null || artists.isNotBlank()) {
					return Enrichment(preferRicherArtists(artist, artists), art)
				}
			}

		return null
	}

	private fun itunesCandidates(title: String, artist: String, album: String?): List<JsonObject> {
		val cleanTitle = TrackMetadataNormalizer.cleanTitle(title)
		val primary = TrackMetadataNormalizer.primaryArtist(artist)
		val queries = listOfNotNull(
			"$primary $cleanTitle",
			TrackMetadataNormalizer.splitArtists(artist).joinToString(" ") { "$it $cleanTitle" },
			album?.takeIf { it.isNotBlank() }?.let { "$primary $it" },
			"$primary $title",
		).map { it.trim() }.filter { it.isNotBlank() }.distinct()

		val out = ArrayList<JsonObject>()
		for (query in queries) {
			val url = "https://itunes.apple.com/search?term=${enc(query)}&entity=song&limit=25"
			val body = httpGet(url) ?: continue
			val results = json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: continue
			results.mapNotNullTo(out) { it.jsonObject }
		}
		return out.distinctBy { it["trackId"]?.jsonPrimitive?.contentOrNull ?: it.toString() }
	}

	private fun deezerCandidates(title: String, artist: String): List<JsonObject> {
		val cleanTitle = TrackMetadataNormalizer.cleanTitle(title)
		val primary = TrackMetadataNormalizer.primaryArtist(artist)
		val q = """track:"$cleanTitle" artist:"$primary""""
		val url = "https://api.deezer.com/search?q=${enc(q)}&limit=15"
		val body = httpGet(url) ?: return emptyList()
		val results = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return emptyList()
		return results.mapNotNull { it.jsonObject }
	}

	private fun scoreItunes(hit: JsonObject, title: String, artist: String, durationMs: Long): Int {
		val hitTitle = hit["trackName"]?.jsonPrimitive?.contentOrNull.orEmpty()
		val hitArtist = hit["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
		val hitMs = hit["trackTimeMillis"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L
		return score(hitTitle, hitArtist, hitMs, title, artist, durationMs)
	}

	private fun scoreDeezer(hit: JsonObject, title: String, artist: String, durationMs: Long): Int {
		val hitTitle = hit["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
		val hitArtist = hit["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty()
		val hitMs = (hit["duration"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L) * 1000L
		return score(hitTitle, hitArtist, hitMs, title, artist, durationMs)
	}

	private fun score(
		hitTitle: String,
		hitArtist: String,
		hitDurationMs: Long,
		title: String,
		artist: String,
		durationMs: Long,
	): Int {
		if (hitTitle.isBlank() || hitArtist.isBlank()) return 0
		if (!TrackMetadataNormalizer.titlesMatch(title, hitTitle)) return 0
		if (!TrackMetadataNormalizer.artistsMatch(artist, hitArtist)) return 0

		var score = 40
		val nTitle = TrackMetadataNormalizer.normalizeForCompare(TrackMetadataNormalizer.cleanTitle(title))
		val nHitTitle = TrackMetadataNormalizer.normalizeForCompare(TrackMetadataNormalizer.cleanTitle(hitTitle))
		if (nTitle == nHitTitle) score += 35
		else if (nHitTitle.startsWith(nTitle) || nTitle.startsWith(nHitTitle)) score += 20

		val ourParts = TrackMetadataNormalizer.splitArtists(artist).map { TrackMetadataNormalizer.normalizeForCompare(it) }
		val hitParts = TrackMetadataNormalizer.splitArtists(hitArtist).map { TrackMetadataNormalizer.normalizeForCompare(it) }
		val overlap = ourParts.count { op -> hitParts.any { hp -> hp.contains(op) || op.contains(hp) } }
		score += overlap * 12
		if (hitParts.size > ourParts.size) score += 8

		if (durationMs > 0 && hitDurationMs > 0) {
			val delta = abs(durationMs - hitDurationMs)
			score += when {
				delta <= 2_000 -> 30
				delta <= 4_000 -> 18
				delta <= 8_000 -> 6
				else -> -50
			}
		}
		return score
	}

	fun preferRicherArtists(smtc: String, enriched: String): String {
		val a = TrackMetadataNormalizer.splitArtists(smtc)
		val b = TrackMetadataNormalizer.splitArtists(enriched)
		return when {
			b.size > a.size -> enriched.trim()
			b.size == a.size && enriched.length > smtc.length -> enriched.trim()
			else -> smtc.trim().ifBlank { enriched.trim() }
		}
	}

	private fun httpGet(url: String): String? {
		val response = http.send(
			HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "spotify-overlay/1.0")
				.GET()
				.timeout(Duration.ofSeconds(15))
				.build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		if (response.statusCode() !in 200..299) return null
		return response.body().takeIf { it.isNotBlank() }
	}

	private fun enc(value: String): String =
		URLEncoder.encode(value, StandardCharsets.UTF_8)

	private const val MIN_SCORE = 70
}

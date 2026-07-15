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
	private val deferredCloses = ArrayList<Image>(4)

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
		if (pendingBytes.isEmpty() && (activeKey == null || gpu.keys.all { it == activeKey })) {
			return
		}
		for ((key, bytes) in pendingBytes.entries.toList()) {
			if (gpu.containsKey(key)) {
				pendingBytes.remove(key)
				continue
			}
			try {
				val image = Image.makeFromEncoded(bytes)
				gpu.put(key, image)?.let { deferredClose(it) }
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.warn("Skija image decode failed for {}: {}", key, e.message)
			}
			pendingBytes.remove(key)
		}

		val keep = activeKey
		for (key in gpu.keys.toList()) {
			if (key == keep) continue
			gpu.remove(key)?.let { deferredClose(it) }
		}
	}

	fun reapDeferredCloses() {
		val batch = synchronized(deferredCloses) {
			if (deferredCloses.isEmpty()) return
			val copy = ArrayList(deferredCloses)
			deferredCloses.clear()
			copy
		}
		for (image in batch) {
			try {
				image.close()
			} catch (_: Exception) {
			}
		}
	}

	private fun deferredClose(image: Image) {
		synchronized(deferredCloses) {
			deferredCloses.add(image)
		}
	}
}

package com.spotifyoverlay.spotify

import com.spotifyoverlay.SpotifyOverlay
import org.lwjgl.nanovg.NanoVG.nvgCreateImageMem
import org.lwjgl.nanovg.NanoVG.nvgDeleteImage
import org.lwjgl.system.MemoryUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Downloads album art off-thread; uploads to NanoVG on the render thread. */
object AlbumArtCache {
	private val http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build()

	private val executor = Executors.newSingleThreadExecutor { r ->
		Thread(r, "spotify-overlay-art").apply { isDaemon = true }
	}

	private data class GpuEntry(val imageId: Int, val buffer: ByteBuffer)

	private val pendingBytes = ConcurrentHashMap<String, ByteArray>()
	private val gpu = ConcurrentHashMap<String, GpuEntry>()
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

	fun imageId(): Int {
		val key = activeKey ?: return -1
		return gpu[key]?.imageId ?: -1
	}

	fun flushPending(nvg: Long) {
		if (nvg == 0L) return
		for ((key, bytes) in pendingBytes.entries.toList()) {
			if (gpu.containsKey(key)) {
				pendingBytes.remove(key)
				continue
			}
			val buffer = MemoryUtil.memAlloc(bytes.size)
			buffer.put(bytes).flip()
			val imageId = nvgCreateImageMem(nvg, 0, buffer)
			if (imageId <= 0) {
				MemoryUtil.memFree(buffer)
				pendingBytes.remove(key)
				SpotifyOverlay.LOGGER.warn("nvgCreateImageMem failed for {}", key)
				continue
			}
			gpu.put(key, GpuEntry(imageId, buffer))?.let { old ->
				nvgDeleteImage(nvg, old.imageId)
				MemoryUtil.memFree(old.buffer)
			}
			pendingBytes.remove(key)
		}

		val keep = activeKey
		for (key in gpu.keys.toList()) {
			if (key == keep) continue
			val entry = gpu.remove(key) ?: continue
			nvgDeleteImage(nvg, entry.imageId)
			MemoryUtil.memFree(entry.buffer)
		}
	}
}

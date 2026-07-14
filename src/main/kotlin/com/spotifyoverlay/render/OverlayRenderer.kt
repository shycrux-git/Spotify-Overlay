package com.spotifyoverlay.render

import com.spotifyoverlay.config.ModConfig
import com.spotifyoverlay.spotify.AlbumArtCache
import com.spotifyoverlay.spotify.LyricsState
import com.spotifyoverlay.spotify.SpotifyLyricsClient
import com.spotifyoverlay.spotify.TrackMetadataEnricher
import com.spotifyoverlay.spotify.TrackState
import com.spotifyoverlay.spotify.WindowsMediaSessionClient
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.nanovg.NVGColor
import org.lwjgl.nanovg.NVGPaint
import org.lwjgl.nanovg.NanoVG
import org.lwjgl.system.MemoryStack
import kotlin.math.exp
import kotlin.math.sin

object OverlayRenderer {
	private var lastFrameNanos = System.nanoTime()
	private var lyricScrollY = 0f
	private var lyricScrollTarget = 0f
	private var activeLyricIdx = -1
	private var lastTrackId: String? = null
	private var fadeNew = 1f
	private var lastEmittedProgressMs = 0
	private var lastEmittedTrackId: String? = null

	fun extract(graphics: GuiGraphicsExtractor) {
		val cfg = ModConfig.get()
		if (!cfg.overlayEnabled) return
		NvgPipRenderer.submit(graphics) { nvg ->
			AlbumArtCache.flushPending(nvg)
			draw(nvg, graphics.guiWidth().toFloat(), graphics.guiHeight().toFloat(), cfg)
		}
	}

	private fun draw(nvg: Long, sw: Float, sh: Float, cfg: ModConfig) {
		val nowNanos = System.nanoTime()
		var dt = (nowNanos - lastFrameNanos) / 1_000_000_000f
		lastFrameNanos = nowNanos
		if (dt > 0.1f) dt = 0.1f

		val track = WindowsMediaSessionClient.currentTrack()
		val layout = OverlayLayout.bounds(sw, sh, cfg)
		val s = cfg.overlayScale.coerceIn(OverlayLayout.MIN_SCALE, OverlayLayout.MAX_SCALE)
		val ow = layout.w
		val oh = layout.h
		val px = layout.x
		val py = layout.y

		val trackId = track?.id
		if (trackId != lastTrackId) {
			lastTrackId = trackId
			activeLyricIdx = -1
			lyricScrollY = 0f
			lyricScrollTarget = 0f
			fadeNew = 0f
			lastEmittedProgressMs = 0
			lastEmittedTrackId = null
		}
		fadeNew = expLerp(fadeNew, 1f, dt, 10f)
		val alpha = fadeNew

		val progressMs = smoothProgress(track)
		val lyrics = SpotifyLyricsClient.currentLyrics()
			?.takeIf { track != null && it.trackId == track.id }

		MemoryStack.stackPush().use { stack ->
			val bg = rgba(stack, 8, 8, 8, (0.92f * alpha * 255f).toInt().coerceIn(0, 255))
			val accent = rgba(stack, 255, 255, 255, (255f * alpha).toInt().coerceIn(0, 255))
			val titleC = rgba(stack, 255, 255, 255, (255f * alpha).toInt().coerceIn(0, 255))
			val artistC = rgba(stack, 170, 170, 170, (255f * alpha).toInt().coerceIn(0, 255))
			val albumC = rgba(stack, 130, 130, 130, (204f * alpha).toInt().coerceIn(0, 255))
			val progTrack = rgba(stack, 45, 45, 45, (200f * alpha).toInt().coerceIn(0, 255))
			val progPlayed = rgba(stack, 255, 255, 255, (242f * alpha).toInt().coerceIn(0, 255))
			val lyricActive = rgba(stack, 255, 255, 255, (255f * alpha).toInt().coerceIn(0, 255))
			val lyricPlayed = rgba(stack, 160, 160, 160, (173f * alpha).toInt().coerceIn(0, 255))
			val lyricPending = rgba(stack, 90, 90, 90, (160f * alpha).toInt().coerceIn(0, 255))
			val border = rgba(stack, 255, 255, 255, (40f * alpha).toInt().coerceIn(0, 255))
			val rim = rgba(stack, 255, 255, 255, (18f * alpha).toInt().coerceIn(0, 255))
			val shadow = rgba(stack, 0, 0, 0, (70f * alpha).toInt().coerceIn(0, 255))

			val cr = 14f * s
			val pad = 14f * s

			NanoVG.nvgBeginPath(nvg)
			NanoVG.nvgRoundedRect(nvg, px - 8f * s, py - 8f * s, ow + 16f * s, oh + 16f * s, cr + 6f * s)
			NanoVG.nvgFillColor(nvg, shadow)
			NanoVG.nvgFill(nvg)

			NanoVG.nvgBeginPath(nvg)
			NanoVG.nvgRoundedRect(nvg, px, py, ow, oh, cr)
			NanoVG.nvgFillColor(nvg, bg)
			NanoVG.nvgFill(nvg)

			val glass = NVGPaint.malloc(stack)
			val g0 = rgba(stack, 255, 255, 255, (18f * alpha).toInt().coerceIn(0, 255))
			val g1 = rgba(stack, 255, 255, 255, 0)
			NanoVG.nvgLinearGradient(nvg, px, py, px, py + oh * 0.2f, g0, g1, glass)
			NanoVG.nvgBeginPath(nvg)
			NanoVG.nvgRoundedRect(nvg, px + 2f * s, py + 2f * s, ow - 4f * s, oh * 0.2f, (cr - 2f * s).coerceAtLeast(0f))
			NanoVG.nvgFillPaint(nvg, glass)
			NanoVG.nvgFill(nvg)

			NanoVG.nvgBeginPath(nvg)
			NanoVG.nvgRoundedRect(nvg, px, py, ow, oh, cr)
			NanoVG.nvgStrokeWidth(nvg, 1f * s)
			NanoVG.nvgStrokeColor(nvg, border)
			NanoVG.nvgStroke(nvg)
			NanoVG.nvgBeginPath(nvg)
			NanoVG.nvgRoundedRect(nvg, px + 1f * s, py + 1f * s, ow - 2f * s, oh - 2f * s, cr - 1f * s)
			NanoVG.nvgStrokeWidth(nvg, 1f * s)
			NanoVG.nvgStrokeColor(nvg, rim)
			NanoVG.nvgStroke(nvg)

			val font = NvgContext.font()
			if (font >= 0) NanoVG.nvgFontFaceId(nvg, font)
			NanoVG.nvgTextAlign(nvg, NanoVG.NVG_ALIGN_LEFT or NanoVG.NVG_ALIGN_TOP)

			if (track == null) {
				NanoVG.nvgFontSize(nvg, 15f * s)
				NanoVG.nvgFillColor(nvg, lyricPending)
				val msg = "No media playing"
				val bounds = FloatArray(4)
				NanoVG.nvgTextBounds(nvg, 0f, 0f, msg, bounds)
				NanoVG.nvgText(nvg, px + (ow - (bounds[2] - bounds[0])) * 0.5f, py + oh * 0.5f - 8f * s, msg)
				return
			}

			val showLyrics = cfg.showLyrics
			val mediaH = OverlayLayout.MEDIA_H * s
			val artX = px + pad
			val artY = py + 12f * s
			val artSz = (mediaH - 24f * s).coerceAtLeast(28f * s)
			val infoX = artX + artSz + pad
			val infoY = artY + 4f * s
			val infoW = (px + ow - pad - infoX).coerceAtLeast(80f * s)
			val progBarY = py + mediaH - 22f * s

			val artRadius = 10f * s
			val imageId = AlbumArtCache.imageId()
			if (imageId >= 0) {
				val imgPaint = NVGPaint.malloc(stack)
				NanoVG.nvgImagePattern(nvg, artX, artY, artSz, artSz, 0f, imageId, 1f, imgPaint)
				NanoVG.nvgBeginPath(nvg)
				NanoVG.nvgRoundedRect(nvg, artX, artY, artSz, artSz, artRadius)
				NanoVG.nvgFillPaint(nvg, imgPaint)
				NanoVG.nvgFill(nvg)
			} else {
				val placeholder = rgba(stack, 32, 32, 32, (220f * alpha).toInt().coerceIn(0, 255))
				NanoVG.nvgBeginPath(nvg)
				NanoVG.nvgRoundedRect(nvg, artX, artY, artSz, artSz, artRadius)
				NanoVG.nvgFillColor(nvg, placeholder)
				NanoVG.nvgFill(nvg)
				NanoVG.nvgFontSize(nvg, 24f * s)
				NanoVG.nvgFillColor(nvg, accent)
				NanoVG.nvgText(nvg, artX + artSz * 0.5f - 6f * s, artY + artSz * 0.5f - 12f * s, "?")
			}
			NanoVG.nvgBeginPath(nvg)
			NanoVG.nvgRoundedRect(nvg, artX, artY, artSz, artSz, artRadius)
			NanoVG.nvgStrokeWidth(nvg, 1.5f * s)
			NanoVG.nvgStrokeColor(nvg, rgba(stack, 0, 0, 0, (66f * alpha).toInt().coerceIn(0, 255)))
			NanoVG.nvgStroke(nvg)

			var iy = infoY
			NanoVG.nvgFontSize(nvg, 17f * s)
			NanoVG.nvgFillColor(nvg, titleC)
			iy += drawEllipsized(nvg, track.title, infoX, iy, infoW) + 5f * s
			NanoVG.nvgFontSize(nvg, 13f * s)
			NanoVG.nvgFillColor(nvg, artistC)
			drawEllipsized(
				nvg,
				TrackMetadataEnricher.displayArtists(track.id, track.artists),
				infoX,
				iy,
				infoW,
			)

			val duration = track.durationMs.coerceAtLeast(0L)
			val frac = if (duration > 0) (progressMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
			val bH = 4f * s
			val bR = bH * 0.5f
			if (frac > 0.001f) {
				val glow = rgba(stack, 255, 255, 255, (28f * alpha).toInt().coerceIn(0, 255))
				NanoVG.nvgBeginPath(nvg)
				NanoVG.nvgRoundedRect(nvg, infoX, progBarY - bH * 0.5f, infoW * frac + bH, bH * 3f, bR * 3f)
				NanoVG.nvgFillColor(nvg, glow)
				NanoVG.nvgFill(nvg)
			}
			NanoVG.nvgBeginPath(nvg)
			NanoVG.nvgRoundedRect(nvg, infoX, progBarY, infoW, bH, bR)
			NanoVG.nvgFillColor(nvg, progTrack)
			NanoVG.nvgFill(nvg)
			if (frac > 0.001f) {
				NanoVG.nvgBeginPath(nvg)
				NanoVG.nvgRoundedRect(nvg, infoX, progBarY, infoW * frac, bH, bR)
				NanoVG.nvgFillColor(nvg, progPlayed)
				NanoVG.nvgFill(nvg)
				val scrubX = infoX + infoW * frac
				NanoVG.nvgBeginPath(nvg)
				NanoVG.nvgRoundedRect(nvg, scrubX - bH, progBarY - bH * 0.25f, bH * 2f, bH * 1.5f, bH)
				NanoVG.nvgFillColor(nvg, titleC)
				NanoVG.nvgFill(nvg)
			}
			NanoVG.nvgFontSize(nvg, 11f * s)
			NanoVG.nvgFillColor(nvg, albumC)
			val time = "${formatMs(progressMs.toLong())} / ${formatMs(duration)}"
			val tb = FloatArray(4)
			NanoVG.nvgTextBounds(nvg, 0f, 0f, time, tb)
			NanoVG.nvgText(nvg, infoX + infoW - (tb[2] - tb[0]), progBarY - 14f * s, time)

			if (!showLyrics) return

			val sepY = py + mediaH
			NanoVG.nvgBeginPath(nvg)
			NanoVG.nvgRect(nvg, px + pad, sepY, ow - 2f * pad, 1f * s)
			NanoVG.nvgFillColor(nvg, rgba(stack, 255, 255, 255, (28f * alpha).toInt().coerceIn(0, 255)))
			NanoVG.nvgFill(nvg)

			val lyrX = px + pad
			val lyrY = sepY + 8f * s
			val lyrW = ow - 2f * pad
			val lyrH = (py + oh - pad - lyrY).coerceAtLeast(36f * s)
			drawLyricsColumn(
				nvg = nvg,
				lyrX = lyrX,
				lyrY = lyrY,
				lyrW = lyrW,
				lyrH = lyrH,
				scale = s,
				dt = dt,
				progressMs = progressMs,
				lyrics = lyrics,
				lyricActive = lyricActive,
				lyricPlayed = lyricPlayed,
				lyricPending = lyricPending,
			)
		}
	}

	private fun drawLyricsColumn(
		nvg: Long,
		lyrX: Float,
		lyrY: Float,
		lyrW: Float,
		lyrH: Float,
		scale: Float,
		dt: Float,
		progressMs: Int,
		lyrics: LyricsState?,
		lyricActive: NVGColor,
		lyricPlayed: NVGColor,
		lyricPending: NVGColor,
	) {
		NanoVG.nvgSave(nvg)
		NanoVG.nvgScissor(nvg, lyrX, lyrY, lyrW, lyrH)

		val lines = lyrics?.lines.orEmpty()
		if (lyrics == null) {
			NanoVG.nvgFontSize(nvg, 13f * scale)
			NanoVG.nvgFillColor(nvg, lyricPending)
			centerText(nvg, "Fetching lyrics...", lyrX, lyrY, lyrW, lyrH)
			NanoVG.nvgRestore(nvg)
			return
		}
		if (lines.isEmpty()) {
			NanoVG.nvgFontSize(nvg, 13f * scale)
			NanoVG.nvgFillColor(nvg, lyricPending)
			centerText(nvg, lyrics.status ?: "No lyrics found", lyrX, lyrY, lyrW, lyrH)
			NanoVG.nvgRestore(nvg)
			return
		}

		var idx = -1
		for (i in lines.indices) {
			if (lines[i].startTimeMs <= progressMs) idx = i else break
		}
		val lineH = 22f * scale
		val spacing = 10f * scale
		if (idx != activeLyricIdx) {
			activeLyricIdx = idx
			if (idx >= 0) {
				val target = idx * (lineH + spacing) + lineH * 0.5f - lyrH * 0.5f
				val maxScroll = (lines.size * (lineH + spacing) - lyrH).coerceAtLeast(0f)
				lyricScrollTarget = target.coerceIn(0f, maxScroll)
			}
		}
		lyricScrollY = expLerp(lyricScrollY, lyricScrollTarget, dt, 6f)

		val t = System.nanoTime() / 1_000_000_000.0
		for (i in lines.indices) {
			val y = lyrY + i * (lineH + spacing) - lyricScrollY
			if (y + lineH < lyrY - lineH || y > lyrY + lyrH + lineH) continue

			val edge = 24f * scale
			var edgeAlpha = 1f
			if (y < lyrY + edge) edgeAlpha = ((y - lyrY + lineH) / (edge + lineH)).coerceIn(0f, 1f)
			if (y + lineH > lyrY + lyrH - edge) {
				edgeAlpha = minOf(edgeAlpha, ((lyrY + lyrH - y) / (edge + lineH)).coerceIn(0f, 1f))
			}

			val baseSize = if (i == idx) 15.5f * scale else 13.5f * scale
			NanoVG.nvgFontSize(nvg, baseSize)
			when {
				i == idx -> {
					val pulse = (0.55 + 0.45 * sin(t * 3.2)).toFloat()
					NanoVG.nvgFillColor(nvg, lyricActive)
					NanoVG.nvgGlobalAlpha(nvg, edgeAlpha * pulse.coerceIn(0.7f, 1f))
				}
				i < idx -> {
					NanoVG.nvgFillColor(nvg, lyricPlayed)
					NanoVG.nvgGlobalAlpha(nvg, edgeAlpha * 0.68f)
				}
				else -> {
					NanoVG.nvgFillColor(nvg, lyricPending)
					NanoVG.nvgGlobalAlpha(nvg, edgeAlpha * 0.48f)
				}
			}
			drawEllipsized(nvg, lines[i].words, lyrX, y, lyrW)
			NanoVG.nvgGlobalAlpha(nvg, 1f)
		}

		NanoVG.nvgRestore(nvg)
	}

	/** Prefer extrapolated clock; while playing, ignore small SMTC backward jitter. */
	private fun smoothProgress(track: TrackState?): Int {
		if (track == null) {
			lastEmittedTrackId = null
			lastEmittedProgressMs = 0
			return 0
		}
		val progressMs = track.interpolatedProgressMs().toInt()
		if (lastEmittedTrackId != track.id) {
			lastEmittedTrackId = track.id
			lastEmittedProgressMs = progressMs
			return progressMs
		}
		if (track.isPlaying) {
			val back = lastEmittedProgressMs - progressMs
			if (back in 1 until TrackState.SEEK_THRESHOLD_MS.toInt()) {
				return lastEmittedProgressMs
			}
		}
		lastEmittedProgressMs = progressMs
		return progressMs
	}

	private fun expLerp(current: Float, target: Float, dt: Float, speed: Float): Float {
		val t = 1f - exp((-dt * speed).toDouble()).toFloat()
		return current + (target - current) * t
	}

	private fun centerText(nvg: Long, text: String, x: Float, y: Float, w: Float, h: Float) {
		val bounds = FloatArray(4)
		NanoVG.nvgTextBounds(nvg, 0f, 0f, text, bounds)
		NanoVG.nvgText(nvg, x + (w - (bounds[2] - bounds[0])) * 0.5f, y + h * 0.5f - 7f, text)
	}

	private fun drawEllipsized(nvg: Long, text: String, x: Float, y: Float, maxWidth: Float): Float {
		if (text.isEmpty()) return 12f
		val bounds = FloatArray(4)
		NanoVG.nvgTextBounds(nvg, x, y, text, bounds)
		val height = (bounds[3] - bounds[1]).coerceAtLeast(12f)
		if (bounds[2] - bounds[0] <= maxWidth) {
			NanoVG.nvgText(nvg, x, y, text)
			return height
		}
		var low = 0
		var high = text.length
		var best = "…"
		while (low <= high) {
			val mid = (low + high) / 2
			val candidate = text.take(mid).trimEnd() + "…"
			NanoVG.nvgTextBounds(nvg, x, y, candidate, bounds)
			if (bounds[2] - bounds[0] <= maxWidth) {
				best = candidate
				low = mid + 1
			} else {
				high = mid - 1
			}
		}
		NanoVG.nvgText(nvg, x, y, best)
		return height
	}

	private fun formatMs(ms: Long): String {
		val totalSec = (ms / 1000).coerceAtLeast(0)
		return "%d:%02d".format(totalSec / 60, totalSec % 60)
	}

	private fun rgba(stack: MemoryStack, r: Int, g: Int, b: Int, a: Int): NVGColor {
		val color = NVGColor.malloc(stack)
		return NanoVG.nvgRGBA(r.toByte(), g.toByte(), b.toByte(), a.toByte(), color)
	}
}

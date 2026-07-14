package com.spotifyoverlay.render

import com.spotifyoverlay.config.ModConfig
import com.spotifyoverlay.spotify.AlbumArtCache
import com.spotifyoverlay.spotify.LyricsState
import com.spotifyoverlay.spotify.SpotifyLyricsClient
import com.spotifyoverlay.spotify.TrackMetadataEnricher
import com.spotifyoverlay.spotify.TrackState
import com.spotifyoverlay.spotify.WindowsMediaSessionClient
import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.FontMgr
import io.github.humbleui.skija.FontStyle
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.PaintMode
import io.github.humbleui.skija.Shader
import io.github.humbleui.skija.paragraph.FontCollection
import io.github.humbleui.skija.paragraph.Paragraph
import io.github.humbleui.skija.paragraph.ParagraphBuilder
import io.github.humbleui.skija.paragraph.ParagraphStyle
import io.github.humbleui.skija.paragraph.TextStyle
import io.github.humbleui.types.RRect
import io.github.humbleui.types.Rect
import net.minecraft.client.gui.GuiGraphicsExtractor
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

	private val fontCollection = FontCollection().setDefaultFontManager(FontMgr.getDefault())
	private val fontFamilies = arrayOf(
		"Segoe UI",
		"Yu Gothic UI",
		"Yu Gothic",
		"Microsoft YaHei",
		"Malgun Gothic",
		"sans-serif",
	)

	fun extract(graphics: GuiGraphicsExtractor) {
		val cfg = ModConfig.get()
		if (!cfg.overlayEnabled) return

		val draw: (Canvas) -> Unit = { canvas ->
			AlbumArtCache.flushPending()
			draw(canvas, graphics.guiWidth().toFloat(), graphics.guiHeight().toFloat(), cfg)
		}

		// Vulkan: queue for main-target composite at GameRenderer TAIL (PiP wrap is unsafe).
		// OpenGL: draw into PiP texture as before.
		if (SkijaBackends.isVulkan()) {
			VulkanOverlayQueue.submit(graphics.guiWidth(), graphics.guiHeight(), draw)
		} else {
			SkijaPipRenderer.submit(graphics, draw)
		}
	}

	private fun draw(canvas: Canvas, sw: Float, sh: Float, cfg: ModConfig) {
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

		val cr = 14f * s
		val pad = 14f * s

		fillRoundRect(canvas, px - 8f * s, py - 8f * s, ow + 16f * s, oh + 16f * s, cr + 6f * s, argb(0, 0, 0, (70f * alpha).toInt()))
		fillRoundRect(canvas, px, py, ow, oh, cr, argb(8, 8, 8, (0.92f * alpha * 255f).toInt()))

		Paint().use { paint ->
			paint.setAntiAlias(true)
			paint.setShader(
				Shader.makeLinearGradient(
					px, py, px, py + oh * 0.2f,
					intArrayOf(argb(255, 255, 255, (18f * alpha).toInt()), argb(255, 255, 255, 0)),
				),
			)
			canvas.drawRRect(
				RRect.makeXYWH(px + 2f * s, py + 2f * s, ow - 4f * s, oh * 0.2f, (cr - 2f * s).coerceAtLeast(0f)),
				paint,
			)
		}

		strokeRoundRect(canvas, px, py, ow, oh, cr, 1f * s, argb(255, 255, 255, (40f * alpha).toInt()))
		strokeRoundRect(canvas, px + 1f * s, py + 1f * s, ow - 2f * s, oh - 2f * s, cr - 1f * s, 1f * s, argb(255, 255, 255, (18f * alpha).toInt()))

		val titleC = argb(255, 255, 255, (255f * alpha).toInt())
		val artistC = argb(170, 170, 170, (255f * alpha).toInt())
		val albumC = argb(130, 130, 130, (204f * alpha).toInt())
		val lyricPending = argb(90, 90, 90, (160f * alpha).toInt())
		val lyricActive = argb(255, 255, 255, (255f * alpha).toInt())
		val lyricPlayed = argb(160, 160, 160, (173f * alpha).toInt())

		if (track == null) {
			val msg = "No media playing"
			val tw = measureText(msg, 15f * s)
			drawText(canvas, msg, px + (ow - tw) * 0.5f, py + oh * 0.5f - 8f * s, 15f * s, lyricPending)
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

		val art = AlbumArtCache.currentImage()
		if (art != null) {
			canvas.save()
			canvas.clipRRect(RRect.makeXYWH(artX, artY, artSz, artSz, artRadius), true)
			canvas.drawImageRect(art, Rect.makeXYWH(artX, artY, artSz, artSz))
			canvas.restore()
		} else {
			fillRoundRect(canvas, artX, artY, artSz, artSz, artRadius, argb(32, 32, 32, (220f * alpha).toInt()))
			drawText(canvas, "?", artX + artSz * 0.5f - 6f * s, artY + artSz * 0.5f - 12f * s, 24f * s, titleC)
		}
		strokeRoundRect(canvas, artX, artY, artSz, artSz, artRadius, 1.5f * s, argb(0, 0, 0, (66f * alpha).toInt()))

		var iy = infoY
		iy += drawEllipsized(canvas, track.title, infoX, iy, infoW, 17f * s, titleC) + 5f * s
		drawEllipsized(
			canvas,
			TrackMetadataEnricher.displayArtists(track.id, track.artists),
			infoX,
			iy,
			infoW,
			13f * s,
			artistC,
		)

		val duration = track.durationMs.coerceAtLeast(0L)
		val frac = if (duration > 0) (progressMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
		val bH = 4f * s
		val bR = bH * 0.5f
		if (frac > 0.001f) {
			fillRoundRect(canvas, infoX, progBarY - bH * 0.5f, infoW * frac + bH, bH * 3f, bR * 3f, argb(255, 255, 255, (28f * alpha).toInt()))
		}
		fillRoundRect(canvas, infoX, progBarY, infoW, bH, bR, argb(45, 45, 45, (200f * alpha).toInt()))
		if (frac > 0.001f) {
			fillRoundRect(canvas, infoX, progBarY, infoW * frac, bH, bR, argb(255, 255, 255, (242f * alpha).toInt()))
			val scrubX = infoX + infoW * frac
			fillRoundRect(canvas, scrubX - bH, progBarY - bH * 0.25f, bH * 2f, bH * 1.5f, bH, titleC)
		}
		val time = "${formatMs(progressMs.toLong())} / ${formatMs(duration)}"
		val tw = measureText(time, 11f * s)
		drawText(canvas, time, infoX + infoW - tw, progBarY - 14f * s, 11f * s, albumC)

		if (!showLyrics) return

		val sepY = py + mediaH
		fillRect(canvas, px + pad, sepY, ow - 2f * pad, 1f * s, argb(255, 255, 255, (28f * alpha).toInt()))

		val lyrX = px + pad
		val lyrY = sepY + 8f * s
		val lyrW = ow - 2f * pad
		val lyrH = (py + oh - pad - lyrY).coerceAtLeast(36f * s)
		drawLyricsColumn(canvas, lyrX, lyrY, lyrW, lyrH, s, dt, progressMs, lyrics, lyricActive, lyricPlayed, lyricPending)
	}

	private fun drawLyricsColumn(
		canvas: Canvas,
		lyrX: Float,
		lyrY: Float,
		lyrW: Float,
		lyrH: Float,
		scale: Float,
		dt: Float,
		progressMs: Int,
		lyrics: LyricsState?,
		lyricActive: Int,
		lyricPlayed: Int,
		lyricPending: Int,
	) {
		canvas.save()
		canvas.clipRect(Rect.makeXYWH(lyrX, lyrY, lyrW, lyrH))

		val lines = lyrics?.lines.orEmpty()
		if (lyrics == null) {
			centerText(canvas, "Fetching lyrics...", lyrX, lyrY, lyrW, lyrH, 13f * scale, lyricPending)
			canvas.restore()
			return
		}
		if (lines.isEmpty()) {
			centerText(canvas, lyrics.status ?: "No lyrics found", lyrX, lyrY, lyrW, lyrH, 13f * scale, lyricPending)
			canvas.restore()
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
			val color = when {
				i == idx -> {
					val pulse = (0.55 + 0.45 * sin(t * 3.2)).toFloat().coerceIn(0.7f, 1f)
					multiplyAlpha(lyricActive, edgeAlpha * pulse)
				}
				i < idx -> multiplyAlpha(lyricPlayed, edgeAlpha * 0.68f)
				else -> multiplyAlpha(lyricPending, edgeAlpha * 0.48f)
			}
			drawEllipsized(canvas, lines[i].words, lyrX, y, lyrW, baseSize, color)
		}

		canvas.restore()
	}

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

	private fun centerText(canvas: Canvas, text: String, x: Float, y: Float, w: Float, h: Float, size: Float, color: Int) {
		val tw = measureText(text, size)
		drawText(canvas, text, x + (w - tw) * 0.5f, y + h * 0.5f - 7f, size, color)
	}

	private fun drawEllipsized(
		canvas: Canvas,
		text: String,
		x: Float,
		y: Float,
		maxWidth: Float,
		size: Float,
		color: Int,
	): Float {
		if (text.isEmpty()) return 12f
		val full = measureText(text, size)
		if (full <= maxWidth) {
			drawText(canvas, text, x, y, size, color)
			return size * 1.2f
		}
		var low = 0
		var high = text.length
		var best = "…"
		while (low <= high) {
			val mid = (low + high) / 2
			val candidate = text.take(mid).trimEnd() + "…"
			if (measureText(candidate, size) <= maxWidth) {
				best = candidate
				low = mid + 1
			} else {
				high = mid - 1
			}
		}
		drawText(canvas, best, x, y, size, color)
		return size * 1.2f
	}

	private fun drawText(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
		buildParagraph(text, size, color).use { para ->
			para.layout(10_000f)
			para.paint(canvas, x, y)
		}
	}

	private fun measureText(text: String, size: Float): Float =
		buildParagraph(text, size, 0xFFFFFFFF.toInt()).use { para ->
			para.layout(10_000f)
			para.maxIntrinsicWidth
		}

	private fun buildParagraph(text: String, size: Float, color: Int): Paragraph {
		val style = TextStyle()
			.setColor(color)
			.setFontSize(size)
			.setFontStyle(FontStyle.NORMAL)
			.setFontFamilies(fontFamilies)
		return ParagraphBuilder(ParagraphStyle(), fontCollection)
			.pushStyle(style)
			.addText(text)
			.popStyle()
			.build()
	}

	private fun fillRoundRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
		Paint().use { paint ->
			paint.setAntiAlias(true).setColor(color).setMode(PaintMode.FILL)
			canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), paint)
		}
	}

	private fun strokeRoundRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, stroke: Float, color: Int) {
		Paint().use { paint ->
			paint.setAntiAlias(true).setColor(color).setMode(PaintMode.STROKE).setStrokeWidth(stroke)
			canvas.drawRRect(RRect.makeXYWH(x, y, w, h, r), paint)
		}
	}

	private fun fillRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
		Paint().use { paint ->
			paint.setAntiAlias(true).setColor(color).setMode(PaintMode.FILL)
			canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint)
		}
	}

	private fun argb(r: Int, g: Int, b: Int, a: Int): Int {
		val aa = a.coerceIn(0, 255)
		val rr = r.coerceIn(0, 255)
		val gg = g.coerceIn(0, 255)
		val bb = b.coerceIn(0, 255)
		return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
	}

	private fun multiplyAlpha(color: Int, factor: Float): Int {
		val a = ((color ushr 24) and 0xFF) * factor.coerceIn(0f, 1f)
		return (color and 0x00FFFFFF) or (a.toInt().coerceIn(0, 255) shl 24)
	}

	private fun formatMs(ms: Long): String {
		val totalSec = (ms / 1000).coerceAtLeast(0)
		return "%d:%02d".format(totalSec / 60, totalSec % 60)
	}

	private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
		try {
			return block(this)
		} finally {
			close()
		}
	}
}

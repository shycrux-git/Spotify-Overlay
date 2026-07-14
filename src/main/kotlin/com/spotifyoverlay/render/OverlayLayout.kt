package com.spotifyoverlay.render

import com.spotifyoverlay.config.ModConfig

object OverlayLayout {
	const val BASE_W = 480f
	const val MEDIA_H = 112f
	const val LYRICS_H = 120f
	const val MIN_SCALE = 0.15f
	const val MAX_SCALE = 2.5f

	data class Bounds(val x: Float, val y: Float, val w: Float, val h: Float) {
		fun contains(mx: Double, my: Double): Boolean =
			mx >= x && mx < x + w && my >= y && my < y + h
	}

	fun baseHeight(showLyrics: Boolean): Float =
		if (showLyrics) MEDIA_H + LYRICS_H else MEDIA_H

	fun bounds(screenW: Float, screenH: Float, cfg: ModConfig = ModConfig.get()): Bounds {
		val scale = cfg.overlayScale.coerceIn(MIN_SCALE, MAX_SCALE)
		val ow = BASE_W * scale
		val oh = baseHeight(cfg.showLyrics) * scale
		var px = if (cfg.overlayX >= 0f) cfg.overlayX else screenW + cfg.overlayX - ow
		var py = if (cfg.overlayY >= 0f) cfg.overlayY else screenH + cfg.overlayY - oh
		px = px.coerceIn(0f, (screenW - ow).coerceAtLeast(0f))
		py = py.coerceIn(0f, (screenH - oh).coerceAtLeast(0f))
		return Bounds(px, py, ow, oh)
	}
}

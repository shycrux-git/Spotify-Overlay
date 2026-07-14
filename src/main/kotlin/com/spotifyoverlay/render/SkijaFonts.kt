package com.spotifyoverlay.render

import com.spotifyoverlay.SpotifyOverlay
import io.github.humbleui.skija.FontMgr
import io.github.humbleui.skija.FontStyle
import io.github.humbleui.skija.Typeface
import java.io.File

object SkijaFonts {
	@Volatile
	private var ready = false

	fun ensureLoaded() {
		if (ready) return
		synchronized(this) {
			if (ready) return
			val mgr = FontMgr.getDefault()
			val windir = System.getenv("WINDIR") ?: "C:\\Windows"
			val fontsDir = "$windir${File.separator}Fonts"

			val primary = loadFile(mgr, "$fontsDir${File.separator}segoeui.ttf")
				?: loadFile(mgr, "$fontsDir${File.separator}arial.ttf")
				?: mgr.matchFamilyStyle("sans-serif", FontStyle.NORMAL)

			var candidates = 0
			for (path in listOf(
				"$fontsDir${File.separator}YuGothR.ttc",
				"$fontsDir${File.separator}msyh.ttc",
				"$fontsDir${File.separator}malgun.ttf",
			)) {
				if (File(path).isFile) candidates++
			}

			SpotifyOverlay.LOGGER.info(
				"Skija fonts ready (primary={}, CJK candidates on disk={})",
				primary?.familyName ?: "FontMgr default",
				candidates,
			)
			primary?.close()
			ready = true
		}
	}

	private fun loadFile(mgr: FontMgr, path: String): Typeface? {
		if (!File(path).isFile) return null
		return try {
			mgr.makeFromFile(path)
		} catch (_: Exception) {
			null
		}
	}
}

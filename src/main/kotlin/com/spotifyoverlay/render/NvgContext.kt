package com.spotifyoverlay.render

import com.spotifyoverlay.SpotifyOverlay
import org.lwjgl.nanovg.NanoVG
import org.lwjgl.nanovg.NanoVGGL3
import java.io.File

object NvgContext {
	@Volatile
	private var handle: Long = 0L
	private var fontRegular: Int = -1

	fun handle(): Long = handle

	fun font(): Int = fontRegular

	fun ensureInitialized(): Long {
		if (handle != 0L) return handle
		synchronized(this) {
			if (handle != 0L) return handle
			val ctx = NanoVGGL3.nvgCreate(NanoVGGL3.NVG_ANTIALIAS or NanoVGGL3.NVG_STENCIL_STROKES)
			if (ctx == 0L) {
				SpotifyOverlay.LOGGER.error("Failed to create NanoVG context")
				return 0L
			}
			handle = ctx
			fontRegular = loadFonts(ctx)
			if (fontRegular < 0) {
				SpotifyOverlay.LOGGER.warn("Failed to load any UI font for NanoVG")
			}
			return handle
		}
	}

	fun beginFrame(width: Float, height: Float, pixelRatio: Float) {
		val ctx = ensureInitialized()
		if (ctx == 0L) return
		NanoVG.nvgBeginFrame(ctx, width, height, pixelRatio)
	}

	fun endFrame() {
		val ctx = handle
		if (ctx == 0L) return
		NanoVG.nvgEndFrame(ctx)
	}

	/**
	 * Loads a Latin base font plus CJK / symbol fallbacks so titles and lyrics
	 * with Japanese, Chinese, Korean, etc. don't render as tofu boxes.
	 */
	private fun loadFonts(ctx: Long): Int {
		val windir = System.getenv("WINDIR") ?: "C:\\Windows"
		val fontsDir = "$windir${File.separator}Fonts"

		val primary = firstExisting(
			"$fontsDir${File.separator}segoeui.ttf",
			"$fontsDir${File.separator}arial.ttf",
			"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
			"/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
			"/System/Library/Fonts/Supplemental/Arial.ttf",
			"/System/Library/Fonts/SFNS.ttf",
		)

		val baseId = createFont(ctx, "regular", primary)
		if (baseId < 0) return -1

		// Fallbacks are tried in order when the base font lacks a glyph.
		val fallbacks = listOf(
			// Japanese
			"jp" to listOf(
				"$fontsDir${File.separator}YuGothR.ttc",
				"$fontsDir${File.separator}YuGothM.ttc",
				"$fontsDir${File.separator}msgothic.ttc",
				"/System/Library/Fonts/ヒラギノ角ゴシック W3.ttc",
				"/System/Library/Fonts/Hiragino Sans GB.ttc",
				"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
				"/usr/share/fonts/opentype/noto/NotoSansCJKjp-Regular.otf",
			),
			// Chinese
			"zh" to listOf(
				"$fontsDir${File.separator}msyh.ttc",
				"$fontsDir${File.separator}simsun.ttc",
				"/System/Library/Fonts/PingFang.ttc",
				"/usr/share/fonts/opentype/noto/NotoSansCJKsc-Regular.otf",
			),
			// Korean
			"ko" to listOf(
				"$fontsDir${File.separator}malgun.ttf",
				"/System/Library/Fonts/AppleSDGothicNeo.ttc",
				"/usr/share/fonts/opentype/noto/NotoSansCJKkr-Regular.otf",
			),
			// Symbols / emoji leftovers
			"sym" to listOf(
				"$fontsDir${File.separator}seguisym.ttf",
				"$fontsDir${File.separator}seguiemj.ttf",
			),
		)

		var attached = 0
		for ((name, candidates) in fallbacks) {
			val path = firstExisting(*candidates.toTypedArray()) ?: continue
			val id = createFont(ctx, "fallback-$name", path)
			if (id < 0) continue
			if (NanoVG.nvgAddFallbackFontId(ctx, baseId, id) != 0) {
				SpotifyOverlay.LOGGER.debug("Attached fallback font {} ({})", name, File(path).name)
				attached++
			}
		}

		SpotifyOverlay.LOGGER.info(
			"NanoVG fonts: base={} (+{} fallbacks)",
			File(primary ?: "").name.ifBlank { "none" },
			attached,
		)
		return baseId
	}

	private fun createFont(ctx: Long, name: String, path: String?): Int {
		if (path.isNullOrBlank() || !File(path).isFile) return -1
		val id = NanoVG.nvgCreateFont(ctx, name, path)
		if (id < 0) {
			SpotifyOverlay.LOGGER.debug("Failed to load font {}: {}", name, path)
		}
		return id
	}

	private fun firstExisting(vararg paths: String): String? =
		paths.firstOrNull { File(it).isFile }
}

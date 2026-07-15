package com.spotifyoverlay.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.spotifyoverlay.SpotifyOverlay
import com.spotifyoverlay.spotify.AlbumArtCache
import io.github.humbleui.skija.Canvas
import io.github.humbleui.types.Rect
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.ceil
import kotlin.math.floor

object OverlayCompositeQueue {
	@Volatile
	private var pending: Pending? = null

	@Volatile
	private var loggedOk = false

	@Volatile
	private var loggedFail = false

	fun submitVulkan(
		guiWidth: Int,
		guiHeight: Int,
		originX: Float,
		originY: Float,
		regionW: Float,
		regionH: Float,
		draw: (Canvas) -> Unit,
	) {
		pending = Pending(guiWidth, guiHeight, originX, originY, regionW, regionH, draw)
	}

	fun blitOpenGl(
		graphics: GuiGraphicsExtractor,
		guiWidth: Int,
		guiHeight: Int,
		originX: Float,
		originY: Float,
		regionW: Float,
		regionH: Float,
		staticDirty: Boolean,
		staticUrgent: Boolean,
		lyricsStrip: Rect?,
		lyricsDirty: Boolean,
		progressStrip: Rect,
		freezeDynamic: Boolean,
		drawStatic: (Canvas) -> Unit,
		drawLyrics: (Canvas) -> Unit,
		drawProgress: (Canvas) -> Unit,
	) {
		try {
			SkijaFonts.ensureLoaded()
			AlbumArtCache.flushPending()
			val view = SkijaBackends.renderOpenGlAtlas(
				guiWidth,
				guiHeight,
				originX,
				originY,
				regionW,
				regionH,
				staticDirty,
				staticUrgent,
				lyricsStrip,
				lyricsDirty,
				progressStrip,
				freezeDynamic,
				drawStatic,
				drawLyrics,
				drawProgress,
			) ?: return
			val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
			val x0 = floor(originX).toInt()
			val y0 = floor(originY).toInt()
			val x1 = ceil(originX + regionW).toInt()
			val y1 = ceil(originY + regionH).toInt()
			graphics.blit(view, sampler, x0, y0, x1, y1, 0f, 1f, 0f, 1f)
			if (!loggedOk) {
				loggedOk = true
				SpotifyOverlay.LOGGER.info(
					"Skija overlay OK (backend=opengl, region={}x{} at {},{})",
					regionW.toInt(),
					regionH.toInt(),
					originX.toInt(),
					originY.toInt(),
				)
			}
		} catch (e: Exception) {
			if (!loggedFail) {
				loggedFail = true
				SpotifyOverlay.LOGGER.error(
					"Skija OpenGL overlay failed: {}: {}",
					e.javaClass.simpleName,
					e.message,
				)
			}
			SpotifyOverlay.LOGGER.warn("Skija OpenGL overlay exception", e)
		} finally {
			AlbumArtCache.reapDeferredCloses()
		}
	}

	fun flush() {
		val frame = pending ?: return
		pending = null
		if (!SkijaBackends.isVulkan()) return
		try {
			SkijaFonts.ensureLoaded()
			SkijaBackends.renderVulkanOntoMain(
				frame.guiWidth,
				frame.guiHeight,
				frame.originX,
				frame.originY,
				frame.regionW,
				frame.regionH,
				frame.draw,
			)
			if (!loggedOk) {
				loggedOk = true
				SpotifyOverlay.LOGGER.info(
					"Skija overlay OK (backend=vulkan, region={}x{} at {},{})",
					frame.regionW.toInt(),
					frame.regionH.toInt(),
					frame.originX.toInt(),
					frame.originY.toInt(),
				)
			}
		} catch (e: Exception) {
			if (!loggedFail) {
				loggedFail = true
				SpotifyOverlay.LOGGER.error(
					"Skija Vulkan overlay failed: {}: {}",
					e.javaClass.simpleName,
					e.message,
				)
			}
			SpotifyOverlay.LOGGER.warn("Skija Vulkan overlay exception", e)
		} finally {
			AlbumArtCache.reapDeferredCloses()
		}
	}

	private data class Pending(
		val guiWidth: Int,
		val guiHeight: Int,
		val originX: Float,
		val originY: Float,
		val regionW: Float,
		val regionH: Float,
		val draw: (Canvas) -> Unit,
	)
}

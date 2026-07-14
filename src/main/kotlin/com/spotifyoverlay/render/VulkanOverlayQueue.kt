package com.spotifyoverlay.render

import com.spotifyoverlay.SpotifyOverlay
import io.github.humbleui.skija.Canvas

/**
 * Vulkan cannot safely wrap Minecraft's PiP color attachment mid-pass.
 * Queued draws are flushed at [GameRenderer] TAIL onto the main render target
 * (same pattern as cryleak/Odin).
 */
object VulkanOverlayQueue {
	@Volatile
	private var pending: Pending? = null

	@Volatile
	private var loggedOk = false

	@Volatile
	private var loggedFail = false

	fun submit(guiWidth: Int, guiHeight: Int, draw: (Canvas) -> Unit) {
		pending = Pending(guiWidth, guiHeight, draw)
	}

	fun flush() {
		val frame = pending ?: return
		pending = null
		try {
			SkijaFonts.ensureLoaded()
			SkijaBackends.renderVulkanOntoMain(frame.guiWidth, frame.guiHeight, frame.draw)
			if (!loggedOk) {
				loggedOk = true
				SpotifyOverlay.LOGGER.info(
					"Skija Vulkan overlay OK (composited to main, gui={}x{})",
					frame.guiWidth,
					frame.guiHeight,
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
		}
	}

	private data class Pending(
		val guiWidth: Int,
		val guiHeight: Int,
		val draw: (Canvas) -> Unit,
	)
}

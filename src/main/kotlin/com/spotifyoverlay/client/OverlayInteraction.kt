package com.spotifyoverlay.client

import com.spotifyoverlay.config.ModConfig
import com.spotifyoverlay.render.OverlayLayout
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/** Chat-screen drag and scroll for moving / scaling the overlay. */
object OverlayInteraction {
	@Volatile
	private var dragging = false
	private var grabOffsetX = 0.0
	private var grabOffsetY = 0.0

	fun onMouseClicked(event: MouseButtonEvent): Boolean {
		if (!ModConfig.get().overlayEnabled) return false
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false

		val bounds = currentBounds() ?: return false
		if (!bounds.contains(event.x(), event.y())) return false

		val cfg = ModConfig.get()
		cfg.overlayX = bounds.x
		cfg.overlayY = bounds.y

		grabOffsetX = event.x() - bounds.x
		grabOffsetY = event.y() - bounds.y
		dragging = true
		return true
	}

	fun onMouseDragged(event: MouseButtonEvent): Boolean {
		if (!dragging) return false
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
		applyDragAt(event.x(), event.y())
		return true
	}

	fun onMouseReleased(event: MouseButtonEvent): Boolean {
		if (!dragging) return false
		if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
		applyDragAt(event.x(), event.y())
		dragging = false
		ModConfig.save()
		return true
	}

	fun onMouseScrolled(mouseX: Double, mouseY: Double, vertical: Double): Boolean {
		if (!ModConfig.get().overlayEnabled) return false
		if (vertical == 0.0) return false

		val client = Minecraft.getInstance()
		val sw = client.window.guiScaledWidth.toFloat()
		val sh = client.window.guiScaledHeight.toFloat()
		val cfg = ModConfig.get()
		val bounds = OverlayLayout.bounds(sw, sh, cfg)
		if (!bounds.contains(mouseX, mouseY)) return false

		val oldScale = cfg.overlayScale.coerceIn(OverlayLayout.MIN_SCALE, OverlayLayout.MAX_SCALE)
		val newScale = (oldScale + 0.08f * vertical.sign.toFloat())
			.coerceIn(OverlayLayout.MIN_SCALE, OverlayLayout.MAX_SCALE)
		if (newScale == oldScale) return true

		val cx = bounds.x + bounds.w * 0.5f
		val cy = bounds.y + bounds.h * 0.5f
		val nw = OverlayLayout.BASE_W * newScale
		val nh = OverlayLayout.baseHeight(cfg.showLyrics) * newScale
		val nx = (cx - nw * 0.5f).coerceIn(0f, (sw - nw).coerceAtLeast(0f))
		val ny = (cy - nh * 0.5f).coerceIn(0f, (sh - nh).coerceAtLeast(0f))

		ModConfig.update {
			overlayScale = newScale
			overlayX = nx
			overlayY = ny
		}
		return true
	}

	private fun applyDragAt(mouseX: Double, mouseY: Double) {
		val client = Minecraft.getInstance()
		val sw = client.window.guiScaledWidth.toFloat()
		val sh = client.window.guiScaledHeight.toFloat()
		val cfg = ModConfig.get()
		val scale = cfg.overlayScale.coerceIn(OverlayLayout.MIN_SCALE, OverlayLayout.MAX_SCALE)
		val ow = OverlayLayout.BASE_W * scale
		val oh = OverlayLayout.baseHeight(cfg.showLyrics) * scale

		cfg.overlayX = (mouseX - grabOffsetX).toFloat().coerceIn(0f, (sw - ow).coerceAtLeast(0f))
		cfg.overlayY = (mouseY - grabOffsetY).toFloat().coerceIn(0f, (sh - oh).coerceAtLeast(0f))
	}

	private fun currentBounds(): OverlayLayout.Bounds? {
		val window = Minecraft.getInstance().window
		return OverlayLayout.bounds(window.guiScaledWidth.toFloat(), window.guiScaledHeight.toFloat())
	}

	private val Double.sign: Int
		get() = when {
			this > 0.0 -> 1
			this < 0.0 -> -1
			else -> 0
		}
}

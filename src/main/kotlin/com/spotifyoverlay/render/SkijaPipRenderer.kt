package com.spotifyoverlay.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.PoseStack
import com.spotifyoverlay.SpotifyOverlay
import io.github.humbleui.skija.Canvas
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc

/** Renders Skija content into Minecraft's picture-in-picture GUI texture (OpenGL or Vulkan). */
class SkijaPipRenderer : PictureInPictureRenderer<SkijaPipRenderer.SkijaRenderState>() {
	@Volatile
	private var loggedFail = false

	@Volatile
	private var loggedOk = false

	override fun getRenderStateClass(): Class<SkijaRenderState> = SkijaRenderState::class.java

	override fun getTextureLabel(): String = "spotify-overlay-skija"

	override fun getTranslateY(height: Int, windowScaleFactor: Int): Float = height / 2f

	override fun textureIsReadyToBlit(state: SkijaRenderState): Boolean = false

	override fun renderToTexture(
		state: SkijaRenderState,
		poseStack: PoseStack,
		submitNodeCollector: SubmitNodeCollector,
	) {
		val window = Minecraft.getInstance().window
		if (window.isIconified) return

		val colorView = RenderSystem.outputColorTextureOverride as? GpuTextureView
		if (colorView == null) {
			logFailOnce("missing PiP color texture override")
			return
		}

		val texture = colorView.texture()
		val width = colorView.getWidth(0).takeIf { it > 0 } ?: return
		val height = colorView.getHeight(0).takeIf { it > 0 } ?: return

		val guiW = window.guiScaledWidth.toFloat().coerceAtLeast(1f)
		val dpr = (width.toFloat() / guiW).takeIf { it.isFinite() && it > 0f } ?: 1f

		try {
			SkijaFonts.ensureLoaded()
			val backend = SkijaBackends.ensure(texture)
			backend.renderInto(texture, dpr, state.callback)
			if (!loggedOk) {
				loggedOk = true
				SpotifyOverlay.LOGGER.info(
					"Skija PiP draw OK (backend={}, {}x{}, dpr={})",
					texture.javaClass.simpleName,
					width,
					height,
					"%.2f".format(dpr),
				)
			}
		} catch (e: Exception) {
			logFailOnce("Skija PiP draw failed: ${e.javaClass.simpleName}: ${e.message}")
			SpotifyOverlay.LOGGER.warn("Skija PiP exception", e)
		}
	}

	private fun logFailOnce(reason: String) {
		if (loggedFail) return
		loggedFail = true
		SpotifyOverlay.LOGGER.error("Skija PiP render failed: {}", reason)
	}

	data class SkijaRenderState(
		private val width: Int,
		private val height: Int,
		private val poseMatrix: Matrix3x2fc,
		private val scissor: ScreenRectangle?,
		private val boundsRect: ScreenRectangle?,
		val callback: (Canvas) -> Unit,
	) : PictureInPictureRenderState {
		override fun x0(): Int = 0
		override fun y0(): Int = 0
		override fun x1(): Int = width
		override fun y1(): Int = height
		override fun scale(): Float = 1f
		override fun pose(): Matrix3x2fc = poseMatrix
		override fun scissorArea(): ScreenRectangle? = scissor
		override fun bounds(): ScreenRectangle? = boundsRect
	}

	companion object {
		fun submit(graphics: GuiGraphicsExtractor, callback: (Canvas) -> Unit) {
			val window = Minecraft.getInstance().window
			if (window.isIconified || window.guiScaledWidth <= 0 || window.guiScaledHeight <= 0) return

			val pose = Matrix3x2f(graphics.pose())
			val screenRect = ScreenRectangle(0, 0, graphics.guiWidth(), graphics.guiHeight()).transformMaxBounds(pose)
			if (screenRect.width() <= 0 || screenRect.height() <= 0) return

			val scissor = graphics.scissorStack.peek()
			val bounds = scissor?.intersection(screenRect) ?: screenRect
			if (bounds.width() <= 0 || bounds.height() <= 0) return

			graphics.guiRenderState.addPicturesInPictureState(
				SkijaRenderState(
					graphics.guiWidth(),
					graphics.guiHeight(),
					pose,
					scissor,
					bounds,
					callback,
				),
			)
		}
	}
}

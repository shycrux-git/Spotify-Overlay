package com.spotifyoverlay.render

import com.mojang.blaze3d.opengl.GlTextureView
import com.mojang.blaze3d.opengl.SpotifyOverlayGlAccess
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.spotifyoverlay.SpotifyOverlay
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL33C

/** Renders NanoVG content into Minecraft's picture-in-picture GUI texture. */
class NvgPipRenderer : PictureInPictureRenderer<NvgPipRenderer.NvgRenderState>() {
	@Volatile
	private var loggedFail = false

	override fun getRenderStateClass(): Class<NvgRenderState> = NvgRenderState::class.java

	override fun getTextureLabel(): String = "spotify-overlay-nvg"

	override fun getTranslateY(height: Int, windowScaleFactor: Int): Float = height / 2f

	override fun textureIsReadyToBlit(state: NvgRenderState): Boolean = false

	override fun renderToTexture(
		state: NvgRenderState,
		poseStack: PoseStack,
		submitNodeCollector: SubmitNodeCollector,
	) {
		val window = Minecraft.getInstance().window
		if (window.isIconified) return

		val colorView = RenderSystem.outputColorTextureOverride as? GlTextureView
		val depthView = RenderSystem.outputDepthTextureOverride as? GlTextureView
		if (colorView == null || depthView == null) {
			logFailOnce("missing output color/depth texture overrides")
			return
		}

		val width = colorView.getWidth(0).takeIf { it > 0 } ?: return
		val height = colorView.getHeight(0).takeIf { it > 0 } ?: return
		val fbo = SpotifyOverlayGlAccess.resolveFbo(colorView, depthView)
		if (fbo <= 0) {
			logFailOnce("failed to resolve PiP FBO (OpenGL backend required)")
			return
		}

		val rawW = width.toFloat()
		val rawH = height.toFloat()
		val guiW = window.guiScaledWidth.toFloat().coerceAtLeast(1f)
		val dpr = (rawW / guiW).takeIf { it.isFinite() && it > 0f } ?: 1f

		val nvg = NvgContext.ensureInitialized()
		if (nvg == 0L) {
			logFailOnce("NanoVG context is 0")
			return
		}

		val glBackup = GlStateBackup()
		glBackup.save()
		try {
			GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo)
			GL11.glViewport(0, 0, width, height)
			GL33C.glBindSampler(0, 0)

			NvgContext.beginFrame(rawW / dpr, rawH / dpr, dpr)
			try {
				state.callback.invoke(nvg)
			} finally {
				NvgContext.endFrame()
			}
		} finally {
			glBackup.restore()
		}
	}

	private fun logFailOnce(reason: String) {
		if (loggedFail) return
		loggedFail = true
		SpotifyOverlay.LOGGER.error("NVG PiP render failed: {}", reason)
	}

	data class NvgRenderState(
		private val width: Int,
		private val height: Int,
		private val poseMatrix: Matrix3x2fc,
		private val scissor: ScreenRectangle?,
		private val boundsRect: ScreenRectangle?,
		val callback: (Long) -> Unit,
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
		fun submit(graphics: GuiGraphicsExtractor, callback: (Long) -> Unit) {
			val window = Minecraft.getInstance().window
			if (window.isIconified || window.guiScaledWidth <= 0 || window.guiScaledHeight <= 0) return

			val pose = Matrix3x2f(graphics.pose())
			val screenRect = ScreenRectangle(0, 0, graphics.guiWidth(), graphics.guiHeight()).transformMaxBounds(pose)
			if (screenRect.width() <= 0 || screenRect.height() <= 0) return

			val scissor = graphics.scissorStack.peek()
			val bounds = scissor?.intersection(screenRect) ?: screenRect
			if (bounds.width() <= 0 || bounds.height() <= 0) return

			graphics.guiRenderState.addPicturesInPictureState(
				NvgRenderState(
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

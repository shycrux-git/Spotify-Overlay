package com.spotifyoverlay.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import io.github.humbleui.skija.BackendRenderTarget
import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.ColorSpace
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.FramebufferFormat
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.SurfaceOrigin
import io.github.humbleui.types.Rect
import org.lwjgl.opengl.GL11C
import org.lwjgl.opengl.GL13C
import org.lwjgl.opengl.GL14C
import org.lwjgl.opengl.GL15C
import org.lwjgl.opengl.GL20C
import org.lwjgl.opengl.GL30C
import org.lwjgl.opengl.GL31C
import org.lwjgl.system.MemoryStack
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12

/**
 * Dual-backend Skija (from cryleak/Odin).
 * OpenGL draws into the PiP texture.
 * Vulkan draws into a private TextureTarget and composites onto the main framebuffer
 * at GameRenderer TAIL (PiP color attachments are not safe to wrap mid-pass).
 */
internal interface SkijaBackend : AutoCloseable {
	fun supports(texture: GpuTexture): Boolean
	fun renderInto(destination: GpuTexture, dpr: Float, draw: (Canvas) -> Unit)
}

internal object SkijaBackends {
	private val colorSpace: ColorSpace = ColorSpace.getSRGB()
	private var active: SkijaBackend? = null

	fun isVulkan(): Boolean = RenderSystem.getDevice().backend is VulkanDevice

	fun ensure(texture: GpuTexture): SkijaBackend {
		val current = active
		if (current != null && current.supports(texture)) return current
		current?.close()
		val next = when (texture) {
			is VulkanGpuTexture -> VulkanSkijaBackend(colorSpace)
			is GlTexture -> OpenGlSkijaBackend(colorSpace)
			else -> error("Unsupported GpuTexture backend: ${texture.javaClass.name}")
		}
		active = next
		return next
	}

	fun renderVulkanOntoMain(guiWidth: Int, guiHeight: Int, draw: (Canvas) -> Unit) {
		val current = active
		val backend = when (current) {
			is VulkanSkijaBackend -> current
			else -> {
				current?.close()
				VulkanSkijaBackend(colorSpace).also { active = it }
			}
		}
		backend.renderOntoMain(guiWidth, guiHeight, draw)
	}

	fun shutdown() {
		active?.close()
		active = null
	}
}

private class VulkanSkijaBackend(
	private val colorSpace: ColorSpace,
) : SkijaBackend {
	private var directContext: DirectContext? = null
	private var drawSurface: Surface? = null
	private var drawRenderTarget: BackendRenderTarget? = null
	private var drawTexture: VulkanGpuTexture? = null
	private var compositeSurface: Surface? = null
	private var compositeRenderTarget: BackendRenderTarget? = null
	private var compositeTexture: VulkanGpuTexture? = null
	private var overlayTarget: TextureTarget? = null

	override fun supports(texture: GpuTexture): Boolean = texture is VulkanGpuTexture

	/** Unused for Vulkan PiP — see [renderOntoMain]. */
	override fun renderInto(destination: GpuTexture, dpr: Float, draw: (Canvas) -> Unit) {
		error("Vulkan Skija must composite via renderOntoMain, not PiP wrap")
	}

	fun renderOntoMain(guiWidth: Int, guiHeight: Int, draw: (Canvas) -> Unit) {
		val mainTarget = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget()
		val mainTex = mainTarget.getColorTexture() as? VulkanGpuTexture
			?: error("Main render target is not a VulkanGpuTexture")
		val width = mainTarget.width
		val height = mainTarget.height
		val dpr = (width.toFloat() / guiWidth.toFloat().coerceAtLeast(1f))
			.takeIf { it.isFinite() && it > 0f } ?: 1f

		// Finish any queued Minecraft Vulkan work before Skija takes the shared queue.
		RenderSystem.getDevice().createCommandEncoder().submit()

		val context = ensureContext()
		val overlay = ensureOverlayTarget(width, height)
		val overlayTex = overlay.getColorTexture() as? VulkanGpuTexture
			?: error("Vulkan overlay target has no color texture")

		ensureDrawSurface(context, overlayTex)
		val surface = drawSurface ?: error("Vulkan draw surface missing")
		val canvas = surface.canvas
		val save = canvas.save()
		try {
			canvas.clear(0x00000000)
			canvas.scale(dpr, dpr)
			draw(canvas)
		} finally {
			canvas.restoreToCount(save)
		}
		context.flushAndSubmit(surface, false)

		val snapshot = surface.makeImageSnapshot()
		try {
			RenderSystem.getDevice().createCommandEncoder().submit()
			val output = ensureCompositeSurface(context, mainTex)
			// Do not clear the main target — draw the transparent overlay snapshot over it.
			output.canvas.drawImageRect(
				snapshot,
				Rect.makeWH(width.toFloat(), height.toFloat()),
			)
			context.flushAndSubmit(output, false)
		} finally {
			snapshot.close()
		}
	}

	override fun close() {
		releaseDrawSurface()
		releaseCompositeSurface()
		destroyOverlayTarget()
		directContext?.close()
		directContext = null
	}

	private fun ensureContext(): DirectContext {
		directContext?.let { return it }
		val backend = RenderSystem.getDevice().backend
		check(backend is VulkanDevice) { "Vulkan Skija backend requires Minecraft Vulkan renderer" }

		val instance = backend.instance().vkInstance()
		val device = backend.vkDevice()
		val physicalDevice = device.physicalDevice
		val queue = backend.graphicsQueue().vkQueue()
		val functionProvider = VK.getFunctionProvider()

		val context = DirectContext.makeVulkan(
			instance.address(),
			physicalDevice.address(),
			device.address(),
			queue.address(),
			backend.graphicsQueue().queueFamilyIndex(),
			functionProvider.getFunctionAddress("vkGetInstanceProcAddr"),
			functionProvider.getFunctionAddress("vkGetDeviceProcAddr"),
			VK12.VK_API_VERSION_1_2,
		)
		directContext = context
		return context
	}

	private fun ensureOverlayTarget(width: Int, height: Int): TextureTarget {
		val existing = overlayTarget
		val existingTex = existing?.getColorTexture()
		if (
			existing != null &&
			existingTex is VulkanGpuTexture &&
			existing.width == width &&
			existing.height == height
		) {
			return existing
		}
		destroyOverlayTarget()
		releaseDrawSurface()
		val created = TextureTarget("spotify-overlay-skija", width, height, false, GpuFormat.RGBA8_UNORM)
		overlayTarget = created
		return created
	}

	private fun destroyOverlayTarget() {
		overlayTarget?.destroyBuffers()
		overlayTarget = null
	}

	private fun ensureDrawSurface(context: DirectContext, texture: VulkanGpuTexture) {
		val width = texture.getWidth(0)
		val height = texture.getHeight(0)
		val current = drawTexture
		if (
			drawSurface != null &&
			drawRenderTarget != null &&
			current != null &&
			current.vkImage() == texture.vkImage() &&
			drawSurface?.width == width &&
			drawSurface?.height == height
		) {
			return
		}
		releaseDrawSurface()
		val target = makeRenderTarget(texture)
		drawRenderTarget = target
		drawSurface = Surface.wrapBackendRenderTarget(
			context,
			target,
			SurfaceOrigin.BOTTOM_LEFT,
			ColorType.RGBA_8888,
			colorSpace,
		)
		drawTexture = texture
	}

	private fun ensureCompositeSurface(context: DirectContext, texture: VulkanGpuTexture): Surface {
		val width = texture.getWidth(0)
		val height = texture.getHeight(0)
		val current = compositeTexture
		if (
			compositeSurface != null &&
			compositeRenderTarget != null &&
			current != null &&
			current.vkImage() == texture.vkImage() &&
			compositeSurface?.width == width &&
			compositeSurface?.height == height
		) {
			return compositeSurface!!
		}
		releaseCompositeSurface()
		val target = makeRenderTarget(texture)
		compositeRenderTarget = target
		compositeSurface = Surface.wrapBackendRenderTarget(
			context,
			target,
			SurfaceOrigin.BOTTOM_LEFT,
			ColorType.RGBA_8888,
			colorSpace,
		)
		compositeTexture = texture
		return compositeSurface!!
	}

	private fun makeRenderTarget(texture: VulkanGpuTexture): BackendRenderTarget {
		val width = texture.getWidth(0)
		val height = texture.getHeight(0)
		return BackendRenderTarget.makeVulkan(
			width,
			height,
			texture.vkImage(),
			VK10.VK_IMAGE_TILING_OPTIMAL,
			VK10.VK_IMAGE_LAYOUT_GENERAL,
			VulkanConst.toVk(GpuFormat.RGBA8_UNORM),
			VulkanConst.textureUsageToVk(texture.usage(), texture.format),
			1,
			texture.mipLevels,
		)
	}

	private fun releaseDrawSurface() {
		drawSurface?.close()
		drawSurface = null
		drawRenderTarget?.close()
		drawRenderTarget = null
		drawTexture = null
	}

	private fun releaseCompositeSurface() {
		compositeSurface?.close()
		compositeSurface = null
		compositeRenderTarget?.close()
		compositeRenderTarget = null
		compositeTexture = null
	}
}

private class OpenGlSkijaBackend(
	private val colorSpace: ColorSpace,
) : SkijaBackend {
	private var directContext: DirectContext? = null
	private var surface: Surface? = null
	private var renderTarget: BackendRenderTarget? = null
	private var surfaceTextureId = 0
	private var framebufferId = 0

	override fun supports(texture: GpuTexture): Boolean = texture is GlTexture

	override fun renderInto(destination: GpuTexture, dpr: Float, draw: (Canvas) -> Unit) {
		check(destination is GlTexture)
		val state = OpenGlStateSnapshot.capture()
		try {
			val context = ensureContext()
			context.resetGLAll()
			ensureSurface(context, destination)
			val prepared = surface ?: error("Skija OpenGL surface missing")
			val canvas = prepared.canvas
			val save = canvas.save()
			try {
				canvas.clear(0x00000000)
				canvas.scale(dpr, dpr)
				draw(canvas)
			} finally {
				canvas.restoreToCount(save)
			}
			context.flushAndSubmit(prepared, false)
			context.resetGLAll()
		} finally {
			state.restore()
		}
	}

	override fun close() {
		surface?.close()
		surface = null
		renderTarget?.close()
		renderTarget = null
		if (framebufferId != 0) {
			GL30C.glDeleteFramebuffers(framebufferId)
			framebufferId = 0
		}
		surfaceTextureId = 0
		directContext?.close()
		directContext = null
	}

	private fun ensureContext(): DirectContext {
		directContext?.let { return it }
		val context = DirectContext.makeGL()
		directContext = context
		return context
	}

	private fun ensureSurface(context: DirectContext, texture: GlTexture) {
		val width = texture.getWidth(0)
		val height = texture.getHeight(0)
		if (
			surface != null &&
			renderTarget != null &&
			surfaceTextureId == texture.glId() &&
			surface?.width == width &&
			surface?.height == height
		) {
			return
		}

		surface?.close()
		renderTarget?.close()
		if (framebufferId != 0) {
			GL30C.glDeleteFramebuffers(framebufferId)
			framebufferId = 0
		}

		framebufferId = createFramebuffer(texture)
		surfaceTextureId = texture.glId()
		val target = BackendRenderTarget.makeGL(
			width,
			height,
			0,
			0,
			framebufferId,
			FramebufferFormat.GR_GL_RGBA8,
		)
		renderTarget = target
		surface = Surface.wrapBackendRenderTarget(
			context,
			target,
			SurfaceOrigin.BOTTOM_LEFT,
			ColorType.RGBA_8888,
			colorSpace,
		)
	}

	private fun createFramebuffer(texture: GlTexture): Int {
		val previousDraw = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)
		val previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING)
		val fbo = GL30C.glGenFramebuffers()
		GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, fbo)
		GL30C.glFramebufferTexture2D(
			GL30C.GL_FRAMEBUFFER,
			GL30C.GL_COLOR_ATTACHMENT0,
			GL11C.GL_TEXTURE_2D,
			texture.glId(),
			texture.fboMipLevel(),
		)
		GL11C.glDrawBuffer(GL30C.GL_COLOR_ATTACHMENT0)
		GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0)
		val status = GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)
		GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previousDraw)
		GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead)
		if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
			GL30C.glDeleteFramebuffers(fbo)
			error("Skija OpenGL FBO incomplete: 0x${status.toString(16)}")
		}
		return fbo
	}
}

private class OpenGlStateSnapshot(
	private val drawFramebuffer: Int,
	private val readFramebuffer: Int,
	private val viewport: IntArray,
	private val scissorBox: IntArray,
	private val currentProgram: Int,
	private val activeTexture: Int,
	private val texture2d: Int,
	private val vertexArray: Int,
	private val arrayBuffer: Int,
	private val elementArrayBuffer: Int,
	private val pixelPackBuffer: Int,
	private val pixelUnpackBuffer: Int,
	private val uniformBuffer: Int,
	private val blend: Boolean,
	private val depthTest: Boolean,
	private val cullFace: Boolean,
	private val scissorTest: Boolean,
	private val stencilTest: Boolean,
	private val multisample: Boolean,
	private val polygonOffsetFill: Boolean,
	private val depthMask: Boolean,
	private val colorMask: BooleanArray,
	private val blendSrcRgb: Int,
	private val blendDstRgb: Int,
	private val blendSrcAlpha: Int,
	private val blendDstAlpha: Int,
	private val blendEquationRgb: Int,
	private val blendEquationAlpha: Int,
) {
	fun restore() {
		setEnabled(GL11C.GL_BLEND, blend)
		setEnabled(GL11C.GL_DEPTH_TEST, depthTest)
		setEnabled(GL11C.GL_CULL_FACE, cullFace)
		setEnabled(GL11C.GL_SCISSOR_TEST, scissorTest)
		setEnabled(GL11C.GL_STENCIL_TEST, stencilTest)
		setEnabled(GL13C.GL_MULTISAMPLE, multisample)
		setEnabled(GL11C.GL_POLYGON_OFFSET_FILL, polygonOffsetFill)
		GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
		GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer)
		GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
		GL11C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
		GL11C.glDepthMask(depthMask)
		GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
		GL14C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha)
		GL20C.glBlendEquationSeparate(blendEquationRgb, blendEquationAlpha)
		GL20C.glUseProgram(currentProgram)
		GL13C.glActiveTexture(activeTexture)
		GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture2d)
		GL30C.glBindVertexArray(vertexArray)
		GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, arrayBuffer)
		GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer)
		GL15C.glBindBuffer(GL_PIXEL_PACK_BUFFER, pixelPackBuffer)
		GL15C.glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pixelUnpackBuffer)
		GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, uniformBuffer)
	}

	companion object {
		private const val GL_PIXEL_PACK_BUFFER = 35051
		private const val GL_PIXEL_UNPACK_BUFFER = 35052
		private const val GL_PIXEL_PACK_BUFFER_BINDING = 35053
		private const val GL_PIXEL_UNPACK_BUFFER_BINDING = 35055

		fun capture(): OpenGlStateSnapshot = MemoryStack.stackPush().use { stack ->
			val viewport = IntArray(4)
			val viewportBuf = stack.mallocInt(4)
			GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewportBuf)
			viewportBuf.get(viewport)

			val scissor = IntArray(4)
			val scissorBuf = stack.mallocInt(4)
			GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, scissorBuf)
			scissorBuf.get(scissor)

			val colorMask = BooleanArray(4)
			val colorMaskBuf = stack.malloc(4)
			GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, colorMaskBuf)
			for (i in colorMask.indices) {
				colorMask[i] = colorMaskBuf.get(i).toInt() != 0
			}

			OpenGlStateSnapshot(
				GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING),
				GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING),
				viewport,
				scissor,
				GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),
				GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE),
				GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D),
				GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),
				GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING),
				GL11C.glGetInteger(GL15C.GL_ELEMENT_ARRAY_BUFFER_BINDING),
				GL11C.glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING),
				GL11C.glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING),
				GL11C.glGetInteger(GL31C.GL_UNIFORM_BUFFER_BINDING),
				GL11C.glIsEnabled(GL11C.GL_BLEND),
				GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST),
				GL11C.glIsEnabled(GL11C.GL_CULL_FACE),
				GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST),
				GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST),
				GL11C.glIsEnabled(GL13C.GL_MULTISAMPLE),
				GL11C.glIsEnabled(GL11C.GL_POLYGON_OFFSET_FILL),
				GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK),
				colorMask,
				GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB),
				GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB),
				GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA),
				GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA),
				GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_RGB),
				GL11C.glGetInteger(GL20C.GL_BLEND_EQUATION_ALPHA),
			)
		}
	}

	private fun setEnabled(cap: Int, enabled: Boolean) {
		if (enabled) GL11C.glEnable(cap) else GL11C.glDisable(cap)
	}
}

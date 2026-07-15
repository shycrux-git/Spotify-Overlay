package com.spotifyoverlay.render

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vulkan.VulkanConst
import com.mojang.blaze3d.vulkan.VulkanDevice
import com.mojang.blaze3d.vulkan.VulkanGpuTexture
import io.github.humbleui.skija.BackendRenderTarget
import io.github.humbleui.skija.Bitmap
import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.ColorAlphaType
import io.github.humbleui.skija.ColorSpace
import io.github.humbleui.skija.ColorType
import io.github.humbleui.skija.DirectContext
import io.github.humbleui.skija.Image
import io.github.humbleui.skija.ImageInfo
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.SurfaceOrigin
import io.github.humbleui.types.Rect
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK12
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

internal interface SkijaBackend : AutoCloseable

internal object SkijaBackends {
	private val colorSpace: ColorSpace = ColorSpace.getSRGB()
	private var active: SkijaBackend? = null

	fun isVulkan(): Boolean = RenderSystem.getDevice().backend is VulkanDevice

	fun renderVulkanOntoMain(
		guiWidth: Int,
		guiHeight: Int,
		originX: Float,
		originY: Float,
		regionW: Float,
		regionH: Float,
		draw: (Canvas) -> Unit,
	) {
		val backend = ensureVulkan()
		backend.renderOntoMain(guiWidth, guiHeight, originX, originY, regionW, regionH, draw)
	}

	fun renderOpenGlAtlas(
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
		progressStrip: Rect?,
		freezeDynamic: Boolean,
		drawStatic: (Canvas) -> Unit,
		drawLyrics: (Canvas) -> Unit,
		drawProgress: (Canvas) -> Unit,
	): com.mojang.blaze3d.textures.GpuTextureView? {
		val backend = ensureOpenGl()
		return backend.renderAtlas(
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
		)
	}

	private fun ensureVulkan(): VulkanSkijaBackend {
		val current = active
		if (current is VulkanSkijaBackend) return current
		current?.close()
		return VulkanSkijaBackend(colorSpace).also { active = it }
	}

	private fun ensureOpenGl(): OpenGlSkijaBackend {
		val current = active
		if (current is OpenGlSkijaBackend) return current
		current?.close()
		return OpenGlSkijaBackend(colorSpace).also { active = it }
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

	fun renderOntoMain(
		guiWidth: Int,
		guiHeight: Int,
		originX: Float,
		originY: Float,
		regionW: Float,
		regionH: Float,
		draw: (Canvas) -> Unit,
	) {
		val mainTarget = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget()
		val mainTex = mainTarget.getColorTexture() as? VulkanGpuTexture
			?: error("Main render target is not a VulkanGpuTexture")
		val dpr = (mainTarget.width.toFloat() / guiWidth.toFloat().coerceAtLeast(1f))
			.takeIf { it.isFinite() && it > 0f } ?: 1f

		val texW = max(1, ceil(regionW * dpr).toInt())
		val texH = max(1, ceil(regionH * dpr).toInt())

		RenderSystem.getDevice().createCommandEncoder().submit()

		val context = ensureContext()
		val overlay = ensureOverlayTarget(texW, texH)
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
			val dstX = originX * dpr
			val dstY = originY * dpr
			output.canvas.drawImageRect(
				snapshot,
				Rect.makeXYWH(0f, 0f, texW.toFloat(), texH.toFloat()),
				Rect.makeXYWH(dstX, dstY, regionW * dpr, regionH * dpr),
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
	private var overlayTarget: TextureTarget? = null
	private var rasterSurface: Surface? = null
	private var stripSurface: Surface? = null
	private var lyricsSurface: Surface? = null
	private var uploadBitmap: Bitmap? = null
	private var stripBitmap: Bitmap? = null
	private var lyricsBitmap: Bitmap? = null
	private var staticSnapshot: Image? = null
	private var atlasW = 0
	private var atlasH = 0
	private var lastStripNanos = 0L
	private var lastLyricsNanos = 0L
	private var cachedView: com.mojang.blaze3d.textures.GpuTextureView? = null
	private var hasStatic = false

	fun renderAtlas(
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
		progressStrip: Rect?,
		freezeDynamic: Boolean,
		drawStatic: (Canvas) -> Unit,
		drawLyrics: (Canvas) -> Unit,
		drawProgress: (Canvas) -> Unit,
	): com.mojang.blaze3d.textures.GpuTextureView? {
		val mainTarget = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget()
		val dpr = (mainTarget.width.toFloat() / guiWidth.toFloat().coerceAtLeast(1f))
			.takeIf { it.isFinite() && it > 0f } ?: 1f

		val texW = max(1, ceil(regionW * dpr).toInt())
		val texH = max(1, ceil(regionH * dpr).toInt())
		val sizeChanged = texW != atlasW || texH != atlasH
		val now = System.nanoTime()
		val needStatic =
			sizeChanged ||
				!hasStatic ||
				cachedView == null ||
				staticUrgent ||
				(staticDirty && !hasStatic)

		atlasW = texW
		atlasH = texH
		val overlay = ensureOverlayTarget(texW, texH)
		val gpuTex = overlay.getColorTexture()
			?: error("OpenGL overlay target has no color texture")

		if (needStatic) {
			paintAndUploadFull(texW, texH, dpr, gpuTex, drawStatic)
			hasStatic = true
		}

		val lyricsRect = lyricsStrip
		val needLyrics = !freezeDynamic && lyricsRect != null && (lyricsDirty || needStatic)
		if (lyricsRect != null && needLyrics) {
			val due = needStatic || now - lastLyricsNanos >= MIN_LYRICS_INTERVAL_NS
			if (due) {
				paintAndUploadLayer(
					texW, texH, dpr, originX, originY, regionW, regionH,
					lyricsRect, gpuTex, drawLyrics, lyrics = true,
				)
				lastLyricsNanos = now
			}
		}

		val strip = progressStrip
		if (
			!freezeDynamic &&
			strip != null &&
			(needStatic || now - lastStripNanos >= MIN_STRIP_INTERVAL_NS)
		) {
			paintAndUploadLayer(
				texW, texH, dpr, originX, originY, regionW, regionH,
				strip, gpuTex, drawProgress, lyrics = false,
			)
			lastStripNanos = now
		}

		cachedView = overlay.getColorTextureView()
		return cachedView
	}

	private fun paintAndUploadFull(
		texW: Int,
		texH: Int,
		dpr: Float,
		gpuTex: com.mojang.blaze3d.textures.GpuTexture,
		drawStatic: (Canvas) -> Unit,
	) {
		val imageInfo = ImageInfo(texW, texH, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, colorSpace)
		val surface = ensureRasterSurface(imageInfo)
		val canvas = surface.canvas
		val save = canvas.save()
		try {
			canvas.clear(0x00000000)
			canvas.scale(dpr, dpr)
			drawStatic(canvas)
		} finally {
			canvas.restoreToCount(save)
		}

		staticSnapshot?.close()
		staticSnapshot = surface.makeImageSnapshot()

		val bitmap = ensureUploadBitmap(imageInfo)
		if (!surface.readPixels(bitmap, 0, 0)) {
			error("Failed to read Skija raster pixels")
		}
		uploadRegion(gpuTex, bitmap, 0, 0, texW, texH)
	}

	private fun paintAndUploadLayer(
		texW: Int,
		texH: Int,
		dpr: Float,
		originX: Float,
		originY: Float,
		regionW: Float,
		regionH: Float,
		strip: Rect,
		gpuTex: com.mojang.blaze3d.textures.GpuTexture,
		draw: (Canvas) -> Unit,
		lyrics: Boolean,
	) {
		val snapshot = staticSnapshot ?: return
		val localLeft = (strip.left - originX).coerceAtLeast(0f)
		val localTop = (strip.top - originY).coerceAtLeast(0f)
		val localRight = (strip.right - originX).coerceAtMost(regionW)
		val localBottom = (strip.bottom - originY).coerceAtMost(regionH)
		if (localRight - localLeft < 1f || localBottom - localTop < 1f) return

		val px = floor(localLeft * dpr).toInt().coerceIn(0, texW - 1)
		val py = floor(localTop * dpr).toInt().coerceIn(0, texH - 1)
		val pw = ceil(localRight * dpr).toInt().coerceAtMost(texW) - px
		val ph = ceil(localBottom * dpr).toInt().coerceAtMost(texH) - py
		if (pw < 1 || ph < 1) return

		val layerInfo = ImageInfo(pw, ph, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL, colorSpace)
		val surface = if (lyrics) ensureLyricsSurface(layerInfo) else ensureStripSurface(layerInfo)
		val canvas = surface.canvas
		val save = canvas.save()
		try {
			canvas.clear(0x00000000)
			canvas.drawImageRect(
				snapshot,
				Rect.makeXYWH(px.toFloat(), py.toFloat(), pw.toFloat(), ph.toFloat()),
				Rect.makeXYWH(0f, 0f, pw.toFloat(), ph.toFloat()),
			)
			canvas.scale(dpr, dpr)
			canvas.translate(-(originX + px / dpr), -(originY + py / dpr))
			draw(canvas)
		} finally {
			canvas.restoreToCount(save)
		}

		val bitmap = if (lyrics) ensureLyricsBitmap(layerInfo) else ensureStripBitmap(layerInfo)
		if (!surface.readPixels(bitmap, 0, 0)) {
			error("Failed to read Skija layer pixels")
		}
		uploadRegion(gpuTex, bitmap, px, py, pw, ph)
	}

	private fun uploadRegion(
		gpuTex: com.mojang.blaze3d.textures.GpuTexture,
		bitmap: Bitmap,
		destX: Int,
		destY: Int,
		width: Int,
		height: Int,
	) {
		val pixmap = bitmap.peekPixels()
		val pixelBuffer = if (pixmap != null) {
			val buf = pixmap.buffer
			buf.position(0)
			buf.limit(width * height * 4)
			buf
		} else {
			val pixels = bitmap.readPixels()
			val buf = ByteBuffer.allocateDirect(pixels.size).order(ByteOrder.nativeOrder())
			buf.put(pixels).flip()
			buf
		}
		RenderSystem.getDevice().createCommandEncoder()
			.writeToTexture(gpuTex, pixelBuffer, 0, 0, destX, destY, width, height)
	}

	override fun close() {
		destroyOverlayTarget()
		rasterSurface?.close()
		rasterSurface = null
		stripSurface?.close()
		stripSurface = null
		lyricsSurface?.close()
		lyricsSurface = null
		uploadBitmap?.close()
		uploadBitmap = null
		stripBitmap?.close()
		stripBitmap = null
		lyricsBitmap?.close()
		lyricsBitmap = null
		staticSnapshot?.close()
		staticSnapshot = null
		cachedView = null
		hasStatic = false
		atlasW = 0
		atlasH = 0
	}

	private fun ensureRasterSurface(info: ImageInfo): Surface {
		val existing = rasterSurface
		if (existing != null && existing.width == info.width && existing.height == info.height) {
			return existing
		}
		existing?.close()
		val next = Surface.makeRaster(info)
		rasterSurface = next
		return next
	}

	private fun ensureStripSurface(info: ImageInfo): Surface {
		val existing = stripSurface
		if (existing != null && existing.width == info.width && existing.height == info.height) {
			return existing
		}
		existing?.close()
		val next = Surface.makeRaster(info)
		stripSurface = next
		return next
	}

	private fun ensureLyricsSurface(info: ImageInfo): Surface {
		val existing = lyricsSurface
		if (existing != null && existing.width == info.width && existing.height == info.height) {
			return existing
		}
		existing?.close()
		val next = Surface.makeRaster(info)
		lyricsSurface = next
		return next
	}

	private fun ensureUploadBitmap(info: ImageInfo): Bitmap {
		val existing = uploadBitmap
		if (
			existing != null &&
			!existing.isNull &&
			existing.width == info.width &&
			existing.height == info.height
		) {
			return existing
		}
		existing?.close()
		val next = Bitmap()
		check(next.allocPixels(info)) { "Failed to alloc Skija upload bitmap" }
		uploadBitmap = next
		return next
	}

	private fun ensureStripBitmap(info: ImageInfo): Bitmap {
		val existing = stripBitmap
		if (
			existing != null &&
			!existing.isNull &&
			existing.width == info.width &&
			existing.height == info.height
		) {
			return existing
		}
		existing?.close()
		val next = Bitmap()
		check(next.allocPixels(info)) { "Failed to alloc Skija strip bitmap" }
		stripBitmap = next
		return next
	}

	private fun ensureLyricsBitmap(info: ImageInfo): Bitmap {
		val existing = lyricsBitmap
		if (
			existing != null &&
			!existing.isNull &&
			existing.width == info.width &&
			existing.height == info.height
		) {
			return existing
		}
		existing?.close()
		val next = Bitmap()
		check(next.allocPixels(info)) { "Failed to alloc Skija lyrics bitmap" }
		lyricsBitmap = next
		return next
	}

	private fun ensureOverlayTarget(width: Int, height: Int): TextureTarget {
		val existing = overlayTarget
		if (existing != null && existing.width == width && existing.height == height) {
			return existing
		}
		destroyOverlayTarget()
		val created = TextureTarget("spotify-overlay-skija", width, height, false, GpuFormat.RGBA8_UNORM)
		overlayTarget = created
		cachedView = null
		hasStatic = false
		staticSnapshot?.close()
		staticSnapshot = null
		return created
	}

	private fun destroyOverlayTarget() {
		overlayTarget?.destroyBuffers()
		overlayTarget = null
		cachedView = null
		hasStatic = false
	}

	companion object {
		private const val MIN_STRIP_INTERVAL_NS = 16_666_667L
		private const val MIN_LYRICS_INTERVAL_NS = 33_333_333L
	}
}


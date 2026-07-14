package com.spotifyoverlay.render

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL31
import org.lwjgl.opengl.GL33
import org.lwjgl.system.MemoryStack

/** Saves/restores GL state that NanoVG mutates so Minecraft world rendering stays correct. */
class GlStateBackup {
	private var program = 0
	private var activeTexture = 0
	private val textureBindings = IntArray(TEXTURE_UNITS)
	private val samplerBindings = IntArray(TEXTURE_UNITS)
	private var arrayBuffer = 0
	private var elementArrayBuffer = 0
	private var uniformBuffer = 0
	private var vertexArray = 0
	private var drawFramebuffer = 0
	private var readFramebuffer = 0
	private val viewport = IntArray(4)
	private val scissorBox = IntArray(4)
	private var blendSrc = 0
	private var blendDst = 0
	private var blendSrcAlpha = 0
	private var blendDstAlpha = 0
	private var blendEqRgb = 0
	private var blendEqAlpha = 0
	private var depthTest = false
	private var cullFace = false
	private var blend = false
	private var scissor = false
	private var stencil = false
	private var depthMask = false
	private val colorMask = BooleanArray(4)
	private var stencilMask = 0
	private var stencilFunc = 0
	private var stencilRef = 0
	private var stencilValueMask = 0
	private var stencilFail = 0
	private var stencilPassDepthFail = 0
	private var stencilPassDepthPass = 0
	private var cullFaceMode = 0
	private var frontFace = 0
	private var unpackAlignment = 0
	private var unpackRowLength = 0
	private var unpackSkipPixels = 0
	private var unpackSkipRows = 0

	fun save() {
		program = GL20.glGetInteger(GL20.GL_CURRENT_PROGRAM)
		activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
		for (i in 0 until TEXTURE_UNITS) {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + i)
			textureBindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
			samplerBindings[i] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING)
		}
		GL13.glActiveTexture(activeTexture)
		arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
		elementArrayBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING)
		uniformBuffer = GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_BINDING)
		vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
		drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
		readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
		GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport)
		GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox)
		blendSrc = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB)
		blendDst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB)
		blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA)
		blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)
		blendEqRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB)
		blendEqAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA)
		depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
		cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE)
		blend = GL11.glIsEnabled(GL11.GL_BLEND)
		scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
		stencil = GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
		depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
		MemoryStack.stackPush().use { stack ->
			val buf = stack.malloc(4)
			GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, buf)
			for (i in 0..3) colorMask[i] = buf.get(i) != 0.toByte()
		}
		stencilMask = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK)
		stencilFunc = GL11.glGetInteger(GL11.GL_STENCIL_FUNC)
		stencilRef = GL11.glGetInteger(GL11.GL_STENCIL_REF)
		stencilValueMask = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK)
		stencilFail = GL11.glGetInteger(GL11.GL_STENCIL_FAIL)
		stencilPassDepthFail = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL)
		stencilPassDepthPass = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS)
		cullFaceMode = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE)
		frontFace = GL11.glGetInteger(GL11.GL_FRONT_FACE)
		unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
		unpackRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH)
		unpackSkipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS)
		unpackSkipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS)
	}

	fun restore() {
		GL20.glUseProgram(program)
		for (i in 0 until TEXTURE_UNITS) {
			GL13.glActiveTexture(GL13.GL_TEXTURE0 + i)
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureBindings[i])
			GL33.glBindSampler(i, samplerBindings[i])
		}
		GL13.glActiveTexture(activeTexture)
		GL30.glBindVertexArray(vertexArray)
		GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer)
		GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementArrayBuffer)
		GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, uniformBuffer)
		GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer)
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer)
		GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
		GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3])
		GL20.glBlendEquationSeparate(blendEqRgb, blendEqAlpha)
		GL14.glBlendFuncSeparate(blendSrc, blendDst, blendSrcAlpha, blendDstAlpha)
		setEnabled(GL11.GL_DEPTH_TEST, depthTest)
		setEnabled(GL11.GL_CULL_FACE, cullFace)
		setEnabled(GL11.GL_BLEND, blend)
		setEnabled(GL11.GL_SCISSOR_TEST, scissor)
		setEnabled(GL11.GL_STENCIL_TEST, stencil)
		GL11.glDepthMask(depthMask)
		GL11.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3])
		GL11.glStencilMask(stencilMask)
		GL11.glStencilFunc(stencilFunc, stencilRef, stencilValueMask)
		GL11.glStencilOp(stencilFail, stencilPassDepthFail, stencilPassDepthPass)
		GL11.glCullFace(cullFaceMode)
		GL11.glFrontFace(frontFace)
		GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment)
		GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, unpackRowLength)
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, unpackSkipPixels)
		GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, unpackSkipRows)
	}

	private fun setEnabled(cap: Int, enabled: Boolean) {
		if (enabled) GL11.glEnable(cap) else GL11.glDisable(cap)
	}

	companion object {
		private const val TEXTURE_UNITS = 4
	}
}

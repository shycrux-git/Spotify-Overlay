package com.mojang.blaze3d.opengl;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import java.lang.reflect.Field;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Package-local bridge so the mod can resolve the PiP FBO for NanoVG.
 * {@link GlDevice} is package-private in Minecraft 26.2.
 */
public final class SpotifyOverlayGlAccess {
	private static final Field BACKEND_FIELD;

	static {
		try {
			BACKEND_FIELD = GpuDevice.class.getDeclaredField("backend");
			BACKEND_FIELD.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	private SpotifyOverlayGlAccess() {}

	@Nullable
	private static GlDevice device() {
		try {
			Object backend = BACKEND_FIELD.get(RenderSystem.getDevice());
			return backend instanceof GlDevice gl ? gl : null;
		} catch (IllegalAccessException e) {
			return null;
		}
	}

	@Nullable
	public static DirectStateAccess directStateAccess() {
		GlDevice device = device();
		return device == null ? null : device.directStateAccess();
	}

	@Nullable
	public static FrameBufferCache frameBufferCache() {
		GlDevice device = device();
		return device == null ? null : device.frameBufferCache();
	}

	public static int resolveFbo(GlTextureView color, GlTextureView depth) {
		GlDevice device = device();
		if (device == null) {
			return 0;
		}
		return device.frameBufferCache().getFbo(
			device.directStateAccess(),
			List.<FrameBufferAttachment>of(color),
			depth
		);
	}
}

package com.spotifyoverlay.mixin;

import com.spotifyoverlay.render.VulkanOverlayQueue;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vulkan Skija draws to a private target during HUD extract and is composited onto the
 * main framebuffer here — after GUI submission, matching Odin's GameRenderer TAIL hook.
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void spotifyOverlay$flushVulkanSkija(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		VulkanOverlayQueue.INSTANCE.flush();
	}
}

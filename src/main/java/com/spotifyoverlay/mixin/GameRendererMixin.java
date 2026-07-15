package com.spotifyoverlay.mixin;

import com.spotifyoverlay.render.OverlayCompositeQueue;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@Inject(method = "render", at = @At("TAIL"))
	private void spotifyOverlay$flushOverlaySkija(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
		OverlayCompositeQueue.INSTANCE.flush();
	}
}

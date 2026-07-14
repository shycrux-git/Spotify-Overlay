package com.spotifyoverlay.mixin;

import com.spotifyoverlay.client.OverlayInteraction;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.gui.components.events.ContainerEventHandler")
public interface ContainerEventHandlerMixin {
	@Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
	private void spotifyOverlay$mouseDragged(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof ChatScreen)) {
			return;
		}
		if (OverlayInteraction.INSTANCE.onMouseDragged(event)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
	private void spotifyOverlay$mouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
		if (!((Object) this instanceof ChatScreen)) {
			return;
		}
		if (OverlayInteraction.INSTANCE.onMouseReleased(event)) {
			cir.setReturnValue(true);
		}
	}
}

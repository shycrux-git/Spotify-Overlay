package com.spotifyoverlay.mixin;

import com.spotifyoverlay.client.OverlayInteraction;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChatScreen.class)
public class ChatScreenMixin {
	@Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
	private void spotifyOverlay$mouseClicked(MouseButtonEvent event, boolean doubled, CallbackInfoReturnable<Boolean> cir) {
		if (OverlayInteraction.INSTANCE.onMouseClicked(event)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
	private void spotifyOverlay$mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY, CallbackInfoReturnable<Boolean> cir) {
		if (OverlayInteraction.INSTANCE.onMouseScrolled(mouseX, mouseY, scrollY)) {
			cir.setReturnValue(true);
		}
	}
}

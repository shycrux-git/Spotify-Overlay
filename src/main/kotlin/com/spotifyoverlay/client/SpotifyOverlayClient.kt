package com.spotifyoverlay.client

import com.mojang.blaze3d.platform.InputConstants
import com.spotifyoverlay.SpotifyOverlay
import com.spotifyoverlay.config.ModConfig
import com.spotifyoverlay.config.MxmConfig
import com.spotifyoverlay.render.OverlayRenderer
import com.spotifyoverlay.spotify.SpotifyLyricsClient
import com.spotifyoverlay.spotify.WindowsMediaSessionClient
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.KeyMapping
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

object SpotifyOverlayClient : ClientModInitializer {
	private lateinit var toggleKey: KeyMapping
	private lateinit var lyricsKey: KeyMapping

	override fun onInitializeClient() {
		ModConfig.load()
		MxmConfig.load()
		SpotifyLyricsClient.warmUp()
		WindowsMediaSessionClient.start()

		HudElementRegistry.addLast(SpotifyOverlay.id("overlay")) { graphics, _ -> OverlayRenderer.extract(graphics) }

		ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
			OverlayRenderer.syncOnWorldJoin()
		}

		val category = KeyMapping.Category.register(SpotifyOverlay.id("spotify"))
		toggleKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.spotify-overlay.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category))
		lyricsKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.spotify-overlay.toggle_lyrics", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, category))

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			while (toggleKey.consumeClick()) {
				ModConfig.update { overlayEnabled = !overlayEnabled }
				val enabled = ModConfig.get().overlayEnabled
				client.player?.sendOverlayMessage(
					Component.literal(if (enabled) "Spotify overlay shown" else "Spotify overlay hidden"),
				)
			}
			while (lyricsKey.consumeClick()) { ModConfig.update { showLyrics = !showLyrics } }
		}

		ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
			val root = ClientCommands.literal("spotify")
				.executes { ctx ->
					val track = WindowsMediaSessionClient.currentTrack()
					ctx.source.sendFeedback(
						Component.literal(
							if (track != null) {
								"Now playing — ${track.title} — ${track.artists}"
							} else {
								"Nothing playing"
							}
						)
					)
					1
				}
			dispatcher.register(root)
		}
	}
}

package com.spotifyoverlay.config

import com.spotifyoverlay.SpotifyOverlay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ModConfig(
	var mxmGuid: String = "",
	var mxmToken: String = "",
	var overlayEnabled: Boolean = true,
	var showLyrics: Boolean = true,
	/** Negative values anchor from the right / bottom edge. */
	var overlayX: Float = 14f,
	var overlayY: Float = -16f,
	var overlayScale: Float = 1f,
) {
	companion object {
		private val json = Json {
			prettyPrint = true
			ignoreUnknownKeys = true
			encodeDefaults = true
		}

		private val path: Path =
			FabricLoader.getInstance().configDir.resolve("spotify-overlay.json")

		@Volatile
		private var instance: ModConfig = ModConfig()

		fun get(): ModConfig = instance

		fun load(): ModConfig {
			try {
				if (Files.exists(path)) {
					instance = json.decodeFromString(serializer(), Files.readString(path))
				} else {
					instance = ModConfig()
					save()
				}
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.error("Failed to load config, using defaults", e)
				instance = ModConfig()
			}
			return instance
		}

		fun save() {
			try {
				Files.createDirectories(path.parent)
				Files.writeString(path, json.encodeToString(serializer(), instance))
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.error("Failed to save config", e)
			}
		}

		fun update(block: ModConfig.() -> Unit) {
			synchronized(this) {
				instance.block()
				save()
			}
		}
	}
}

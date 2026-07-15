package com.spotifyoverlay.config

import com.spotifyoverlay.SpotifyOverlay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ModConfig(
	var overlayEnabled: Boolean = true,
	var showLyrics: Boolean = true,
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

		private val dir: Path =
			FabricLoader.getInstance().configDir.resolve("SpotifyOverlay")

		private val path: Path = dir.resolve("config.json")
		private val legacyPath: Path = FabricLoader.getInstance().configDir.resolve("spotify-overlay.json")

		@Volatile
		private var instance: ModConfig = ModConfig()

		fun get(): ModConfig = instance

		fun load(): ModConfig {
			try {
				Files.createDirectories(dir)
				when {
					Files.exists(path) -> {
						instance = json.decodeFromString(serializer(), Files.readString(path))
					}
					Files.exists(legacyPath) -> {
						MxmConfig.load()
						instance = migrateFromLegacy(Files.readString(legacyPath))
						save()
					}
					else -> {
						instance = ModConfig()
						save()
					}
				}
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.error("Failed to load config, using defaults", e)
				instance = ModConfig()
			}
			return instance
		}

		fun save() {
			try {
				Files.createDirectories(dir)
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

		private fun migrateFromLegacy(raw: String): ModConfig {
			val legacy = json.decodeFromString(LegacyConfig.serializer(), raw)
			MxmConfig.update {
				if (legacy.mxmGuid.isNotBlank()) guid = legacy.mxmGuid
				if (legacy.mxmToken.isNotBlank()) token = legacy.mxmToken
			}
			return ModConfig(
				overlayEnabled = legacy.overlayEnabled,
				showLyrics = legacy.showLyrics,
				overlayX = legacy.overlayX,
				overlayY = legacy.overlayY,
				overlayScale = legacy.overlayScale,
			)
		}
	}
}

@Serializable
private data class LegacyConfig(
	var mxmGuid: String = "",
	var mxmToken: String = "",
	var overlayEnabled: Boolean = true,
	var showLyrics: Boolean = true,
	var overlayX: Float = 14f,
	var overlayY: Float = -16f,
	var overlayScale: Float = 1f,
)

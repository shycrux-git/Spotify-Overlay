package com.spotifyoverlay.config

import com.spotifyoverlay.SpotifyOverlay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class MxmConfig(
	var guid: String = "",
	var token: String = "",
) {
	companion object {
		private val json = Json {
			prettyPrint = true
			ignoreUnknownKeys = true
			encodeDefaults = true
		}

		private val path: Path =
			FabricLoader.getInstance().configDir.resolve("SpotifyOverlay").resolve("mxm.json")

		@Volatile
		private var instance: MxmConfig = MxmConfig()

		fun get(): MxmConfig = instance

		fun load(): MxmConfig {
			try {
				Files.createDirectories(path.parent)
				if (Files.exists(path)) {
					instance = json.decodeFromString(serializer(), Files.readString(path))
				} else {
					instance = MxmConfig()
					save()
				}
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.error("Failed to load mxm config, using defaults", e)
				instance = MxmConfig()
			}
			return instance
		}

		fun save() {
			try {
				Files.createDirectories(path.parent)
				Files.writeString(path, json.encodeToString(serializer(), instance))
			} catch (e: Exception) {
				SpotifyOverlay.LOGGER.error("Failed to save mxm config", e)
			}
		}

		fun update(block: MxmConfig.() -> Unit) {
			synchronized(this) {
				instance.block()
				save()
			}
		}
	}
}

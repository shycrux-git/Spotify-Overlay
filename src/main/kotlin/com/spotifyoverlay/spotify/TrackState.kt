package com.spotifyoverlay.spotify

data class TrackState(
	val id: String,
	val title: String,
	val artists: String,
	val album: String?,
	val durationMs: Long,
	val progressMs: Long,
	val isPlaying: Boolean,
	val fetchedAt: Long,
) {
	fun interpolatedProgressMs(now: Long = System.currentTimeMillis()): Long {
		if (!isPlaying) return progressMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
		val elapsed = (now - fetchedAt).coerceAtLeast(0L)
		return (progressMs + elapsed).coerceIn(0L, durationMs.coerceAtLeast(progressMs))
	}

	companion object {
		const val SEEK_THRESHOLD_MS = 8000L

		fun withSmoothedProgress(
			previous: TrackState?,
			id: String,
			title: String,
			artists: String,
			album: String?,
			durationMs: Long,
			rawProgressMs: Long,
			isPlaying: Boolean,
			positionValidAtMs: Long? = null,
			now: Long = System.currentTimeMillis(),
		): TrackState {
			val raw = rawProgressMs.coerceAtLeast(0L)
			val duration = durationMs.coerceAtLeast(0L)
			val sampleAt = (positionValidAtMs ?: now).coerceAtMost(now)

			if (previous == null || previous.id != id) {
				return TrackState(id, title, artists, album, duration, raw, isPlaying, sampleAt)
			}

			val projected = previous.interpolatedProgressMs(now)
			val meta = previous.copy(
				title = title,
				artists = artists,
				album = album,
				durationMs = duration,
			)

			if (!isPlaying) {
				return meta.copy(progressMs = projected, isPlaying = false, fetchedAt = now)
			}

			if (!previous.isPlaying) {
				val resumeAt = maxOf(raw, previous.progressMs)
				return TrackState(id, title, artists, album, duration, resumeAt, true, sampleAt)
			}

			val versusAnchor = raw - previous.progressMs
			return when {
				versusAnchor <= -SEEK_THRESHOLD_MS ->
					TrackState(id, title, artists, album, duration, raw, true, sampleAt)

				versusAnchor <= 120L -> meta.copy(isPlaying = true)

				else -> {
					val liveFromSample = (raw + (now - sampleAt).coerceAtLeast(0L))
						.coerceIn(0L, duration.coerceAtLeast(raw))
					if (liveFromSample + 250L < projected) {
						meta.copy(isPlaying = true)
					} else {
						TrackState(id, title, artists, album, duration, raw, true, sampleAt)
					}
				}
			}
		}
	}
}

data class LyricLine(
	val startTimeMs: Long,
	val words: String,
)

data class LyricsState(
	val trackId: String,
	val lines: List<LyricLine>,
	val status: String? = null,
) {
	companion object {
		fun unavailable(trackId: String, status: String) =
			LyricsState(trackId = trackId, lines = emptyList(), status = status)
	}
}

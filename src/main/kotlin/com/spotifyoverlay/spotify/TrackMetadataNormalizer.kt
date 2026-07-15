package com.spotifyoverlay.spotify

object TrackMetadataNormalizer {
	private val artistSplit =
		Regex("""\s*(?:,|;|/|&|\band\b|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s+""", RegexOption.IGNORE_CASE)

	private val featInTitle =
		Regex(
			"""\s*[\(\[\-–—]\s*(?:feat\.?|ft\.?|featuring|with)\b.*$""",
			RegexOption.IGNORE_CASE,
		)

	fun splitArtists(raw: String): List<String> {
		val cleaned = raw.trim()
		if (cleaned.isEmpty()) return emptyList()
		val parts = artistSplit.split(cleaned)
			.map { it.trim().trim(',', '.', ';') }
			.filter { it.isNotBlank() }
			.distinctBy { it.lowercase() }
		return parts.ifEmpty { listOf(cleaned) }
	}

	fun primaryArtist(raw: String): String =
		splitArtists(raw).firstOrNull() ?: raw.trim()

	fun cleanTitle(title: String): String =
		featInTitle.replace(title.trim(), "").trim().ifBlank { title.trim() }

	fun artistQueryCandidates(raw: String): List<String> {
		val parts = splitArtists(raw)
		val out = LinkedHashSet<String>()
		parts.firstOrNull()?.let { out.add(it) }
		parts.forEach { out.add(it) }
		if (raw.isNotBlank()) out.add(raw.trim())
		return out.toList()
	}

	fun normalizeForCompare(value: String): String {
		val sb = StringBuilder(value.length)
		var prevSpace = false
		for (ch in value.lowercase()) {
			when (ch) {
				'\'', '!', '?', ',', '.', '(', ')', '[', ']', '-', ':', ';', '"', '’' -> Unit
				'\t', '\n', '\r', ' ' -> {
					if (!prevSpace && sb.isNotEmpty()) {
						sb.append(' ')
						prevSpace = true
					}
				}
				else -> {
					sb.append(ch)
					prevSpace = false
				}
			}
		}
		return sb.toString().trim()
	}

	fun artistsMatch(ours: String, theirs: String): Boolean {
		if (theirs.isBlank()) return true
		val ourParts = splitArtists(ours).map { normalizeForCompare(it) }.filter { it.isNotEmpty() }
		val theirNorm = normalizeForCompare(theirs)
		if (ourParts.isEmpty() || theirNorm.isEmpty()) return true
		val theirsParts = splitArtists(theirs).map { normalizeForCompare(it) }.filter { it.isNotEmpty() }
		return ourParts.any { oursPart ->
			theirNorm.contains(oursPart) ||
				oursPart.contains(theirNorm) ||
				theirsParts.any { theirsPart ->
					theirsPart.contains(oursPart) || oursPart.contains(theirsPart)
				}
		}
	}

	fun titlesMatch(ours: String, theirs: String): Boolean {
		if (theirs.isBlank()) return true
		val a = normalizeForCompare(cleanTitle(ours))
		val b = normalizeForCompare(cleanTitle(theirs))
		if (a.isEmpty() || b.isEmpty()) return true
		return a.contains(b) || b.contains(a)
	}
}

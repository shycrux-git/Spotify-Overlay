package com.spotifyoverlay.render

import com.spotifyoverlay.SpotifyOverlay
import com.spotifyoverlay.client.OverlayInteraction
import com.spotifyoverlay.config.ModConfig
import com.spotifyoverlay.spotify.AlbumArtCache
import com.spotifyoverlay.spotify.LyricLine
import com.spotifyoverlay.spotify.LyricsState
import com.spotifyoverlay.spotify.SpotifyLyricsClient
import com.spotifyoverlay.spotify.TrackMetadataEnricher
import com.spotifyoverlay.spotify.TrackState
import com.spotifyoverlay.spotify.WindowsMediaSessionClient
import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.FontMgr
import io.github.humbleui.skija.FontStyle
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.PaintMode
import io.github.humbleui.skija.Shader
import io.github.humbleui.skija.paragraph.FontCollection
import io.github.humbleui.skija.paragraph.Paragraph
import io.github.humbleui.skija.paragraph.ParagraphBuilder
import io.github.humbleui.skija.paragraph.ParagraphStyle
import io.github.humbleui.skija.paragraph.TextStyle
import io.github.humbleui.types.RRect
import io.github.humbleui.types.Rect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import kotlin.math.exp

object OverlayRenderer {
	private const val EDGE_SLIDE_SPEED = 3.2f
	private const val CENTER_SLIDE_SPEED = 8.5f
	private const val LYRICS_ANIM_DURATION = 0.4f
	private const val LYRICS_SLIDE_FRACTION = 0.28f
	private const val PAUSE_HIDE_AFTER_MS = 2_000L
	private const val TRACK_GAP_GRACE_MS = 3_000L
	private const val END_OF_TRACK_PAUSE_IGNORE_MS = 5_000L
	private const val FETCH_DOT_INTERVAL = 0.5f

	private var lastFrameNanos = System.nanoTime()
	private var lyricScrollY = 0f
	private var lyricScrollTarget = 0f
	private var activeLyricIdx = -1
	private var lastTrackId: String? = null
	private var fadeNew = 1f
	private var lastEmittedProgressMs = 0
	private var lastEmittedTrackId: String? = null

	private var slideProgress = 0f
	private var wantVisible = false
	private var slideOffX = 0f
	private var slideOffY = 0f
	private var slideSpeed = 5.5f
	private var frozenTrack: TrackState? = null
	private var lastLiveSeenMs = 0L
	private var pausedSinceMs: Long? = null
	private var pausedTrackId: String? = null

	private var lyricsFade = 0f
	private var lastLyrics: LyricsState? = null
	private var fetchDotCount = 1
	private var fetchDotTimer = 0f
	@Volatile
	private var publishedLyricsExpand = 0f

	private var lastOpenGlContentKey = Long.MIN_VALUE
	private var lastOpenGlLyricsKey = Long.MIN_VALUE

	private var lyricCacheTrackId: String? = null
	private var lyricCacheWidth = -1f
	private var lyricCacheScale = -1f
	private var lyricCacheActiveIdx = Int.MIN_VALUE
	private var lyricInactiveHeights = FloatArray(0)
	private var lyricHeights = FloatArray(0)
	private var lyricTops = FloatArray(0)

	private val fillPaint = Paint().setAntiAlias(true).setMode(PaintMode.FILL)
	private val strokePaint = Paint().setAntiAlias(true).setMode(PaintMode.STROKE)

	private val paragraphCache = object : LinkedHashMap<Long, Paragraph>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Paragraph>?): Boolean {
			if (size <= 64) return false
			eldest?.value?.close()
			return true
		}
	}
	private val widthCache = HashMap<Long, Float>(64)

	private val fontCollection = FontCollection().setDefaultFontManager(FontMgr.getDefault())
	private val fontFamilies = arrayOf(
		"Segoe UI",
		"Yu Gothic UI",
		"Yu Gothic",
		"Microsoft YaHei",
		"Malgun Gothic",
		"sans-serif",
	)


	fun isInteractable(): Boolean = slideProgress > 0.2f && isOverlayAllowedWithScreen(Minecraft.getInstance().gui.screen())

	fun lyricsPanelExpand(): Float = publishedLyricsExpand

	fun syncOnWorldJoin() {
		lastFrameNanos = System.nanoTime()
		val track = WindowsMediaSessionClient.currentTrack()
		val nowMs = System.currentTimeMillis()
		if (track != null && track.isPlaying) {
			frozenTrack = track
			lastLiveSeenMs = nowMs
			pausedSinceMs = null
			pausedTrackId = null
			return
		}
		frozenTrack = null
		lastLiveSeenMs = 0L
		if (track != null) {
			pausedSinceMs = nowMs - PAUSE_HIDE_AFTER_MS
			pausedTrackId = track.id
		} else {
			pausedSinceMs = null
			pausedTrackId = null
		}
		wantVisible = false
		slideProgress = 0f
		lyricsFade = 0f
		publishedLyricsExpand = 0f
		lastLyrics = null
		lastTrackId = null
		lastOpenGlContentKey = Long.MIN_VALUE
		lastOpenGlLyricsKey = Long.MIN_VALUE
		clearParagraphCache()
	}

	private fun isOverlayAllowedWithScreen(screen: Screen?): Boolean {
		if (screen == null) return true
		if (screen is AbstractContainerScreen<*>) return true
		if (screen is ChatScreen) return true
		return false
	}

	fun extract(graphics: GuiGraphicsExtractor) {
		val cfg = ModConfig.get()
		if (!cfg.overlayEnabled) return

		val sw = graphics.guiWidth().toFloat()
		val sh = graphics.guiHeight().toFloat()
		val region = prepareFrame(sw, sh, cfg) ?: return

		val ox = region.submitX
		val oy = region.submitY

		if (SkijaBackends.isVulkan()) {
			AlbumArtCache.flushPending()
			val draw: (Canvas) -> Unit = { canvas ->
				canvas.translate(-ox, -oy)
				paintFrame(canvas, sw, sh, cfg, region, PaintParts.ALL)
			}
			OverlayCompositeQueue.submitVulkan(sw.toInt(), sh.toInt(), ox, oy, region.width, region.height, draw)
		} else {
			val drawStatic: (Canvas) -> Unit = { canvas ->
				canvas.translate(-ox, -oy)
				paintFrame(canvas, sw, sh, cfg, region, PaintParts.STATIC)
			}
			val drawLyrics: (Canvas) -> Unit = { canvas ->
				paintFrame(canvas, sw, sh, cfg, region, PaintParts.LYRICS)
			}
			val drawProgress: (Canvas) -> Unit = { canvas ->
				paintFrame(canvas, sw, sh, cfg, region, PaintParts.PROGRESS)
			}
			OverlayCompositeQueue.blitOpenGl(
				graphics,
				sw.toInt(),
				sh.toInt(),
				ox,
				oy,
				region.width,
				region.height,
				region.openGlStaticDirty,
				region.openGlStaticUrgent,
				region.lyricsStrip,
				region.openGlLyricsDirty && !OverlayInteraction.isDragging(),
				region.progressStrip,
				OverlayInteraction.isDragging(),
				drawStatic,
				drawLyrics,
				drawProgress,
			)
			if (region.openGlLyricsDirty &&
				kotlin.math.abs(lyricScrollY - lyricScrollTarget) < 0.35f
			) {
				lastOpenGlLyricsKey = region.lyricsKey
			}
		}
	}

	private enum class PaintParts {
		ALL,
		STATIC,
		LYRICS,
		PROGRESS,
	}

	private data class FrameRegion(
		val submitX: Float,
		val submitY: Float,
		val width: Float,
		val height: Float,
		val track: TrackState,
		val liveTrack: TrackState?,
		val progressMs: Int,
		val lyricsAnim: Float,
		val slideT: Float,
		val alpha: Float,
		val layout: OverlayLayout.Bounds,
		val px: Float,
		val py: Float,
		val s: Float,
		val lyricsState: LyricsState?,
		val hasLyrics: Boolean,
		val isFetchingLyrics: Boolean,
		val wantLyricsPanel: Boolean,
		val progressStrip: Rect,
		val lyricsStrip: Rect?,
		val openGlStaticDirty: Boolean,
		val openGlStaticUrgent: Boolean,
		val openGlLyricsDirty: Boolean,
		val lyricsKey: Long,
	)

	private fun prepareFrame(sw: Float, sh: Float, cfg: ModConfig): FrameRegion? {
		val nowNanos = System.nanoTime()
		var dt = (nowNanos - lastFrameNanos) / 1_000_000_000f
		lastFrameNanos = nowNanos
		if (dt > 0.1f) dt = 0.1f

		val liveTrack = WindowsMediaSessionClient.currentTrack()
		val nowMs = System.currentTimeMillis()
		if (liveTrack != null) {
			frozenTrack = liveTrack
			lastLiveSeenMs = nowMs
		}

		when {
			liveTrack == null -> {
				pausedSinceMs = null
				pausedTrackId = null
			}
			liveTrack.isPlaying || isNearNaturalEnd(liveTrack) -> {
				pausedSinceMs = null
				pausedTrackId = null
			}
			pausedTrackId != liveTrack.id || pausedSinceMs == null -> {
				pausedSinceMs = nowMs
				pausedTrackId = liveTrack.id
			}
		}
		val pausedTooLong = pausedSinceMs?.let { nowMs - it >= PAUSE_HIDE_AFTER_MS } == true
		val screenAllowsOverlay = isOverlayAllowedWithScreen(Minecraft.getInstance().gui.screen())
		val inTrackGapGrace = liveTrack == null &&
			frozenTrack != null &&
			(nowMs - lastLiveSeenMs) < TRACK_GAP_GRACE_MS
		val mediaPresent =
			(liveTrack != null || inTrackGapGrace) && !pausedTooLong && screenAllowsOverlay

		val trackKey = liveTrack?.id ?: frozenTrack?.id
		val lyricsState = SpotifyLyricsClient.currentLyrics()
			?.takeIf { trackKey != null && it.trackId == trackKey }
		val hasLyrics = lyricsState?.lines?.isNotEmpty() == true
		val isFetchingLyrics = when {
			!cfg.showLyrics || !mediaPresent -> false
			hasLyrics -> false
			lyricsState == null -> true
			lyricsState.status?.startsWith("Fetching", ignoreCase = true) == true -> true
			else -> false
		}
		if (hasLyrics) {
			lastLyrics = lyricsState
		}

		val wantLyricsPanel = cfg.showLyrics && mediaPresent && (hasLyrics || isFetchingLyrics)
		val openGl = !SkijaBackends.isVulkan()
		if (openGl) {
			lyricsFade = if (wantLyricsPanel) 1f else 0f
			if (!wantLyricsPanel) lastLyrics = null
		} else if (wantLyricsPanel) {
			lyricsFade = (lyricsFade + dt / LYRICS_ANIM_DURATION).coerceAtMost(1f)
		} else {
			lyricsFade = (lyricsFade - dt / LYRICS_ANIM_DURATION).coerceAtLeast(0f)
			if (lyricsFade < 0.002f) {
				lastLyrics = null
			}
		}
		if (isFetchingLyrics) {
			fetchDotTimer += dt
			while (fetchDotTimer >= FETCH_DOT_INTERVAL) {
				fetchDotTimer -= FETCH_DOT_INTERVAL
				fetchDotCount = fetchDotCount % 3 + 1
			}
		} else {
			fetchDotCount = 1
			fetchDotTimer = 0f
		}
		val lyricsAnim = if (openGl) lyricsFade else easeInOutCubic(lyricsFade)
		publishedLyricsExpand = lyricsAnim

		val layout = OverlayLayout.bounds(sw, sh, cfg, lyricsAnim)
		val s = cfg.overlayScale.coerceIn(OverlayLayout.MIN_SCALE, OverlayLayout.MAX_SCALE)

		if (mediaPresent != wantVisible) {
			wantVisible = mediaPresent
			if (!openGl) {
				val slide = slideFromNearestEdge(layout, sw, sh)
				slideOffX = slide.offX
				slideOffY = slide.offY
				slideSpeed = slide.speed
			} else {
				slideOffX = 0f
				slideOffY = 0f
			}
		}

		if (openGl) {
			slideProgress = if (wantVisible) 1f else 0f
		} else {
			slideProgress = expLerp(slideProgress, if (wantVisible) 1f else 0f, dt, slideSpeed)
		}
		if (slideProgress < 0.002f && !wantVisible) {
			frozenTrack = null
			lastTrackId = null
			lastLyrics = null
			lyricsFade = 0f
			publishedLyricsExpand = 0f
			lastOpenGlContentKey = Long.MIN_VALUE
			lastOpenGlLyricsKey = Long.MIN_VALUE
			clearParagraphCache()
			return null
		}

		val track = liveTrack ?: frozenTrack ?: return null
		val slideT = if (openGl) 1f else easeOutCubic(slideProgress.coerceIn(0f, 1f))
		val px = layout.x + slideOffX * (1f - slideT)
		val py = layout.y + slideOffY * (1f - slideT)

		val trackId = track.id
		if (trackId != lastTrackId) {
			lastTrackId = trackId
			activeLyricIdx = -1
			lyricScrollY = 0f
			lyricScrollTarget = 0f
			fadeNew = 0f
			lastEmittedProgressMs = 0
			lastEmittedTrackId = null
			clearParagraphCache()
		}
		if (openGl) {
			fadeNew = 1f
		} else {
			fadeNew = expLerp(fadeNew, 1f, dt, 10f)
		}
		val alpha = if (openGl) 1f else fadeNew * slideT

		val progressMs = if (liveTrack != null) {
			smoothProgress(liveTrack)
		} else {
			lastEmittedProgressMs
		}

		val pad = 14f * s
		val mediaH = OverlayLayout.MEDIA_H * s
		val artSz = (mediaH - 24f * s).coerceAtLeast(28f * s)
		val infoX = px + pad + artSz + pad
		val infoW = (px + layout.w - pad - infoX).coerceAtLeast(80f * s)
		val progBarY = py + mediaH - 22f * s
		val stripPad = 4f * s
		val progressStrip = Rect.makeLTRB(
			infoX - stripPad,
			progBarY - 20f * s - stripPad,
			infoX + infoW + stripPad,
			progBarY + 8f * s + stripPad,
		)

		val displayLyricsForScroll = when {
			hasLyrics -> lyricsState
			else -> lastLyrics?.takeIf { it.trackId == track.id && it.lines.isNotEmpty() }
		}
		if (lyricsAnim > 0.002f && displayLyricsForScroll != null && !isFetchingLyrics) {
			val lyrW = layout.w - 2f * pad
			val lyricsPanelH = OverlayLayout.LYRICS_H * s * lyricsAnim
			val lyrH = (lyricsPanelH - 8f * s - pad).coerceAtLeast(0f)
			tickLyricScroll(progressMs, displayLyricsForScroll, lyrW, lyrH, s, dt)
		}

		val shadow = 10f * s
		val submitX = (px - shadow).coerceAtLeast(0f)
		val submitY = (py - shadow).coerceAtLeast(0f)
		val submitW = (layout.w + shadow * 2f).coerceAtMost(sw - submitX)
		val submitH = (layout.h + shadow * 2f).coerceAtMost(sh - submitY)

		val artKey = AlbumArtCache.currentImage()?.hashCode() ?: 0
		val artistsKey = TrackMetadataEnricher.displayArtists(track.id, track.artists).hashCode()
		// Only edge-flush changes require a chrome rebuild; plain moves just change the blit origin.
		val edgeEps = (2f * s).coerceAtLeast(2f)
		val touchLeft = px <= edgeEps
		val touchRight = px + layout.w >= sw - edgeEps
		val contentKey = packStaticKey(
			track.id.hashCode(),
			track.title.hashCode(),
			artistsKey,
			artKey,
			(s * 100f).toInt(),
			submitW.toBits(),
			submitH.toBits(),
			if (wantLyricsPanel) 1 else 0,
			if (touchLeft) 1 else 0,
			if (touchRight) 1 else 0,
		)
		val openGlStaticUrgent = contentKey != lastOpenGlContentKey
		if (openGlStaticUrgent) {
			lastOpenGlContentKey = contentKey
		}
		val openGlStaticDirty = openGlStaticUrgent

		val sepY = py + mediaH
		val lyricsStrip = if (lyricsAnim > 0.002f) {
			Rect.makeLTRB(px, sepY, px + layout.w, py + layout.h)
		} else {
			null
		}
		val lyricsKey = packStaticKey(
			activeLyricIdx,
			lyricScrollY.toInt(),
			fetchDotCount,
			if (isFetchingLyrics) 1 else 0,
			displayLyricsForScroll?.lines?.size ?: 0,
			track.id.hashCode(),
		)
		val scrollMoving = kotlin.math.abs(lyricScrollY - lyricScrollTarget) >= 0.35f
		val openGlLyricsDirty = lyricsStrip != null &&
			(scrollMoving || lyricsKey != lastOpenGlLyricsKey)

		return FrameRegion(
			submitX = submitX,
			submitY = submitY,
			width = submitW.coerceAtLeast(1f),
			height = submitH.coerceAtLeast(1f),
			track = track,
			liveTrack = liveTrack,
			progressMs = progressMs,
			lyricsAnim = lyricsAnim,
			slideT = slideT,
			alpha = alpha,
			layout = layout,
			px = px,
			py = py,
			s = s,
			lyricsState = lyricsState,
			hasLyrics = hasLyrics,
			isFetchingLyrics = isFetchingLyrics,
			wantLyricsPanel = wantLyricsPanel,
			progressStrip = progressStrip,
			lyricsStrip = lyricsStrip,
			openGlStaticDirty = openGlStaticDirty,
			openGlStaticUrgent = openGlStaticUrgent,
			openGlLyricsDirty = openGlLyricsDirty,
			lyricsKey = lyricsKey,
		)
	}

	private fun packStaticKey(vararg parts: Int): Long {
		var h = 1125899906842597L
		for (p in parts) {
			h = h * 1315423911L + (p.toLong() and 0xffffffffL)
		}
		return h
	}

	private fun tickLyricScroll(
		progressMs: Int,
		lyrics: LyricsState,
		lyrW: Float,
		lyrH: Float,
		scale: Float,
		dt: Float,
	) {
		val lines = lyrics.lines
		if (lines.isEmpty() || lyrH < 1f) return
		var idx = -1
		for (i in lines.indices) {
			if (lines[i].startTimeMs <= progressMs) idx = i else break
		}
		val spacing = 10f * scale
		ensureLyricLayoutCache(lyrics.trackId, lines, lyrW, scale, idx, spacing)
		val heights = lyricHeights
		val tops = lyricTops
		if (heights.isEmpty() || tops.isEmpty()) return
		val focusIdx = idx.coerceAtLeast(0).coerceAtMost(lines.lastIndex)
		val focusMid = tops[focusIdx] + heights[focusIdx] * 0.5f
		val target = focusMid - lyrH * 0.5f
		if (activeLyricIdx < 0) {
			activeLyricIdx = idx
			lyricScrollTarget = target
			lyricScrollY = target
		} else if (idx != activeLyricIdx) {
			activeLyricIdx = idx
			lyricScrollTarget = target
		} else {
			lyricScrollTarget = target
		}
		lyricScrollY = expLerp(lyricScrollY, lyricScrollTarget, dt, 6f)
	}

	private fun paintFrame(
		canvas: Canvas,
		sw: Float,
		sh: Float,
		@Suppress("UNUSED_PARAMETER") cfg: ModConfig,
		region: FrameRegion,
		parts: PaintParts,
	) {
		val track = region.track
		val progressMs = region.progressMs
		val lyricsAnim = region.lyricsAnim
		val alpha = region.alpha
		val layout = region.layout
		val px = region.px
		val py = region.py
		val s = region.s
		val ow = layout.w
		val oh = layout.h
		val lyricsState = region.lyricsState
		val hasLyrics = region.hasLyrics
		val isFetchingLyrics = region.isFetchingLyrics
		val wantLyricsPanel = region.wantLyricsPanel

		val pad = 14f * s
		val mediaH = OverlayLayout.MEDIA_H * s
		val artX = px + pad
		val artY = py + 12f * s
		val artSz = (mediaH - 24f * s).coerceAtLeast(28f * s)
		val infoX = artX + artSz + pad
		val infoY = artY + 4f * s
		val infoW = (px + ow - pad - infoX).coerceAtLeast(80f * s)
		val progBarY = py + mediaH - 22f * s

		val titleC = argb(255, 255, 255, (255f * alpha).toInt())
		val albumC = argb(130, 130, 130, (204f * alpha).toInt())

		if (parts == PaintParts.PROGRESS) {
			paintProgressTip(canvas, track, progressMs, infoX, progBarY, infoW, s, alpha, titleC, albumC)
			return
		}

		val lyricPending = argb(90, 90, 90, (160f * alpha * lyricsAnim).toInt())
		val lyricActive = argb(255, 255, 255, (255f * alpha * lyricsAnim).toInt())
		val lyricPlayed = argb(160, 160, 160, (173f * alpha * lyricsAnim).toInt())

		if (parts == PaintParts.LYRICS) {
			paintLyricsContent(
				canvas, region, pad, mediaH, lyricActive, lyricPlayed, lyricPending,
			)
			return
		}

		val cr = 14f * s
		val edgeEps = (2f * s).coerceAtLeast(2f)
		val touchLeft = px <= edgeEps
		val touchRight = px + ow >= sw - edgeEps
		val boxX = if (touchLeft) 0f else px
		val boxR = if (touchRight) sw else px + ow
		val boxW = (boxR - boxX).coerceAtLeast(1f)
		val rL = if (touchLeft) 0f else cr
		val rR = if (touchRight) 0f else cr
		val glow = cr + 6f * s
		val gL = if (touchLeft) 0f else glow
		val gR = if (touchRight) 0f else glow
		val glowPad = 8f * s
		val glowX = if (touchLeft) 0f else boxX - glowPad
		val glowR = if (touchRight) sw else boxR + glowPad

		fillRoundRect(
			canvas,
			glowX,
			py - glowPad,
			(glowR - glowX).coerceAtLeast(1f),
			oh + glowPad * 2f,
			gL, gR, gR, gL,
			argb(0, 0, 0, (70f * alpha).toInt()),
		)
		fillRoundRect(canvas, boxX, py, boxW, oh, rL, rR, rR, rL, argb(8, 8, 8, (0.92f * alpha * 255f).toInt()))

		val sheenA = (18f * alpha).toInt()
		if (sheenA > 0) {
			fillPaint.setShader(
				Shader.makeLinearGradient(
					boxX, py, boxX, py + oh * 0.2f,
					intArrayOf(argb(255, 255, 255, sheenA), argb(255, 255, 255, 0)),
				),
			)
			val sheenInset = 2f * s
			val sheenX = boxX + if (touchLeft) 0f else sheenInset
			val sheenR = boxR - if (touchRight) 0f else sheenInset
			val sheenRads = (cr - sheenInset).coerceAtLeast(0f)
			val sL = if (touchLeft) 0f else sheenRads
			val sR = if (touchRight) 0f else sheenRads
			canvas.drawRRect(
				RRect.makeXYWH(sheenX, py + sheenInset, (sheenR - sheenX).coerceAtLeast(1f), oh * 0.2f, sL, sR, sR, sL),
				fillPaint,
			)
			fillPaint.setShader(null)
		}

		strokeRoundRect(canvas, boxX, py, boxW, oh, rL, rR, rR, rL, 1f * s, argb(255, 255, 255, (40f * alpha).toInt()))
		val inset = (cr - 1f * s).coerceAtLeast(0f)
		val iL = if (touchLeft) 0f else inset
		val iR = if (touchRight) 0f else inset
		val strokeInset = 1f * s
		val inX = boxX + if (touchLeft) 0f else strokeInset
		val inR = boxR - if (touchRight) 0f else strokeInset
		strokeRoundRect(
			canvas,
			inX,
			py + strokeInset,
			(inR - inX).coerceAtLeast(1f),
			oh - strokeInset * 2f,
			iL, iR, iR, iL,
			strokeInset,
			argb(255, 255, 255, (18f * alpha).toInt()),
		)

		val artistC = argb(170, 170, 170, (255f * alpha).toInt())

		val artRadius = 10f * s

		val art = AlbumArtCache.currentImage()
		if (art != null) {
			canvas.save()
			canvas.clipRRect(RRect.makeXYWH(artX, artY, artSz, artSz, artRadius), true)
			canvas.drawImageRect(art, Rect.makeXYWH(artX, artY, artSz, artSz))
			canvas.restore()
		} else {
			fillRoundRect(canvas, artX, artY, artSz, artSz, artRadius, argb(32, 32, 32, (220f * alpha).toInt()))
			val qSize = 24f * s
			paintCached(canvas, "?", artX + artSz * 0.5f, artY + artSz * 0.5f, 10_000f, qSize, titleC, center = true)
		}
		strokeRoundRect(canvas, artX, artY, artSz, artSz, artRadius, 1.5f * s, argb(0, 0, 0, (66f * alpha).toInt()))

		var iy = infoY
		iy += drawEllipsized(canvas, track.title, infoX, iy, infoW, 17f * s, titleC) + 5f * s
		drawEllipsized(
			canvas,
			TrackMetadataEnricher.displayArtists(track.id, track.artists),
			infoX,
			iy,
			infoW,
			13f * s,
			artistC,
		)

		val bH = 4f * s
		val bR = bH * 0.5f
		fillRoundRect(canvas, infoX, progBarY, infoW, bH, bR, argb(45, 45, 45, (200f * alpha).toInt()))
		if (parts == PaintParts.ALL) {
			paintProgressTip(canvas, track, progressMs, infoX, progBarY, infoW, s, alpha, titleC, albumC)
		}

		if (lyricsFade < 0.002f) return

		val sepY = py + mediaH
		val lyricsPanelH = OverlayLayout.LYRICS_H * s * lyricsAnim
		fillRect(
			canvas,
			px + pad,
			sepY,
			ow - 2f * pad,
			1f * s,
			argb(255, 255, 255, (28f * alpha * lyricsAnim).toInt()),
		)

		if (parts == PaintParts.ALL) {
			paintLyricsContent(
				canvas, region, pad, mediaH, lyricActive, lyricPlayed, lyricPending,
			)
		}
	}

	private fun paintLyricsContent(
		canvas: Canvas,
		region: FrameRegion,
		pad: Float,
		mediaH: Float,
		lyricActive: Int,
		lyricPlayed: Int,
		lyricPending: Int,
	) {
		val lyricsAnim = region.lyricsAnim
		if (lyricsFade < 0.002f) return
		val px = region.px
		val py = region.py
		val s = region.s
		val ow = region.layout.w
		val lyricsState = region.lyricsState
		val hasLyrics = region.hasLyrics
		val isFetchingLyrics = region.isFetchingLyrics
		val wantLyricsPanel = region.wantLyricsPanel
		val progressMs = region.progressMs
		val track = region.track

		val sepY = py + mediaH
		val lyricsPanelH = OverlayLayout.LYRICS_H * s * lyricsAnim

		val displayLyrics = when {
			hasLyrics -> lyricsState
			else -> lastLyrics?.takeIf { it.trackId == track.id && it.lines.isNotEmpty() }
		}
		val showFetching = isFetchingLyrics || (displayLyrics == null && wantLyricsPanel)

		val lyrX = px + pad
		val lyrY = sepY + 8f * s
		val lyrW = ow - 2f * pad
		val lyrH = (lyricsPanelH - 8f * s - pad).coerceAtLeast(0f)
		val contentSlide = (1f - lyricsAnim) * -(OverlayLayout.LYRICS_H * s * LYRICS_SLIDE_FRACTION)

		canvas.save()
		canvas.clipRect(Rect.makeXYWH(px, sepY, ow, lyricsPanelH.coerceAtLeast(0f)))
		if (lyrH > 1f) {
			if (displayLyrics != null && !isFetchingLyrics) {
				drawLyricsColumn(
					canvas,
					lyrX,
					lyrY + contentSlide,
					lyrW,
					lyrH,
					s,
					progressMs,
					displayLyrics,
					lyricActive,
					lyricPlayed,
					lyricPending,
				)
			} else if (showFetching || isFetchingLyrics) {
				val fetchingMsg = "Fetching lyrics " + ".".repeat(fetchDotCount)
				centerText(
					canvas,
					fetchingMsg,
					lyrX,
					lyrY + contentSlide,
					lyrW,
					lyrH,
					13f * s,
					lyricPending,
				)
			}
		}
		canvas.restore()
	}

	private fun paintProgressTip(
		canvas: Canvas,
		track: TrackState,
		progressMs: Int,
		infoX: Float,
		progBarY: Float,
		infoW: Float,
		s: Float,
		alpha: Float,
		titleC: Int,
		albumC: Int,
	) {
		val duration = track.durationMs.coerceAtLeast(0L)
		val frac = if (duration > 0) (progressMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
		val bH = 4f * s
		val bR = bH * 0.5f
		if (frac > 0.001f) {
			fillRoundRect(canvas, infoX, progBarY - bH * 0.5f, infoW * frac + bH, bH * 3f, bR * 3f, argb(255, 255, 255, (28f * alpha).toInt()))
		}
		fillRoundRect(canvas, infoX, progBarY, infoW, bH, bR, argb(45, 45, 45, (200f * alpha).toInt()))
		if (frac > 0.001f) {
			fillRoundRect(canvas, infoX, progBarY, infoW * frac, bH, bR, argb(255, 255, 255, (242f * alpha).toInt()))
			val scrubX = infoX + infoW * frac
			fillRoundRect(canvas, scrubX - bH, progBarY - bH * 0.25f, bH * 2f, bH * 1.5f, bH, titleC)
		}
		val time = "${formatMs(progressMs.toLong())} / ${formatMs(duration)}"
		val tw = measureTextCached(time, 11f * s)
		drawTextCached(canvas, time, infoX + infoW - tw, progBarY - 17f * s, 11f * s, albumC)
	}

	private data class EdgeSlide(val offX: Float, val offY: Float, val speed: Float)

	private enum class SlideEdge { LEFT, RIGHT, TOP, BOTTOM }

	private fun slideFromNearestEdge(
		bounds: OverlayLayout.Bounds,
		sw: Float,
		sh: Float,
	): EdgeSlide {
		val touch = 2f
		val touchLeft = bounds.x <= touch
		val touchRight = bounds.x + bounds.w >= sw - touch
		val touchTop = bounds.y <= touch
		val touchBottom = bounds.y + bounds.h >= sh - touch

		val edge = when {
			touchTop -> SlideEdge.TOP
			touchBottom -> SlideEdge.BOTTOM
			touchLeft -> SlideEdge.LEFT
			touchRight -> SlideEdge.RIGHT
			else -> {
				val cx = bounds.x + bounds.w * 0.5f
				val cy = bounds.y + bounds.h * 0.5f
				val distLeft = cx
				val distRight = (sw - cx).coerceAtLeast(0f)
				val distTop = cy
				val distBottom = (sh - cy).coerceAtLeast(0f)
				val nearest = minOf(distLeft, distRight, distTop, distBottom)
				when (nearest) {
					distTop -> SlideEdge.TOP
					distBottom -> SlideEdge.BOTTOM
					distLeft -> SlideEdge.LEFT
					else -> SlideEdge.RIGHT
				}
			}
		}

		val (offX, offY) = when (edge) {
			SlideEdge.LEFT -> -(bounds.w + 28f) to 0f
			SlideEdge.RIGHT -> (bounds.w + 28f) to 0f
			SlideEdge.TOP -> 0f to -(bounds.h + 28f)
			SlideEdge.BOTTOM -> 0f to (bounds.h + 28f)
		}

		val cx = bounds.x + bounds.w * 0.5f
		val cy = bounds.y + bounds.h * 0.5f
		val centerNearest = minOf(cx, sw - cx, cy, sh - cy).coerceAtLeast(0f)
		val maxInset = maxOf(1f, minOf(sw, sh) * 0.5f)
		val centerFactor = (centerNearest / maxInset).coerceIn(0f, 1f)
		val speed = EDGE_SLIDE_SPEED + (CENTER_SLIDE_SPEED - EDGE_SLIDE_SPEED) * centerFactor

		return EdgeSlide(offX, offY, speed)
	}

	private fun easeOutCubic(t: Float): Float {
		val u = 1f - t
		return 1f - u * u * u
	}

	private fun easeInOutCubic(t: Float): Float {
		val x = t.coerceIn(0f, 1f)
		return if (x < 0.5f) {
			4f * x * x * x
		} else {
			val u = -2f * x + 2f
			1f - (u * u * u) / 2f
		}
	}

	private fun drawLyricsColumn(
		canvas: Canvas,
		lyrX: Float,
		lyrY: Float,
		lyrW: Float,
		lyrH: Float,
		scale: Float,
		progressMs: Int,
		lyrics: LyricsState?,
		lyricActive: Int,
		lyricPlayed: Int,
		lyricPending: Int,
	) {
		canvas.save()
		canvas.clipRect(Rect.makeXYWH(lyrX, lyrY, lyrW, lyrH))

		val lines = lyrics?.lines.orEmpty()
		if (lyrics == null) {
			centerText(canvas, "Fetching lyrics...", lyrX, lyrY, lyrW, lyrH, 13f * scale, lyricPending)
			canvas.restore()
			return
		}
		if (lines.isEmpty()) {
			centerText(canvas, lyrics.status ?: "No lyrics found", lyrX, lyrY, lyrW, lyrH, 13f * scale, lyricPending)
			canvas.restore()
			return
		}

		var idx = -1
		for (i in lines.indices) {
			if (lines[i].startTimeMs <= progressMs) idx = i else break
		}
		val spacing = 10f * scale
		val trackId = lyrics.trackId
		ensureLyricLayoutCache(trackId, lines, lyrW, scale, idx, spacing)
		val heights = lyricHeights
		val tops = lyricTops

		val inactiveSize = 13.5f * scale
		val activeSize = 15.5f * scale
		for (i in lines.indices) {
			val blockH = heights[i]
			val y = lyrY + tops[i] - lyricScrollY
			if (y + blockH < lyrY - blockH || y > lyrY + lyrH + blockH) continue

			val edge = 24f * scale
			var edgeAlpha = 1f
			if (y < lyrY + edge) edgeAlpha = ((y - lyrY + blockH) / (edge + blockH)).coerceIn(0f, 1f)
			if (y + blockH > lyrY + lyrH - edge) {
				edgeAlpha = minOf(edgeAlpha, ((lyrY + lyrH - y) / (edge + blockH)).coerceIn(0f, 1f))
			}

			val color = when {
				i == idx -> quantizeAlpha(multiplyAlpha(lyricActive, edgeAlpha))
				i < idx -> quantizeAlpha(multiplyAlpha(lyricPlayed, edgeAlpha * 0.68f))
				else -> quantizeAlpha(multiplyAlpha(lyricPending, edgeAlpha * 0.48f))
			}
			val size = if (i == idx) activeSize else inactiveSize
			drawWrappedText(canvas, lines[i].words, lyrX, y, lyrW, size, color)
		}

		canvas.restore()
	}

	private fun ensureLyricLayoutCache(
		trackId: String,
		lines: List<LyricLine>,
		lyrW: Float,
		scale: Float,
		activeIdx: Int,
		spacing: Float,
	) {
		val inactiveSize = 13.5f * scale
		val activeSize = 15.5f * scale
		val rebuildBase =
			trackId != lyricCacheTrackId ||
				lyrW != lyricCacheWidth ||
				scale != lyricCacheScale ||
				lines.size != lyricInactiveHeights.size

		if (rebuildBase) {
			lyricCacheTrackId = trackId
			lyricCacheWidth = lyrW
			lyricCacheScale = scale
			lyricInactiveHeights = FloatArray(lines.size) { i ->
				measureWrappedHeight(lines[i].words, lyrW, inactiveSize).coerceAtLeast(inactiveSize * 1.15f)
			}
			lyricCacheActiveIdx = Int.MIN_VALUE
		}

		if (rebuildBase || activeIdx != lyricCacheActiveIdx) {
			lyricHeights = lyricInactiveHeights.copyOf()
			if (activeIdx in lines.indices) {
				lyricHeights[activeIdx] = measureWrappedHeight(lines[activeIdx].words, lyrW, activeSize)
					.coerceAtLeast(activeSize * 1.15f)
			}
			lyricTops = FloatArray(lines.size)
			var acc = 0f
			for (i in lines.indices) {
				lyricTops[i] = acc
				acc += lyricHeights[i] + spacing
			}
			lyricCacheActiveIdx = activeIdx
		}
	}

	private fun isNearNaturalEnd(track: TrackState): Boolean {
		val duration = track.durationMs
		if (duration <= 0L) return false
		return track.interpolatedProgressMs() >= duration - END_OF_TRACK_PAUSE_IGNORE_MS
	}

	private fun smoothProgress(track: TrackState?): Int {
		if (track == null) {
			lastEmittedTrackId = null
			lastEmittedProgressMs = 0
			return 0
		}
		val progressMs = track.interpolatedProgressMs().toInt()
		if (lastEmittedTrackId != track.id) {
			lastEmittedTrackId = track.id
			lastEmittedProgressMs = progressMs
			return progressMs
		}
		if (track.isPlaying) {
			val back = lastEmittedProgressMs - progressMs
			if (back in 1 until TrackState.SEEK_THRESHOLD_MS.toInt()) {
				return lastEmittedProgressMs
			}
		}
		lastEmittedProgressMs = progressMs
		return progressMs
	}

	private fun expLerp(current: Float, target: Float, dt: Float, speed: Float): Float {
		val t = 1f - exp((-dt * speed).toDouble()).toFloat()
		return current + (target - current) * t
	}

	private fun centerText(canvas: Canvas, text: String, x: Float, y: Float, w: Float, h: Float, size: Float, color: Int) {
		val tw = measureTextCached(text, size)
		drawTextCached(canvas, text, x + (w - tw) * 0.5f, y + h * 0.5f - 7f, size, color)
	}

	private fun paintCached(
		canvas: Canvas,
		text: String,
		x: Float,
		y: Float,
		maxWidth: Float,
		size: Float,
		color: Int,
		center: Boolean,
	) {
		val para = paragraphFor(text, size, color, maxWidth)
		if (center) {
			para.paint(canvas, x - para.maxIntrinsicWidth * 0.5f, y - para.height * 0.5f)
		} else {
			para.paint(canvas, x, y)
		}
	}

	private fun drawWrappedText(
		canvas: Canvas,
		text: String,
		x: Float,
		y: Float,
		maxWidth: Float,
		size: Float,
		color: Int,
	): Float {
		if (text.isEmpty()) return size * 1.2f
		val para = paragraphFor(text, size, color, maxWidth.coerceAtLeast(1f))
		para.paint(canvas, x, y)
		return para.height
	}

	private fun measureWrappedHeight(text: String, maxWidth: Float, size: Float): Float {
		if (text.isEmpty()) return size * 1.2f
		// Neutral color — height does not depend on glyph color.
		return paragraphFor(text, size, 0xFFFFFFFF.toInt(), maxWidth.coerceAtLeast(1f)).height
	}

	private fun drawEllipsized(
		canvas: Canvas,
		text: String,
		x: Float,
		y: Float,
		maxWidth: Float,
		size: Float,
		color: Int,
	): Float {
		if (text.isEmpty() || maxWidth <= 0f) return 12f
		val full = measureTextCached(text, size)
		if (full <= maxWidth) {
			drawTextCached(canvas, text, x, y, size, color)
			return size * 1.2f
		}
		var low = 0
		var high = text.length
		var best = "…"
		while (low <= high) {
			val mid = (low + high) / 2
			val candidate = text.take(mid).trimEnd() + "…"
			if (measureTextCached(candidate, size) <= maxWidth) {
				best = candidate
				low = mid + 1
			} else {
				high = mid - 1
			}
		}
		drawTextCached(canvas, best, x, y, size, color)
		return size * 1.2f
	}

	private fun drawTextCached(canvas: Canvas, text: String, x: Float, y: Float, size: Float, color: Int) {
		paragraphFor(text, size, color, 10_000f).paint(canvas, x, y)
	}

	private fun measureTextCached(text: String, size: Float): Float {
		val key = widthKey(text, size)
		widthCache[key]?.let { return it }
		val w = paragraphFor(text, size, 0xFFFFFFFF.toInt(), 10_000f).maxIntrinsicWidth
		if (widthCache.size > 128) widthCache.clear()
		widthCache[key] = w
		return w
	}

	private fun paragraphFor(text: String, size: Float, color: Int, maxWidth: Float): Paragraph {
		val key = paragraphKey(text, size, color, maxWidth)
		paragraphCache[key]?.let { return it }
		val para = buildParagraph(text, size, color)
		para.layout(maxWidth.coerceAtLeast(1f))
		paragraphCache[key] = para
		return para
	}

	private fun clearParagraphCache() {
		paragraphCache.values.forEach { it.close() }
		paragraphCache.clear()
		widthCache.clear()
	}

	private fun paragraphKey(text: String, size: Float, color: Int, maxWidth: Float): Long {
		var h = text.hashCode().toLong()
		h = h * 31 + (size * 10f).toInt()
		h = h * 31 + color
		h = h * 31 + (maxWidth * 2f).toInt()
		return h
	}

	private fun widthKey(text: String, size: Float): Long =
		text.hashCode().toLong() * 31 + (size * 10f).toInt()

	private fun quantizeAlpha(color: Int): Int {
		val a = ((color ushr 24) and 0xFF) / 16 * 16
		return (color and 0x00FFFFFF) or (a shl 24)
	}

	private fun buildParagraph(text: String, size: Float, color: Int): Paragraph {
		val style = TextStyle()
			.setColor(color)
			.setFontSize(size)
			.setFontStyle(FontStyle.NORMAL)
			.setFontFamilies(fontFamilies)
		return ParagraphBuilder(ParagraphStyle(), fontCollection)
			.pushStyle(style)
			.addText(text)
			.popStyle()
			.build()
	}

	private fun fillRoundRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
		fillRoundRect(canvas, x, y, w, h, r, r, r, r, color)
	}

	private fun fillRoundRect(
		canvas: Canvas,
		x: Float,
		y: Float,
		w: Float,
		h: Float,
		tl: Float,
		tr: Float,
		br: Float,
		bl: Float,
		color: Int,
	) {
		fillPaint.setColor(color)
		canvas.drawRRect(RRect.makeXYWH(x, y, w, h, tl, tr, br, bl), fillPaint)
	}

	private fun strokeRoundRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float, stroke: Float, color: Int) {
		strokeRoundRect(canvas, x, y, w, h, r, r, r, r, stroke, color)
	}

	private fun strokeRoundRect(
		canvas: Canvas,
		x: Float,
		y: Float,
		w: Float,
		h: Float,
		tl: Float,
		tr: Float,
		br: Float,
		bl: Float,
		stroke: Float,
		color: Int,
	) {
		strokePaint.setColor(color).setStrokeWidth(stroke)
		canvas.drawRRect(RRect.makeXYWH(x, y, w, h, tl, tr, br, bl), strokePaint)
	}

	private fun fillRect(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, color: Int) {
		fillPaint.setColor(color)
		canvas.drawRect(Rect.makeXYWH(x, y, w, h), fillPaint)
	}

	private fun argb(r: Int, g: Int, b: Int, a: Int): Int {
		val aa = a.coerceIn(0, 255)
		val rr = r.coerceIn(0, 255)
		val gg = g.coerceIn(0, 255)
		val bb = b.coerceIn(0, 255)
		return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
	}

	private fun multiplyAlpha(color: Int, factor: Float): Int {
		val a = ((color ushr 24) and 0xFF) * factor.coerceIn(0f, 1f)
		return (color and 0x00FFFFFF) or (a.toInt().coerceIn(0, 255) shl 24)
	}

	private fun formatMs(ms: Long): String {
		val totalSec = (ms / 1000).coerceAtLeast(0)
		return "%d:%02d".format(totalSec / 60, totalSec % 60)
	}

	private inline fun <T : AutoCloseable, R> T.use(block: (T) -> R): R {
		try {
			return block(this)
		} finally {
			close()
		}
	}
}

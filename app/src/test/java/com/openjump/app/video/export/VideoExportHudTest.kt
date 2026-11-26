package com.openjump.app.video.export

import com.openjump.app.encoder.EncoderSample
import com.openjump.app.encoder.ObservationKind
import com.openjump.app.tracking.ImagePoint
import com.openjump.app.tracking.TrackingStatus
import com.openjump.app.tracking.VideoPresentationGeometry
import com.openjump.app.ui.result.computePreviewTrajectoryScale
import com.openjump.app.video.VideoFrameIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoExportHudTest {

    @Test
    fun `logo y hud nunca solapan incluso en la misma esquina`() {
        val resolutions = listOf(1080 to 1920, 1920 to 1080, 720 to 1280)
        for ((w, h) in resolutions) {
            for (corner in VideoOverlayCorner.entries) {
                val layout = computeVideoOverlayLayout(
                    videoWidth = w,
                    videoHeight = h,
                    options = VideoExportOptions(
                        showLogo = true,
                        logoCorner = corner,
                        showHud = true,
                        hudCorner = corner,
                    ),
                    hasSecondaryMetrics = true,
                    hasAthleteRow = true,
                    hasRepDots = true,
                )
                val logo = requireNotNull(layout.logoRect)
                val hud = requireNotNull(layout.hudRect)
                assertFalse("Solapamiento en ${w}x${h} esquina $corner", logo.overlaps(hud))
                assertTrue(logo.left >= 0f && logo.top >= 0f && logo.right <= w && logo.bottom <= h)
                assertTrue(hud.left >= 0f && hud.top >= 0f && hud.right <= w && hud.bottom <= h)
            }
        }
    }

    @Test
    fun `ocultar logo o hud devuelve null en el layout correspondiente`() {
        val logoOnly = computeVideoOverlayLayout(
            videoWidth = 1080,
            videoHeight = 1920,
            options = VideoExportOptions(showLogo = true, showHud = false),
            hasSecondaryMetrics = false,
            hasAthleteRow = false,
            hasRepDots = false,
        )
        assertNotNull(logoOnly.logoRect)
        assertNull(logoOnly.hudRect)

        val hudOnly = computeVideoOverlayLayout(
            videoWidth = 1080,
            videoHeight = 1920,
            options = VideoExportOptions(showLogo = false, showHud = true),
            hasSecondaryMetrics = true,
            hasAthleteRow = false,
            hasRepDots = false,
        )
        assertNull(hudOnly.logoRect)
        assertNotNull(hudOnly.hudRect)
    }

    @Test
    fun `jump hud transiciona de listo a vuelo con cronometro y aterrizaje`() {
        val spec = VideoHudSpec(
            title = "CMJ",
            subtitle = "Tiempo de vuelo",
            primaryLabel = "Altura de salto",
            primaryValue = "42.8 cm",
            jumpTimeline = VideoHudJumpTimeline(
                takeoffPtsUs = 1_500_000L,
                landingPtsUs = 2_090_000L,
                readyLabel = "Listo",
                inFlightLabel = "En vuelo",
                landedLabel = "Aterrizaje",
            ),
        )
        val firstPts = 500_000L

        val before = resolveVideoHudFrameState(spec, presentationTimeUs = 800_000L, firstSourcePtsUs = firstPts)
        assertEquals("Listo", before.statusBadge)
        assertEquals(VideoHudPhaseTone.NEUTRAL, before.statusTone)
        assertNull(before.liveDetail)

        val inFlight = resolveVideoHudFrameState(spec, presentationTimeUs = 1_310_000L, firstSourcePtsUs = firstPts)
        assertEquals("En vuelo", inFlight.statusBadge)
        assertEquals(VideoHudPhaseTone.ACTIVE, inFlight.statusTone)
        assertEquals("0.31 s", inFlight.liveDetail)

        val landed = resolveVideoHudFrameState(spec, presentationTimeUs = 1_700_000L, firstSourcePtsUs = firstPts)
        assertEquals("Aterrizaje", landed.statusBadge)
        assertEquals(VideoHudPhaseTone.COMPLETE, landed.statusTone)
        assertNull(landed.liveDetail)
    }

    @Test
    fun `encoder hud avanza por repeticiones y muestra velocidad instantanea y ROM por rep`() {
        val summaryMetrics = listOf(
            VideoHudMetric("PV", "1.20 m/s"),
            VideoHudMetric("Pérdida", "28.0 %"),
            VideoHudMetric("ROM", "0.62 m"),
        )
        val rep1Metrics = listOf(
            VideoHudMetric("PV", "1.20 m/s"),
            VideoHudMetric("Pérdida", "0.0 %"),
            VideoHudMetric("ROM", "0.65 m"),
        )
        val rep3Metrics = listOf(
            VideoHudMetric("PV", "0.94 m/s"),
            VideoHudMetric("Pérdida", "28.0 %"),
            VideoHudMetric("ROM", "0.58 m"),
        )
        val spec = VideoHudSpec(
            title = "Press banca",
            subtitle = "80 kg · 3 reps",
            primaryLabel = "VMC",
            primaryValue = "0.72 m/s",
            secondaryMetrics = summaryMetrics,
            encoderReps = listOf(
                VideoHudEncoderRepSegment(1, 1_000_000L, 2_000_000L, "0.72 m/s", "0%", rep1Metrics),
                VideoHudEncoderRepSegment(2, 2_500_000L, 3_500_000L, "0.64 m/s", "-11%"),
                VideoHudEncoderRepSegment(3, 4_000_000L, 5_100_000L, "0.52 m/s", "-28%", rep3Metrics),
            ),
            encoderVelocitySamples = listOf(
                VideoHudEncoderVelocitySample(1_200_000L, "0.45 m/s", -0.45),
                VideoHudEncoderVelocitySample(1_700_000L, "0.84 m/s", 0.84),
                VideoHudEncoderVelocitySample(4_600_000L, "0.59 m/s", 0.59),
            ),
            repPrefix = "REP",
            totalReps = 3,
        )

        val preSet = resolveVideoHudFrameState(spec, presentationTimeUs = 400_000L, firstSourcePtsUs = 0L)
        assertEquals("REP 0/3", preSet.statusBadge)
        assertEquals("0.72 m/s", preSet.primaryValue)
        assertNull(preSet.activeRepNumber)
        assertEquals(summaryMetrics, preSet.secondaryMetrics)

        val rep1 = resolveVideoHudFrameState(spec, presentationTimeUs = 1_750_000L, firstSourcePtsUs = 0L)
        assertEquals("REP 1/3", rep1.statusBadge)
        assertEquals(VideoHudPhaseTone.ACTIVE, rep1.statusTone)
        assertEquals("0.72 m/s", rep1.primaryValue)
        assertEquals("0.84 m/s", rep1.liveDetail)
        assertEquals(1, rep1.activeRepNumber)
        assertEquals("0.65 m", rep1.secondaryMetrics.last().value)

        val betweenReps = resolveVideoHudFrameState(spec, presentationTimeUs = 2_200_000L, firstSourcePtsUs = 0L)
        assertEquals("REP 1/3", betweenReps.statusBadge)
        assertEquals(VideoHudPhaseTone.COMPLETE, betweenReps.statusTone)
        assertEquals("0%", betweenReps.liveDetail)
        assertEquals("0.65 m", betweenReps.secondaryMetrics.last().value)

        val rep3 = resolveVideoHudFrameState(spec, presentationTimeUs = 4_650_000L, firstSourcePtsUs = 0L)
        assertEquals("REP 3/3", rep3.statusBadge)
        assertEquals("0.52 m/s", rep3.primaryValue)
        assertEquals("0.59 m/s", rep3.liveDetail)
        assertEquals(3, rep3.activeRepNumber)
        assertEquals("0.58 m", rep3.secondaryMetrics.last().value)
        assertEquals(summaryMetrics.map { it.label }, rep3.secondaryMetrics.map { it.label })
    }

    @Test
    fun `paletas claro y oscuro son puras en JVM y distintas`() {
        val dark = videoHudPalette(lightTheme = false)
        val light = videoHudPalette(lightTheme = true)
        assertTrue(dark.cardBgTop != light.cardBgTop)
        assertTrue(dark.textPrimary != light.textPrimary)
    }

    @Test
    fun `TrajectoryTimeline fromEncoderSamples construye linea de tiempo valida y tolera ausencias`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(100_000L, 116_667L, 133_333L, 150_000L),
            durationUs = 66_667L,
            width = 1080,
            height = 1920,
            rotationDegrees = 0,
            mimeType = "video/avc",
        )
        val samples = listOf(
            encoderSample(0, 0, 100_000L, ImagePoint(500.0, 900.0)),
            encoderSample(1, 1, 116_667L, ImagePoint(502.0, 860.0)),
            encoderSample(2, 2, 133_333L, null, TrackingStatus.LOST),
            encoderSample(3, 3, 150_000L, ImagePoint(505.0, 810.0)),
        )

        val timeline = TrajectoryTimeline.fromEncoderSamples(index, samples)
        assertNotNull(timeline)
        assertEquals(4, timeline!!.samples.size)
        assertEquals(ImagePoint(502.0, 860.0), timeline.sampleAtOrBefore(16_667L)?.point)
        assertEquals(TrackingStatus.LOST, timeline.sampleAtOrBefore(33_333L)?.status)

        assertNull(TrajectoryTimeline.fromEncoderSamples(index, emptyList()))
        assertNull(
            TrajectoryTimeline.fromEncoderSamples(
                index,
                listOf(encoderSample(0, 0, 100_000L, null, TrackingStatus.LOST)),
            ),
        )
    }

    @Test
    fun `escala de preview de trayectoria mantiene proporcion exacta en videos horizontales y verticales`() {
        val landscapeScale = computePreviewTrajectoryScale(
            previewWidth = 720,
            previewHeight = 405,
            sourceGeometry = VideoPresentationGeometry.fromEncoded(1920, 1080, 0),
        )
        assertNotNull(landscapeScale)
        assertEquals(0.375f, landscapeScale!!.first, 0.0001f)
        assertEquals(0.375f, landscapeScale.second, 0.0001f)

        val portraitRotatedScale = computePreviewTrajectoryScale(
            previewWidth = 405,
            previewHeight = 720,
            sourceGeometry = VideoPresentationGeometry.fromEncoded(1920, 1080, 90),
        )
        assertNotNull(portraitRotatedScale)
        assertEquals(0.375f, portraitRotatedScale!!.first, 0.0001f)
        assertEquals(0.375f, portraitRotatedScale.second, 0.0001f)

        assertNull(computePreviewTrajectoryScale(720, 405, null))
    }

    @Test
    fun `grafica por repeticion avanza en rep 1 congela entre reps y reinicia limpia en rep 2`() {
        val rep1Curve = listOf(
            VideoHudRepTelemetryPoint(0.0f, -0.4, 1.0f),
            VideoHudRepTelemetryPoint(0.5f, 0.0, 0.0f),
            VideoHudRepTelemetryPoint(1.0f, 1.1, 1.0f),
        )
        val rep2Curve = listOf(
            VideoHudRepTelemetryPoint(0.0f, -0.3, 1.0f),
            VideoHudRepTelemetryPoint(0.5f, 0.0, 0.05f),
            VideoHudRepTelemetryPoint(1.0f, 0.75, 0.95f),
        )
        val spec = VideoHudSpec(
            title = "Press banca",
            subtitle = "80 kg · 2 reps",
            primaryLabel = "VMC",
            primaryValue = "0.78 m/s",
            encoderReps = listOf(
                VideoHudEncoderRepSegment(1, 1_000_000L, 2_000_000L, "0.78 m/s", "0%", telemetryPoints = rep1Curve),
                VideoHudEncoderRepSegment(2, 3_000_000L, 4_000_000L, "0.58 m/s", "-26%", telemetryPoints = rep2Curve),
            ),
            repPrefix = "REP",
            totalReps = 2,
            chartMaxVelMps = 1.25,
            chartMinVelMps = -0.6,
            chartBestPeakVelMps = 1.1,
        )
        assertTrue(spec.hasLiveTelemetryChart)

        val before = resolveVideoHudFrameState(spec, presentationTimeUs = 500_000L, firstSourcePtsUs = 0L)
        assertEquals(-1, before.chartProgressBucket)

        val midRep1 = resolveVideoHudFrameState(spec, presentationTimeUs = 1_500_000L, firstSourcePtsUs = 0L)
        assertEquals(1, midRep1.chartRepNumber)
        assertEquals(30, midRep1.chartProgressBucket)
        assertEquals(rep1Curve, midRep1.chartPoints)

        val between = resolveVideoHudFrameState(spec, presentationTimeUs = 2_400_000L, firstSourcePtsUs = 0L)
        assertEquals(1, between.chartRepNumber)
        assertEquals(CHART_PROGRESS_BUCKETS, between.chartProgressBucket)

        val earlyRep2 = resolveVideoHudFrameState(spec, presentationTimeUs = 3_250_000L, firstSourcePtsUs = 0L)
        assertEquals(2, earlyRep2.chartRepNumber)
        assertEquals(15, earlyRep2.chartProgressBucket)
        assertEquals(rep2Curve, earlyRep2.chartPoints)
    }

    @Test
    fun `layout con grafica en vivo nunca solapa con el logo en ninguna esquina`() {
        for ((w, h) in listOf(1080 to 1920, 1920 to 1080)) {
            for (corner in VideoOverlayCorner.entries) {
                val layout = computeVideoOverlayLayout(
                    videoWidth = w,
                    videoHeight = h,
                    options = VideoExportOptions(
                        showLogo = true,
                        logoCorner = corner,
                        showHud = true,
                        hudCorner = corner,
                        showLiveChart = true,
                    ),
                    hasSecondaryMetrics = true,
                    hasAthleteRow = true,
                    hasRepDots = true,
                    hasLiveChart = true,
                )
                val logo = requireNotNull(layout.logoRect)
                val hud = requireNotNull(layout.hudRect)
                assertFalse("Solapamiento con grafica en ${w}x${h} $corner", logo.overlaps(hud))
            }
        }
    }

    @Test
    fun `ventana de trayectoria por repeticion reinicia en cada rep y acumula si esta vacia`() {
        val index = VideoFrameIndex(
            frameTimesUs = longArrayOf(0L, 100_000L, 200_000L, 300_000L, 400_000L, 500_000L),
            durationUs = 600_000L,
            width = 1080,
            height = 1920,
            rotationDegrees = 0,
            mimeType = "video/avc",
        )
        val samples = (0..5).map { i ->
            encoderSample(i, i, i * 100_000L, ImagePoint(500.0, 900.0 - i * 20.0))
        }
        val timeline = requireNotNull(TrajectoryTimeline.fromEncoderSamples(index, samples))
        val repStarts = listOf(100_000L, 400_000L)

        // Full set (empty repStarts) always starts at sample 0
        assertEquals(0, resolveTrajectoryStartIndex(timeline, 450_000L, emptyList()))

        // Per-rep mode
        assertEquals(0, resolveTrajectoryStartIndex(timeline, 50_000L, repStarts))
        assertEquals(1, resolveTrajectoryStartIndex(timeline, 250_000L, repStarts))
        assertEquals(4, resolveTrajectoryStartIndex(timeline, 450_000L, repStarts))
    }

    @Test
    fun `layout con potencia estimada de Sayers no solapa con el logo y expande altura del HUD`() {
        val base = computeVideoOverlayLayout(
            videoWidth = 1080,
            videoHeight = 1920,
            options = VideoExportOptions(
                showLogo = true,
                logoCorner = VideoOverlayCorner.BOTTOM_LEFT,
                showHud = true,
                hudCorner = VideoOverlayCorner.BOTTOM_LEFT,
                includeEstimatedPower = false,
            ),
            hasSecondaryMetrics = true,
            hasAthleteRow = false,
            hasRepDots = false,
            hasEstimatedPowerRow = false,
        )
        val withPower = computeVideoOverlayLayout(
            videoWidth = 1080,
            videoHeight = 1920,
            options = VideoExportOptions(
                showLogo = true,
                logoCorner = VideoOverlayCorner.BOTTOM_LEFT,
                showHud = true,
                hudCorner = VideoOverlayCorner.BOTTOM_LEFT,
                includeEstimatedPower = true,
            ),
            hasSecondaryMetrics = true,
            hasAthleteRow = true,
            hasRepDots = false,
            hasEstimatedPowerRow = true,
        )
        val baseHud = requireNotNull(base.hudRect)
        val powerHud = requireNotNull(withPower.hudRect)
        val logo = requireNotNull(withPower.logoRect)
        assertTrue(powerHud.height > baseHud.height)
        assertFalse(logo.overlaps(powerHud))
    }

    @Test
    fun `escala de coordenadas de overlay proyecta 4K a 1080p y respeta 1 a 1`() {
        val (sx4k, sy4k) = computeOverlayCoordinateScale(
            targetWidth = 1080,
            targetHeight = 1920,
            sourceGeometry = VideoPresentationGeometry(2160, 3840),
        )
        assertEquals(0.5f, sx4k, 1e-4f)
        assertEquals(0.5f, sy4k, 1e-4f)

        val (sx1080, sy1080) = computeOverlayCoordinateScale(
            targetWidth = 1920,
            targetHeight = 1080,
            sourceGeometry = VideoPresentationGeometry(1920, 1080),
        )
        assertEquals(1f, sx1080, 1e-4f)
        assertEquals(1f, sy1080, 1e-4f)

        val (sxNull, syNull) = computeOverlayCoordinateScale(1080, 1920, null)
        assertEquals(1f, sxNull, 1e-4f)
        assertEquals(1f, syNull, 1e-4f)

        // Modo claro debe contrastar contra el fondo blanco del grafico
        assertTrue(LightVideoHudPalette.chartDotHalo != LightVideoHudPalette.chartBg)
        assertTrue(DarkVideoHudPalette.chartDotHalo != DarkVideoHudPalette.chartBg)
    }

    private fun encoderSample(
        ordinal: Int,
        frameIndex: Int,
        ptsUs: Long,
        point: ImagePoint?,
        status: TrackingStatus = TrackingStatus.TRACKING,
    ) = EncoderSample(
        ordinal = ordinal,
        frameIndex = frameIndex,
        sourcePtsUs = ptsUs,
        physicalTimeUs = ptsUs,
        rawPixelPoint = point,
        rawMetricPoint = null,
        smoothedMetricPoint = null,
        velocityXMps = null,
        velocityYMps = 0.5,
        confidence = if (point != null) 0.9 else 0.0,
        trackingStatus = status,
        observationKind = if (point != null) ObservationKind.MEASURED else ObservationKind.MISSING,
        fitResidualM = null,
        gapBeforeUs = null,
    )
}

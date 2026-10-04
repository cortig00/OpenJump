package com.openjump.app.camera

import android.annotation.SuppressLint
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapabilities
import android.util.Size

/** Discovers product profiles from one CameraX rear CameraInfo. */
object CameraXProfileCatalog {
    data class Discovery(
        val selector: CameraSelector,
        val cameraInfo: CameraInfo,
        val profiles: List<CameraProductProfile>,
    ) {
        init {
            // Capability queries and the later bind must remain on this one rear CameraInfo.
            require(profiles.all { it.cameraId == cameraInfo.cameraIdentifier.toString() })
        }
    }

    fun discover(provider: ProcessCameraProvider): Discovery? {
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val info = runCatching { provider.getCameraInfo(selector) }.getOrNull() ?: return null
        val cameraId = info.cameraIdentifier.toString()
        val qualities = listOf(Quality.HD, Quality.FHD)
        val regular = capabilities(info, Recorder.VIDEO_CAPABILITIES_SOURCE_CODEC_CAPABILITIES)
        val highSpeed = runCatching { Recorder.getHighSpeedVideoCapabilities(info) }.getOrNull()
        val profiles = buildList {
            addAll(profilesFor(info, cameraId, regular, qualities, 30, CameraSessionKind.NORMAL))
            addAll(profilesFor(info, cameraId, regular, qualities, 60, CameraSessionKind.NORMAL))
            addAll(profilesFor(info, cameraId, highSpeed, qualities, 120, CameraSessionKind.HIGH_SPEED))
            addAll(profilesFor(info, cameraId, highSpeed, qualities, 240, CameraSessionKind.HIGH_SPEED))
        }
        // 30 fps is retained solely as Auto's explicit degraded terminal candidate.
        val productProfiles = CameraProfilePolicy.visible(profiles) + profiles.filter { it.fps == 30 }
        return Discovery(selector, info, productProfiles.distinct())
    }

    private fun capabilities(info: CameraInfo, source: Int): VideoCapabilities? =
        runCatching { Recorder.getVideoCapabilities(info, source) }.getOrNull()

    @SuppressLint("RestrictedApi")
    private fun profilesFor(
        info: CameraInfo,
        cameraId: String,
        capabilities: VideoCapabilities?,
        qualities: List<Quality>,
        fps: Int,
        kind: CameraSessionKind,
    ): List<CameraProductProfile> {
        if (capabilities == null) return emptyList()
        val supported = runCatching {
            capabilities.getSupportedQualities(DynamicRange.SDR)
        }.getOrDefault(emptyList())
        return qualities.mapNotNull { quality ->
            if (quality !in supported) return@mapNotNull null
            val resolution = runCatching {
                capabilities.getResolution(quality, DynamicRange.SDR)
            }.getOrNull() ?: return@mapNotNull null
            if (!isProductResolution(resolution)) return@mapNotNull null
            CameraProductProfile(
                cameraId = cameraId,
                quality = qualityName(quality),
                width = resolution.width,
                height = resolution.height,
                fps = fps,
                sessionKind = kind,
            )
        }
    }

    private fun qualityName(quality: Quality): String = when (quality) {
        Quality.HD -> "HD"
        Quality.FHD -> "FHD"
        else -> quality.toString()
    }

    private fun isProductResolution(size: Size): Boolean =
        size.width >= size.height && size.width <= 1920 && size.height <= 1080
}

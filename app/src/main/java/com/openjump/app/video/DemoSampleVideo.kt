package com.openjump.app.video

import android.content.Context
import android.net.Uri
import com.openjump.app.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides cached local copies of the bundled open-source sample clips (CMJ jump and Back Squat
 * barbell encoder) so new users can test both measurement workflows immediately without recording
 * a video first.
 */
object DemoSampleVideo {

    private const val DEMO_DIR = "demo"
    private const val DEMO_JUMP_FILE = "demo_cmj_sample.mp4"
    private const val DEMO_ENCODER_FILE = "demo_encoder_squat_sample.mp4"

    const val ENCODER_DEMO_LOAD_KG = 80.0
    const val ENCODER_DEMO_PLATE_DIAMETER_CM = 45.0

    suspend fun ensureCachedUri(context: Context): Uri =
        ensureResourceCachedUri(context, R.raw.demo_cmj_sample, DEMO_JUMP_FILE)

    suspend fun ensureEncoderCachedUri(context: Context): Uri =
        ensureResourceCachedUri(context, R.raw.demo_encoder_squat_sample, DEMO_ENCODER_FILE)

    private suspend fun ensureResourceCachedUri(
        context: Context,
        rawResId: Int,
        fileName: String,
    ): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DEMO_DIR).apply { mkdirs() }
        val target = File(dir, fileName)
        if (!target.isFile || target.length() == 0L) {
            val tmp = File(dir, "$fileName.tmp")
            context.resources.openRawResource(rawResId).use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        Uri.fromFile(target)
    }
}

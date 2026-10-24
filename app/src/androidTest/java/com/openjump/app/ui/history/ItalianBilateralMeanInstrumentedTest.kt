package com.openjump.app.ui.history

import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openjump.app.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * F003: the Italian `bilateral_mean` label is the plain "Media", matching the
 * existing `comparison_average` wording (parent-authorized translation fix).
 */
@RunWith(AndroidJUnit4::class)
class ItalianBilateralMeanInstrumentedTest {
    @Test fun italianBilateralMeanIsPlainMedia() {
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val config = Configuration(appContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("it"))
        }
        val strings = appContext.createConfigurationContext(config)
        assertEquals("Media", strings.getString(R.string.bilateral_mean))
        assertEquals("Media", strings.getString(R.string.comparison_average))
    }
}

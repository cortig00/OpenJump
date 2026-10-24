package com.openjump.app.data.backup

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DataExtractionRulesInstrumentedTest {
    @Test fun backupIsDisabledAndBothRuleSectionsExcludeAllStorageDomains() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.applicationInfo
        assertEquals(0, info.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        val resourceId = context.resources.getIdentifier("data_extraction_rules", "xml", context.packageName)
        assertTrue(resourceId != 0)
        val parser = context.resources.getXml(resourceId)
        val exclusionsBySection = mutableMapOf<String, MutableSet<String>>()
        var section: String? = null
        var event = parser.next()
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "cloud-backup", "device-transfer" -> {
                        section = parser.name
                        exclusionsBySection.getOrPut(section) { mutableSetOf() }
                    }
                    "exclude" -> exclusionsBySection.getOrPut(section.orEmpty()) { mutableSetOf() } +=
                        "${parser.getAttributeValue(null, "domain")}:${parser.getAttributeValue(null, "path")}"
                }
            } else if (event == org.xmlpull.v1.XmlPullParser.END_TAG &&
                (parser.name == "cloud-backup" || parser.name == "device-transfer")
            ) {
                section = null
            }
            event = parser.next()
        }
        val domains = listOf("root", "file", "database", "sharedpref", "external", "device_root", "device_file", "device_database", "device_sharedpref")
        for (name in listOf("cloud-backup", "device-transfer")) {
            val exclusions = exclusionsBySection[name].orEmpty()
            for (domain in domains) assertTrue("missing $name $domain", "$domain:." in exclusions)
        }
    }
}

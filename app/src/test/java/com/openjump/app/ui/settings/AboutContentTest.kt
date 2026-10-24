package com.openjump.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutContentTest {
    @Test
    fun `canonical external destinations are exact and allowed`() {
        assertEquals(
            "https://dust-pot-4a1.notion.site/OpenJump-Privacy-Policy-3d8cbe14085580569817ef2d5432bc07?pvs=74",
            PUBLIC_PRIVACY_URL,
        )
        assertTrue(isAllowedExternalUrl(SOURCE_URL))
        assertTrue(isAllowedExternalUrl(ISSUES_URL))
        assertTrue(isAllowedExternalUrl(PUBLIC_PRIVACY_URL))
        assertEquals("https://buymeacoffee.com/cortig00", COFFEE_URL)
        assertTrue(isAllowedExternalUrl(COFFEE_URL))
        assertFalse(isAllowedExternalUrl("http://buymeacoffee.com/cortig00"))
        assertFalse(isAllowedExternalUrl("https://buymeacoffee.com.evil.example/cortig00"))
        assertFalse(isAllowedExternalUrl("https://buymeacoffee.com/another-creator"))
        assertFalse(isAllowedExternalUrl("$COFFEE_URL?redirect=https://evil.example"))
        assertFalse(isAllowedExternalUrl("https://cortig00.github.io/OpenJump/privacy/"))
        assertFalse(isAllowedExternalUrl("http://github.com/cortig00/OpenJump"))
        assertFalse(isAllowedExternalUrl("https://github.com.evil.example/cortig00/OpenJump"))
        assertFalse(isAllowedExternalUrl("https://github.com/cortig00/OpenJump?redirect=https://evil.example"))
        assertFalse(isAllowedExternalUrl("https://example.com"))
    }

    @Test
    fun `problem report mailto encodes UTF-8 template and only minimal diagnostics`() {
        val diagnostics = formatDiagnostics(
            DiagnosticMetadata("1.2.0-beta.3", 6, "15", 35, "Google", "Pixel 6"),
            DiagnosticLabels(device = "Dispositivo"),
        )
        val body = formatProblemReportBody(
            problemLabel = "Describe el problema",
            stepsLabel = "Pasos para reproducirlo",
            expectedLabel = "Resultado esperado",
            actualLabel = "Resultado real",
            diagnosticsLabel = "Datos técnicos básicos",
            diagnostics = diagnostics,
        )
        val uri = encodeProblemReportUri("Informe de problema: salto ágil", body)
        assertTrue(uri.startsWith("mailto:$PROBLEM_REPORT_EMAIL?subject="))
        val query = uri.substringAfter('?').split('&').associate { part ->
            val (key, value) = part.split('=', limit = 2)
            key to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }
        assertEquals("Informe de problema: salto ágil", query["subject"])
        assertEquals(body, query["body"])
        listOf(
            "Describe el problema:", "Pasos para reproducirlo:", "Resultado esperado:",
            "Resultado real:", "Datos técnicos básicos:", diagnostics,
        ).forEach { assertTrue(body.contains(it)) }
        listOf("athlete", "session", "video", "uri", "path", "database", "settings").forEach {
            assertFalse(body.lowercase().contains(it))
        }
    }

    @Test
    fun `version formatter preserves installed values`() {
        assertEquals("1.1.0-beta.1 (2)", formatVersion(InstalledAppMetadata("1.1.0-beta.1", 2)))
    }

    @Test
    fun `diagnostics contain only approved device and package fields`() {
        val result = formatDiagnostics(
            DiagnosticMetadata(
                appVersionName = "1.1.0-beta.1",
                appVersionCode = 2,
                androidRelease = "15",
                androidApi = 35,
                manufacturer = "Google",
                model = "Pixel 6",
            ),
        )
        assertEquals("OpenJump 1.1.0-beta.1 (2)\nAndroid 15 (API 35)\nDevice: Google Pixel 6", result)
        listOf(
            "android_id", "advertising", "serial", "imei", "mac", "ip", "fingerprint",
            "account", "athlete", "session", "database", "path", "uri", "settings",
        ).forEach { forbidden -> assertFalse(result.lowercase().contains(forbidden)) }
    }

    @Test
    fun `diagnostics labels can be localized without changing the payload fields`() {
        val result = formatDiagnostics(
            DiagnosticMetadata("v", 7, "14", 34, "Acme", "Model"),
            DiagnosticLabels(app = "OpenJump", android = "Android", device = "Appareil", api = "API"),
        )
        assertEquals("OpenJump v (7)\nAndroid 14 (API 34)\nAppareil: Acme Model", result)
    }
}

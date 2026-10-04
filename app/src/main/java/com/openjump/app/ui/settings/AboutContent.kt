package com.openjump.app.ui.settings

import java.net.URI
import java.net.URLEncoder

/** Canonical, fixed destinations. User-provided URLs must never reach the launcher. */
internal const val SOURCE_URL = "https://github.com/cortig00/OpenJump"
internal const val ISSUES_URL = "https://github.com/cortig00/OpenJump/issues"
internal const val PUBLIC_PRIVACY_URL = "https://dust-pot-4a1.notion.site/OpenJump-Privacy-Policy-3d8cbe14085580569817ef2d5432bc07?pvs=74"

internal const val COFFEE_URL = "https://buymeacoffee.com/cortig00"
internal const val PROBLEM_REPORT_EMAIL = "openjump.app@gmail.com"

private val approvedExternalUrls = setOf(SOURCE_URL, ISSUES_URL, PUBLIC_PRIVACY_URL, COFFEE_URL)

/** Returns true only for the exact, HTTPS destinations shipped by the app. */
internal fun isAllowedExternalUrl(url: String): Boolean {
    if (url !in approvedExternalUrls) return false
    val parsed = runCatching { URI(url) }.getOrNull() ?: return false
    return parsed.scheme == "https" && parsed.userInfo == null && parsed.port == -1 &&
        parsed.host in setOf("github.com", "dust-pot-4a1.notion.site", "buymeacoffee.com")
}

data class InstalledAppMetadata(
    val versionName: String,
    val versionCode: Long,
)

internal fun formatVersion(metadata: InstalledAppMetadata): String =
    "${metadata.versionName} (${metadata.versionCode})"

data class DiagnosticMetadata(
    val appVersionName: String,
    val appVersionCode: Long,
    val androidRelease: String,
    val androidApi: Int,
    val manufacturer: String,
    val model: String,
)

data class DiagnosticLabels(
    val app: String = "OpenJump",
    val android: String = "Android",
    val device: String = "Device",
    val api: String = "API",
)

/** Encodes an editable draft for the fixed support recipient; never sends mail. */
internal fun encodeProblemReportUri(subject: String, body: String): String =
    "mailto:$PROBLEM_REPORT_EMAIL?subject=${uriEncode(subject)}&body=${uriEncode(body)}"

private fun uriEncode(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun formatProblemReportBody(
    problemLabel: String,
    stepsLabel: String,
    expectedLabel: String,
    actualLabel: String,
    diagnosticsLabel: String,
    diagnostics: String,
): String = buildString {
    append(problemLabel)
    append(":\r\n\r\n")
    append(stepsLabel)
    append(":\r\n\r\n")
    append(expectedLabel)
    append(":\r\n\r\n")
    append(actualLabel)
    append(":\r\n\r\n")
    append(diagnosticsLabel)
    append(":\r\n")
    append(diagnostics)
}

/** Basic technical metadata only: no IDs, account/user data, paths, URIs or settings. */
internal fun formatDiagnostics(
    metadata: DiagnosticMetadata,
    labels: DiagnosticLabels = DiagnosticLabels(),
): String {
    val manufacturer = metadata.manufacturer.trim().ifEmpty { "Unknown" }
    val model = metadata.model.trim().ifEmpty { "Unknown" }
    return buildString {
        append(labels.app)
        append(' ')
        append(formatVersion(InstalledAppMetadata(metadata.appVersionName, metadata.appVersionCode)))
        append('\n')
        append(labels.android)
        append(' ')
        append(metadata.androidRelease.trim().ifEmpty { "Unknown" })
        append(" (")
        append(labels.api)
        append(' ')
        append(metadata.androidApi)
        append(')')
        append('\n')
        append(labels.device)
        append(": ")
        append(manufacturer)
        append(' ')
        append(model)
    }
}

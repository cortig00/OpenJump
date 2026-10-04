plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

val pinnedKotlinGradlePluginVersion = libs.versions.kotlin.get()
val kaptSecurityMessage = """
    CVE-2026-53914: applying Kotlin KAPT is prohibited while this build pins Kotlin Gradle Plugin $pinnedKotlinGradlePluginVersion.
    The upstream fix restricts deserialization of KAPT incremental-cache classes; OpenJump currently uses KSP, not KAPT.
    Remove this prohibition only after upgrading to a Kotlin Gradle Plugin version containing the fix (2.4.20-Beta1 or later)
    and validating compatible Kotlin, KSP, AGP, and build/test configurations.
    See https://github.com/advisories/GHSA-r937-wjx7-w2jp and https://github.com/JetBrains/kotlin/commit/bf51df665b458fda7c3eaf436c4d88dc119d7ec6.
""".trimIndent()

allprojects {
    listOf("org.jetbrains.kotlin.kapt", "kotlin-kapt").forEach { kaptPluginId ->
        pluginManager.withPlugin(kaptPluginId) {
            throw GradleException(kaptSecurityMessage)
        }
    }
}

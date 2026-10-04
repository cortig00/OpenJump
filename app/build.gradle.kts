plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val uploadSigningProperties = listOf(
    "OPENJUMP_UPLOAD_STORE_FILE",
    "OPENJUMP_UPLOAD_STORE_PASSWORD",
    "OPENJUMP_UPLOAD_KEY_ALIAS",
    "OPENJUMP_UPLOAD_KEY_PASSWORD",
).associateWith { providers.gradleProperty(it).orNull }

require(uploadSigningProperties.values.count { it != null } in setOf(0, uploadSigningProperties.size)) {
    "Provide all OPENJUMP_UPLOAD_* Gradle properties or none of them. See README.md."
}
val uploadSigningConfigured = uploadSigningProperties.values.all { it != null }

val versionFile = rootProject.file("version.properties")
require(versionFile.isFile) {
    "Missing version.properties at ${versionFile.absolutePath}."
}
val versionValues = linkedMapOf<String, String>()
versionFile.readLines().forEachIndexed { index, rawLine ->
    val line = rawLine.trimEnd('\r')
    if (line.isBlank() || line.trimStart().startsWith("#") || line.trimStart().startsWith("!")) {
        return@forEachIndexed
    }
    val separator = line.indexOf('=')
    require(separator > 0 && line.substring(0, separator).matches(Regex("[A-Z][A-Z0-9_]*"))) {
        "${versionFile.name}:${index + 1}: expected KEY=VALUE."
    }
    val key = line.substring(0, separator)
    require(key in setOf("VERSION_CODE", "VERSION_NAME")) {
        "${versionFile.name}:${index + 1}: unexpected key $key."
    }
    require(key !in versionValues) {
        "${versionFile.name}:${index + 1}: repeated key $key."
    }
    val value = line.substring(separator + 1)
    require(value.isNotEmpty() && value == value.trim()) {
        "${versionFile.name}:${index + 1}: empty or padded value for $key."
    }
    versionValues[key] = value
}
require(versionValues.keys == setOf("VERSION_CODE", "VERSION_NAME")) {
    "${versionFile.name} must contain exactly VERSION_CODE and VERSION_NAME."
}
val configuredVersionCode = versionValues.getValue("VERSION_CODE")
require(configuredVersionCode.matches(Regex("[1-9][0-9]*"))) {
    "${versionFile.name}: VERSION_CODE must be a positive integer."
}
val versionCodeValue = requireNotNull(configuredVersionCode.toLongOrNull()) {
    "${versionFile.name}: VERSION_CODE must be an integer."
}
require(versionCodeValue <= 2_100_000_000L) {
    "${versionFile.name}: VERSION_CODE must be between 1 and 2100000000."
}
val versionNameValue = versionValues.getValue("VERSION_NAME")
require(
    versionNameValue == "1.0" ||
        Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-beta\\.[1-9][0-9]*)?").matches(versionNameValue),
) {
    "${versionFile.name}: VERSION_NAME must be public 1.0, stable MAJOR.MINOR.PATCH or beta MAJOR.MINOR.PATCH-beta.N."
}

android {
    namespace = "com.openjump.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.openjump.app"
        minSdk = 26
        targetSdk = 36
        versionCode = versionCodeValue.toInt()
        versionName = versionNameValue
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("es", "en", "fr", "de", "pt-rBR", "pt-rPT", "it", "tr")
    }

    signingConfigs {
        if (uploadSigningConfigured) {
            create("upload") {
                storeFile = file(requireNotNull(uploadSigningProperties["OPENJUMP_UPLOAD_STORE_FILE"]))
                storePassword = requireNotNull(uploadSigningProperties["OPENJUMP_UPLOAD_STORE_PASSWORD"])
                keyAlias = requireNotNull(uploadSigningProperties["OPENJUMP_UPLOAD_KEY_ALIAS"])
                keyPassword = requireNotNull(uploadSigningProperties["OPENJUMP_UPLOAD_KEY_PASSWORD"])
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.transformer) {
        // Transformer 1.9.x pulls Lottie only for its optional LottieOverlay.
        exclude(group = "com.airbnb.android", module = "lottie")
    }

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.boofcv.feature)

}

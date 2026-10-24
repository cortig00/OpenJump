plugins {
    // Sin version: el plugin Kotlin (2.1.20) ya esta en el classpath via el catalogo raiz
    // (libs.plugins.kotlin-android); declarar version aqui conflicta con ese classpath.
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Alinear tareas Java con jvmTarget 17 (el daemon corre en JDK 21 del host).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // JUnit 4.13.2 from the version catalog (libs.junit = junit:junit:4.13.2).
    testImplementation(libs.junit)
}

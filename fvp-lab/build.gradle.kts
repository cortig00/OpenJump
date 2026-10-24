plugins {
    kotlin("jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Alinear tareas Java con jvmTarget 17 (igual que :fvp; el daemon corre en JDK 21 del host).
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":fvp"))
    // Unica dependencia externa permitida ademas de :fvp (contrato de tarea).
    // Se usa SOLO Json.parseToJsonElement (sin plugin de serializacion, sin @Serializable).
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(project(":fvp"))
}

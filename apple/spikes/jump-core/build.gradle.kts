import org.gradle.api.tasks.Sync

plugins {
    kotlin("multiplatform") version "2.1.20"
}

val syncSharedJumpMath by tasks.registering(Sync::class) {
    from(rootProject.file("../../../app/src/main/java/com/openjump/app/math/JumpMath.kt"))
    into(layout.buildDirectory.dir("generated/sharedJumpMath/commonMain"))
}

kotlin {
    jvm { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain {
            kotlin.srcDir(syncSharedJumpMath.map { it.destinationDir })
            dependencies { }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation(kotlin("test-junit")) }
    }

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "JumpCoreSpike"
            isStatic = true
        }
    }
}

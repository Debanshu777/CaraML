import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    compilerOptions {
        optIn.add("io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi")
    }
    android {
        namespace = "io.github.debanshu777.caraml.storage"
        compileSdk = 37
        minSdk = 28
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":core-api"))
            api(libs.okio)
            api(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

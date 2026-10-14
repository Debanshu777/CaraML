import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    compilerOptions {
        optIn.add("io.github.debanshu777.caraml.core.api.ExperimentalCaraMLApi")
    }
    android {
        namespace = "io.github.debanshu777.caraml.discovery"
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
            api(project.dependencies.platform(libs.ktor))
            api(libs.ktor.client.core)
            // The compatibility Hub facade creates platform clients and decodes legacy DTOs.
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        jvmMain.dependencies { implementation(libs.ktor.client.cio) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.okio)
            implementation(project.dependencies.platform(libs.ktor))
            implementation(project(":storage"))
            implementation(libs.okio.fakefilesystem)
            implementation("io.ktor:ktor-client-mock")
        }
    }
}

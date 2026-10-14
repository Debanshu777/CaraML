import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    android {
        namespace = "com.debanshu777.caraml.sdk.runtime"
        compileSdk = 37
        minSdk = 28
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest {}
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutinesTest)
        }
    }
}

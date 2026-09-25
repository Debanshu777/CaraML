import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    android {
        namespace = "com.debanshu777.huggingfacemanager"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {}
    }
    val xcfName = "huggingFaceManagerKit"

    iosX64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    jvm()

    sourceSets {
        commonMain {
            dependencies {
                implementation(project.dependencies.platform(libs.ktor))
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.okio)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutinesTest)
                implementation(project.dependencies.platform(libs.ktor))
                implementation("io.ktor:ktor-client-mock")
                implementation(libs.okio.fakefilesystem)
            }
        }

        androidMain {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(project(":nativeEngine"))
            }
        }

        nativeMain {
            dependencies {
                implementation(libs.ktor.client.darwin)
            }
        }

        jvmMain {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
    }
}

val artifactFsDesktopPlatform = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    System.getProperty("os.name").lowercase().contains("win") -> "windows"
    else -> throw GradleException("Unsupported desktop platform for secure artifact storage")
}
val artifactFsDesktopDir = project(":nativeEngine").layout.buildDirectory
    .dir("llama-runner-desktop/$artifactFsDesktopPlatform")

tasks.matching { it.name == "jvmTest" }.configureEach {
    dependsOn(":nativeEngine:compileArtifactFsDesktop")
    (this as Test).systemProperty("caraml.native.lib.dir", artifactFsDesktopDir.get().asFile.absolutePath)
}

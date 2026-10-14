import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

val nativeParityEnabled = providers.environmentVariable("CARAML_NATIVE_PARITY")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val nativeParityPlatform = when {
    System.getProperty("os.name").contains("mac", ignoreCase = true) -> "macos"
    System.getProperty("os.name").contains("linux", ignoreCase = true) -> "linux"
    System.getProperty("os.name").contains("win", ignoreCase = true) -> "windows"
    else -> "unsupported"
}
val nativeProfileEngines = providers.gradleProperty("caramlNativeEngines")
    .orElse("llama,diffusion").get().split(',').map(String::trim).filter(String::isNotEmpty).distinct().sorted()
val nativeProfileDefaultBackend = if (System.getProperty("os.name").contains("mac", ignoreCase = true)) "metal" else "cpu"
val nativeProfileBackend = providers.gradleProperty("caramlDesktopBackend").orElse(nativeProfileDefaultBackend).get()
val nativeProfileSuffix = if (nativeProfileEngines == listOf("diffusion", "llama") && nativeProfileBackend == nativeProfileDefaultBackend) "" else
    "/profiles/${nativeProfileEngines.joinToString("-")}-$nativeProfileBackend"
val nativeParityLibraryDir = project(":nativeEngine").layout.buildDirectory
    .dir("llama-runner-desktop/$nativeParityPlatform$nativeProfileSuffix")
val nativeParityFixtureDir = project(":nativeEngine").layout.buildDirectory
    .dir("native-preflight-fixtures")

tasks.matching { it.name == "jvmTest" }.configureEach {
    if (nativeParityEnabled.get()) {
        require(nativeParityPlatform != "unsupported") {
            "CARAML_NATIVE_PARITY is unsupported on this desktop platform"
        }
        require("diffusion" in nativeProfileEngines) {
            "Diffusion native parity requires diffusion in caramlNativeEngines"
        }
        dependsOn(
            ":nativeEngine:verifyNativePreflightFixtures",
            ":nativeEngine:compileLlamaRunnerDesktop",
        )
        (this as Test).apply {
            systemProperty("java.library.path", nativeParityLibraryDir.get().asFile.absolutePath)
            systemProperty("caraml.native.lib.dir", nativeParityLibraryDir.get().asFile.absolutePath)
            systemProperty("caraml.native.fixture.dir", nativeParityFixtureDir.get().asFile.absolutePath)
        }
    }
}

kotlin {
    android {
        namespace = "com.debanshu777.caraml.sdk.plugin.diffusion"
        compileSdk = 37
        minSdk = 28
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-proguard-rules.pro")
            }
        }
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {}
    }

    jvm()

    val xcfName = "diffusionRunnerKit"

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

    val nativeEngineProject = project(":nativeEngine")
    val hostOsName = System.getProperty("os.name").lowercase()
    val isMacHost = hostOsName.contains("mac")

    if (isMacHost) {
        listOf(
            Triple(iosArm64(), "iPhoneOS", "iosArm64"),
            Triple(iosSimulatorArm64(), "iPhoneSimulator", "iosSimulatorArm64")
        ).forEach { (arch, sdkName, kotlinArchName) ->
            val cmakeBuildDir = nativeEngineProject.layout.buildDirectory
                .dir("llama-runner-ios/$sdkName/$kotlinArchName")
                .get()
                .asFile
            val libPath = cmakeBuildDir.absolutePath

            val mergeTaskName =
                "mergeLlamaRunnerStatic${kotlinArchName.replaceFirstChar { it.uppercase() }}"
            val mergeTaskPath = ":nativeEngine:$mergeTaskName"

            arch.compilations.getByName("main").cinterops {
                create("diffusionRunner") {
                    defFile("src/iosMain/cpp/diffusion_runner.def")
                    packageName("com.debanshu777.caraml.sdk.plugin.diffusion.bridge.cpp")
                    compilerOpts("-I${projectDir}/src/iosMain/cpp")
                    extraOpts("-libraryPath", libPath)
                    tasks.named(interopProcessingTaskName).configure {
                        dependsOn(mergeTaskPath)
                    }
                }
            }

            val merged = "$libPath/libllama_runner_merged.a"
            listOf("DEBUG", "RELEASE").forEach { buildType ->
                arch.binaries.getFramework(buildType).apply {
                    baseName = xcfName
                    isStatic = true
                    linkerOpts(
                        "-L$libPath",
                        "-Wl,-force_load", merged,
                        "-framework", "Metal",
                        "-framework", "Accelerate",
                        "-framework", "Foundation",
                        "-Wl,-no_implicit_dylibs"
                    )
                }
            }
        }
    } else {
        logger.lifecycle("Skipping iOS native merge paths (host OS is not macOS: $hostOsName)")
        listOf(iosArm64(), iosSimulatorArm64()).forEach { arch ->
            arch.compilations.getByName("main").cinterops {
                create("diffusionRunner") {
                    defFile("src/iosMain/cpp/diffusion_runner.def")
                    packageName("com.debanshu777.caraml.sdk.plugin.diffusion.bridge.cpp")
                    compilerOpts("-I${projectDir}/src/iosMain/cpp")
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(project(":core-api"))
                implementation(project(":native-runtime"))
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutinesCore)
            }
        }

        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutinesTest)
            }
        }

        androidMain {
            dependencies {
                implementation(project(":nativeEngine"))
            }
        }
    }
}

tasks.matching { it.name.startsWith("cinteropDiffusionRunner") }.configureEach {
    inputs.file(layout.projectDirectory.file("src/iosMain/cpp/diffusion_runner.h"))
}

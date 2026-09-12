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
val nativeParityLibraryDir = project(":nativeEngine").layout.buildDirectory
    .dir("llama-runner-desktop/$nativeParityPlatform")
val nativeParityFixtureDir = project(":nativeEngine").layout.buildDirectory
    .dir("native-preflight-fixtures")

tasks.matching { it.name == "jvmTest" }.configureEach {
    if (nativeParityEnabled.get()) {
        require(nativeParityPlatform != "unsupported") {
            "CARAML_NATIVE_PARITY is unsupported on this desktop platform"
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

val minIos = "17.2"

kotlin {
    android {
        namespace = "com.debanshu777.runner"
        compileSdk = 36
        minSdk = 24
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {}
    }

    jvm()

    val xcfName = "runnerKit"

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
            val mergeTask = nativeEngineProject.tasks.named(mergeTaskName)

            arch.compilations.getByName("main").cinterops {
                create("llamaRunner") {
                    defFile("src/iosMain/cpp/llama_runner.def")
                    packageName("com.debanshu777.runner.cpp")
                    compilerOpts("-I${projectDir}/src/iosMain/cpp")
                    extraOpts("-libraryPath", libPath)
                    tasks.named(interopProcessingTaskName).configure {
                        dependsOn(mergeTask)
                    }
                }
            }

            val merged = "$libPath/libllama_runner_merged.a"

            arch.binaries.getFramework("DEBUG").apply {
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
            arch.binaries.getFramework("RELEASE").apply {
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
    } else {
        logger.lifecycle("Skipping iOS native merge paths (host OS is not macOS: $hostOsName)")
        listOf(iosArm64(), iosSimulatorArm64()).forEach { arch ->
            arch.compilations.getByName("main").cinterops {
                create("llamaRunner") {
                    defFile("src/iosMain/cpp/llama_runner.def")
                    packageName("com.debanshu777.runner.native")
                    compilerOpts("-I${projectDir}/src/iosMain/cpp")
                }
            }
        }
    }

    sourceSets {
        commonMain {
            dependencies {
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

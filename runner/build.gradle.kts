plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

val minIos = "17.2"

kotlin {
    androidTarget {
        publishLibraryVariants("release", "debug")
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
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            }
        }

        androidMain {
            dependencies {
                implementation(project(":nativeEngine"))
            }
        }

        jvmMain {
            dependencies {
                implementation(project(":nativeEngine"))
            }
        }
    }
}

android {
    namespace = "com.debanshu777.runner"
    compileSdk = 36

    defaultConfig {
        minSdk = 24

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

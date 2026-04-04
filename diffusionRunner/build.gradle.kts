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

    println("diffusionRunner: hostOsName = $hostOsName, isMacHost = $isMacHost")

    afterEvaluate {
        if (isMacHost) {
            println("diffusionRunner: Looking for merge tasks in nativeEngine...")
            val availableTasks = nativeEngineProject.tasks.names.filter { it.contains("merge") }
            println("diffusionRunner: Available merge tasks in nativeEngine: $availableTasks")

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
                println("diffusionRunner: Looking for task: $mergeTaskName")

                // Use findByName to avoid exception if task doesn't exist
                val mergeTask = nativeEngineProject.tasks.findByName(mergeTaskName)
                if (mergeTask == null) {
                    logger.warn("diffusionRunner: Task $mergeTaskName not found in :nativeEngine - iOS native libraries will not be available")
                }

                arch.compilations.getByName("main").cinterops {
                    create("diffusionRunner") {
                        defFile("src/iosMain/cpp/diffusion_runner.def")
                        packageName("com.debanshu777.diffusionrunner.cpp")
                        compilerOpts("-I${projectDir}/src/iosMain/cpp")
                        extraOpts("-libraryPath", libPath)
                        if (mergeTask != null) {
                            tasks.named(interopProcessingTaskName).configure {
                                dependsOn(mergeTask)
                            }
                        }
                    }
                }

                val merged = "$libPath/libllama_runner_merged.a"

                if (mergeTask != null) {
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
                } else {
                    logger.warn("diffusionRunner: Skipping iOS framework configuration for $kotlinArchName - merge task not available")
                }
            }
        } else {
            logger.lifecycle("Skipping iOS native merge paths (host OS is not macOS: $hostOsName)")
            listOf(iosArm64(), iosSimulatorArm64()).forEach { arch ->
                arch.compilations.getByName("main").cinterops {
                    create("diffusionRunner") {
                        defFile("src/iosMain/cpp/diffusion_runner.def")
                        packageName("com.debanshu777.diffusionrunner.cpp")
                        compilerOpts("-I${projectDir}/src/iosMain/cpp")
                    }
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
    namespace = "com.debanshu777.diffusionrunner"
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
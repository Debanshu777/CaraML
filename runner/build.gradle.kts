plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

val minIos = "17.2"

fun Project.findTool(name: String): String {
    findProperty("${name.uppercase()}_PATH")?.toString()?.let { path ->
        val f = file(path)
        if (f.exists() && f.canExecute()) return f.absolutePath
    }
    System.getenv("${name.uppercase()}_PATH")?.let { path ->
        val f = file(path)
        if (f.exists() && f.canExecute()) return f.absolutePath
    }
    val candidates = listOf(
        "/opt/homebrew/bin/$name",
        "/usr/local/bin/$name",
        "/usr/bin/$name"
    )
    for (p in candidates) {
        val f = file(p)
        if (f.exists() && f.canExecute()) return f.absolutePath
    }
    throw GradleException(
        "Cannot find required tool '$name'. Install it or set ${name.uppercase()}_PATH=/full/path/to/$name"
    )
}

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

    val hostOsName = System.getProperty("os.name").lowercase()
    val isMacHost = hostOsName.contains("mac")

    if (isMacHost) {
        val cmakePath = findTool("cmake")
        val libtoolPath = findTool("libtool")

        listOf(
            Triple(iosArm64(), "arm64", "iPhoneOS"),
            Triple(iosSimulatorArm64(), "arm64", "iPhoneSimulator")
        ).forEach { (arch, archName, sdkName) ->
            val cmakeBuildDir = layout.buildDirectory
                .dir("llama-runner-ios/$sdkName/${arch.name}")
                .get()
                .asFile
            val buildTaskName = "buildLlamaRunnerCMake${arch.name.replaceFirstChar { it.uppercase() }}"

            tasks.register(buildTaskName, Exec::class) {
                doFirst {
                    val sourceDir = projectDir.resolve("src/iosMain/cpp")
                    val sdk = when (sdkName) {
                        "iPhoneSimulator" -> "iphonesimulator"
                        "iPhoneOS" -> "iphoneos"
                        else -> "macosx"
                    }
                    val sdkPathProvider = providers.exec {
                        commandLine("xcrun", "--sdk", sdk, "--show-sdk-path")
                    }.standardOutput.asText.map { it.trim() }
                    val systemName = if (sdk == "macosx") "Darwin" else "iOS"
                    cmakeBuildDir.mkdirs()
                    environment("PATH", "/opt/homebrew/bin:" + System.getenv("PATH"))

                    commandLine(
                        cmakePath,
                        "-S", sourceDir.absolutePath,
                        "-B", cmakeBuildDir.absolutePath,
                        "-DCMAKE_SYSTEM_NAME=$systemName",
                        "-DCMAKE_OSX_ARCHITECTURES=$archName",
                        "-DCMAKE_OSX_SYSROOT=${sdkPathProvider.get()}",
                        "-DCMAKE_OSX_DEPLOYMENT_TARGET=$minIos",
                        "-DCMAKE_BUILD_TYPE=Release",
                        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON",
                        "-DBUILD_SHARED_LIBS=OFF",
                        "-DGGML_OPENMP=OFF",
                        "-DLLAMA_CURL=OFF"
                    )
                }
            }

            val compileTask = tasks.register(
                "compileLlamaRunnerCMake${arch.name.replaceFirstChar { it.uppercase() }}",
                Exec::class
            ) {
                dependsOn(buildTaskName)
                environment("PATH", "/opt/homebrew/bin:" + System.getenv("PATH"))
                commandLine(
                    cmakePath,
                    "--build", cmakeBuildDir.absolutePath,
                    "--target", "llama_runner",
                    "--verbose"
                )
            }

            val libPath = cmakeBuildDir.absolutePath

            val mergeTask = tasks.register(
                "mergeLlamaRunnerStatic${arch.name.replaceFirstChar { it.uppercase() }}",
                Exec::class
            ) {
                dependsOn(compileTask)

                doFirst {
                    val llamaBuild = file("$libPath/llama-build")
                    val ggmlCpuLibs = fileTree(llamaBuild) {
                        include("**/libggml-cpu*.a")
                        exclude("**/CMakeFiles/**")
                    }.files.map { file(it.absolutePath) }
                    val cppHttplibLibs = fileTree(llamaBuild) {
                        include("**/libcpp-httplib*.a", "**/libcpp_httplib*.a")
                        exclude("**/CMakeFiles/**")
                    }.files.map { file(it.absolutePath) }

                    val requiredLibs = listOf(
                        file("$libPath/libllama_runner.a"),
                        file("$llamaBuild/src/libllama.a"),
                        file("$llamaBuild/common/libcommon.a"),
                        file("$llamaBuild/ggml/src/libggml.a"),
                        file("$llamaBuild/ggml/src/libggml-base.a"),
                        file("$llamaBuild/ggml/src/ggml-blas/libggml-blas.a"),
                        file("$llamaBuild/ggml/src/ggml-metal/libggml-metal.a")
                    )
                    val libs = requiredLibs + cppHttplibLibs + ggmlCpuLibs
                    val missing = requiredLibs.filter { !it.exists() }
                    if (missing.isNotEmpty() || ggmlCpuLibs.isEmpty() || cppHttplibLibs.isEmpty()) {
                        val msg = buildString {
                            append("Missing static libraries for merge:\n")
                            missing.forEach { append("  - ${it.absolutePath}\n") }
                            if (ggmlCpuLibs.isEmpty()) {
                                append("  - ggml-cpu (no libggml-cpu*.a found under $llamaBuild)\n")
                            }
                            if (cppHttplibLibs.isEmpty()) {
                                append("  - cpp-httplib (no libcpp-httplib*.a found under $llamaBuild)\n")
                            }
                            append("\nEnsure CMake build completed successfully.")
                        }
                        throw GradleException(msg)
                    }

                    val args = mutableListOf(
                        libtoolPath, "-static",
                        "-o", "$libPath/libllama_runner_merged.a"
                    ) + libs.map { it.absolutePath }
                    commandLine(args)
                }
            }

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
        logger.lifecycle("Skipping iOS native build tasks (host OS is not macOS: $hostOsName)")
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

    // ---------- Desktop (JVM) JNI build for llama_runner (macOS/Linux/Windows) ----------
    val desktopPlatform = when {
        hostOsName.contains("mac") -> "macos"
        hostOsName.contains("linux") -> "linux"
        hostOsName.contains("win") -> "windows"
        else -> error("Unsupported desktop OS: $hostOsName")
    }

    val desktopJniBuildDir = layout.buildDirectory
        .dir("llama-runner-desktop/$desktopPlatform")
        .get()
        .asFile

    val desktopJniSourceDir = projectDir.resolve("src/jvmMain/cpp")
    val desktopCmakePath = findTool("cmake")

    val buildLlamaRunnerDesktop by tasks.registering(Exec::class) {
        group = "llama-native"
        description = "Configure CMake for desktop ($desktopPlatform) llama_runner"

        val javaHome = System.getenv("JAVA_HOME")
            ?: run {
                val jh = File(System.getProperty("java.home"))
                if (jh.name == "Home") jh.parentFile?.parentFile?.absolutePath else jh.absolutePath
            } ?: System.getProperty("java.home")
        environment("JAVA_HOME", javaHome)

        doFirst {
            if (!desktopJniSourceDir.resolve("CMakeLists.txt").exists()) {
                throw GradleException(
                    "Desktop JNI CMakeLists.txt not found at: ${desktopJniSourceDir.resolve("CMakeLists.txt").absolutePath}"
                )
            }
            desktopJniBuildDir.mkdirs()

            val args = mutableListOf(
                desktopCmakePath,
                "-S", desktopJniSourceDir.absolutePath,
                "-B", desktopJniBuildDir.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release"
            )
            if (desktopPlatform == "macos") {
                args += "-DCMAKE_SYSTEM_NAME=Darwin"
            }
            commandLine(args)
        }
    }

    val compileLlamaRunnerDesktop by tasks.registering(Exec::class) {
        group = "llama-native"
        description = "Build desktop ($desktopPlatform) llama_runner native library"
        dependsOn(buildLlamaRunnerDesktop)

        commandLine(
            desktopCmakePath,
            "--build", desktopJniBuildDir.absolutePath,
            "--config", "Release"
        )
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
                // Android-specific dependencies
            }
        }

        iosMain {
            dependencies {
                // iOS-specific dependencies
            }
        }

        jvmMain {
            dependencies {
                // JVM-specific dependencies
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

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_MESSAGE_LOG_LEVEL=DEBUG"
                arguments += "-DCMAKE_VERBOSE_MAKEFILE=ON"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
    }

    ndkVersion = "26.1.10909125"

    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.31.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

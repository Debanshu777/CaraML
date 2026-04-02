plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

/** Output layout: stable paths for composeApp / runner iOS configuration. */
object CaramlNativeLayout {
    const val IOS_SUBDIR = "llama-runner-ios"
    const val DESKTOP_SUBDIR = "llama-runner-desktop"
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

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlin.stdlib)
            }
        }
        androidMain { dependencies { } }
        jvmMain { dependencies { } }
    }
}

val hostOsName = System.getProperty("os.name").lowercase()
val isMacHost = hostOsName.contains("mac")

if (isMacHost) {
    val cmakePath = findTool("cmake")
    val libtoolPath = findTool("libtool")

    listOf(
        Triple("iosArm64", "arm64", "iPhoneOS"),
        Triple("iosSimulatorArm64", "arm64", "iPhoneSimulator")
    ).forEach { (kotlinArchName, archName, sdkName) ->
        val cmakeBuildDir = layout.buildDirectory
            .dir("${CaramlNativeLayout.IOS_SUBDIR}/$sdkName/$kotlinArchName")
            .get()
            .asFile
        val buildTaskName = "buildLlamaRunnerCMake${kotlinArchName.replaceFirstChar { it.uppercase() }}"

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
            "compileLlamaRunnerCMake${kotlinArchName.replaceFirstChar { it.uppercase() }}",
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

        tasks.register(
            "mergeLlamaRunnerStatic${kotlinArchName.replaceFirstChar { it.uppercase() }}",
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
    }
} else {
    logger.lifecycle("Skipping iOS native build tasks (host OS is not macOS: $hostOsName)")
}

val desktopPlatform = when {
    hostOsName.contains("mac") -> "macos"
    hostOsName.contains("linux") -> "linux"
    hostOsName.contains("win") -> "windows"
    else -> error("Unsupported desktop OS: $hostOsName")
}

val desktopJniBuildDir = layout.buildDirectory
    .dir("${CaramlNativeLayout.DESKTOP_SUBDIR}/$desktopPlatform")
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

android {
    namespace = "com.debanshu777.nativeengine"
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

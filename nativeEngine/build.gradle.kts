import groovy.json.JsonSlurper
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URI
import java.net.HttpURLConnection
import java.security.DigestInputStream
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.net.ssl.HttpsURLConnection

plugins {
    alias(libs.plugins.androidLibrary)
}

/** Output layout: stable paths for composeApp / runner iOS configuration. */
object CaramlNativeLayout {
    const val IOS_SUBDIR = "llama-runner-ios"
    const val DESKTOP_SUBDIR = "llama-runner-desktop"
}

val minIos = "17.2"

fun Project.findTool(name: String): String {
    require(name.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid tool name" }
    val windows = System.getProperty("os.name").contains("win", ignoreCase = true)
    fun validatedExecutable(rawPath: String): String? {
        val trimmed = rawPath.trim().removeSurrounding("\"")
        val candidate = File(trimmed)
        if (!candidate.isAbsolute || !candidate.isFile) return null
        if (windows && !candidate.name.endsWith(".exe", ignoreCase = true)) return null
        if (!windows && !candidate.canExecute()) return null
        return candidate.canonicalFile.absolutePath
    }

    val variable = name.uppercase()
    val explicitCandidates = listOfNotNull(
        findProperty("${variable}_PATH")?.toString(),
        System.getenv(variable),
        System.getenv("${variable}_PATH"),
    )
    explicitCandidates.firstNotNullOfOrNull(::validatedExecutable)?.let { return it }

    val executableNames = if (windows) listOf("$name.exe") else listOf(name)
    System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { it.removeSurrounding("\"") }
        .map(::File)
        .filter { it.isAbsolute && it.isDirectory }
        .flatMap { directory -> executableNames.asSequence().map(directory::resolve) }
        .mapNotNull { validatedExecutable(it.absolutePath) }
        .firstOrNull()
        ?.let { return it }

    throw GradleException(
        "Cannot find required tool '$name'. Install it, add it to PATH, or set ${variable}_PATH to an absolute executable path."
    )
}

val hostOsName = System.getProperty("os.name").lowercase()
val isMacHost = hostOsName.contains("mac")

// ----------------------------------------------------------------------------
// Build-owned llama.cpp patching
//
// Numbered llama.cpp patches are applied only to a Gradle-owned source copy.
// Every platform configure task depends on the single producer and receives the
// same source override, so parallel task graphs never edit the pinned submodule.
// ----------------------------------------------------------------------------

fun discoverLlamaPatches(): List<File> {
    val librariesRoot = rootProject.layout.projectDirectory.dir("libraries").asFile
    val patchesRoot = librariesRoot.resolve("patches/llama.cpp")
    if (!patchesRoot.isDirectory) return emptyList()
    return patchesRoot.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".patch") }
        .sortedBy { it.relativeTo(patchesRoot).path }
        .toList()
}

val pinnedLlamaSourceDir = rootProject.layout.projectDirectory.dir("libraries/llama.cpp").asFile
val patchedLlamaSourceDir = layout.buildDirectory.dir("patched-native-sources/llama.cpp")
val patchedLlamaSourcePath = patchedLlamaSourceDir.get().asFile.absolutePath
val pinnedLlamaCommit = providers.exec {
    commandLine("git", "-C", pinnedLlamaSourceDir.absolutePath, "rev-parse", "--short=7", "HEAD")
}.standardOutput.asText.map(String::trim)
val pinnedLlamaBuildNumber = providers.exec {
    commandLine("git", "-C", pinnedLlamaSourceDir.absolutePath, "rev-list", "--count", "HEAD")
}.standardOutput.asText.map(String::trim)

fun MutableList<String>.addPinnedLlamaSourceArguments() {
    add("-DLLAMA_SRC=$patchedLlamaSourcePath")
    add("-DLLAMA_BUILD_COMMIT=${pinnedLlamaCommit.get()}")
    add("-DLLAMA_BUILD_NUMBER=${pinnedLlamaBuildNumber.get()}")
}

val preparePatchedLlamaSource by tasks.registering(Sync::class) {
    group = "llama-native"
    description = "Create an isolated llama.cpp source tree and apply numbered patches"
    from(pinnedLlamaSourceDir) {
        exclude(".git", "**/.git/**")
    }
    into(patchedLlamaSourceDir)
    inputs.files(discoverLlamaPatches())

    // A patch update must start from the pinned source, never a previously
    // patched output. This directory is build-owned and safe to recreate.
    doFirst {
        delete(patchedLlamaSourceDir.get().asFile)
    }
    doLast {
        val patches = discoverLlamaPatches()
        if (patches.isEmpty()) {
            logger.lifecycle("preparePatchedLlamaSource: no llama.cpp patches found")
            return@doLast
        }
        val workingDir = patchedLlamaSourceDir.get().asFile
        val workingDirFromRepo = workingDir.relativeTo(rootProject.projectDir).invariantSeparatorsPath
        patches.forEach { patchFile ->
            val rel = patchFile.relativeTo(rootProject.projectDir).path
            val checkForward = providers.exec {
                workingDir(rootProject.projectDir)
                commandLine("git", "apply", "--check", "--directory=$workingDirFromRepo", patchFile.absolutePath)
                isIgnoreExitValue = true
            }.result.get()
            if (checkForward.exitValue == 0) {
                providers.exec {
                    workingDir(rootProject.projectDir)
                    commandLine("git", "apply", "--directory=$workingDirFromRepo", patchFile.absolutePath)
                }.result.get()
                logger.lifecycle("preparePatchedLlamaSource: applied $rel to isolated source")
                return@forEach
            }
            val checkReverse = providers.exec {
                workingDir(rootProject.projectDir)
                commandLine("git", "apply", "--check", "-R", "--directory=$workingDirFromRepo", patchFile.absolutePath)
                isIgnoreExitValue = true
            }.result.get()
            if (checkReverse.exitValue != 0) {
                throw GradleException(
                    "preparePatchedLlamaSource: $rel does not apply to the pinned llama.cpp source"
                )
            }
            logger.lifecycle("preparePatchedLlamaSource: $rel already present in pinned source — skipping")
        }
    }
}

/**
 * CMake needs a full JDK with [JAVA_HOME]/include/jni.h.
 * Gradle's java.home may already be correct, or may point at a nested JRE.
 */
fun resolveJavaHomeForJni(logger: org.gradle.api.logging.Logger): String {
    fun hasJniH(dir: File): Boolean = dir.resolve("include/jni.h").isFile

    System.getenv("JAVA_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let { env ->
        val root = File(env)
        if (hasJniH(root)) return root.absolutePath
        logger.lifecycle(
            "JAVA_HOME=$env does not contain include/jni.h; trying other JDK locations."
        )
    }

    System.getProperty("java.home")?.let { path ->
        val jh = File(path)
        if (hasJniH(jh)) return jh.absolutePath
        val parent = jh.parentFile
        if (parent != null && hasJniH(parent)) return parent.absolutePath
    }

    if (isMacHost) {
        try {
            val proc = ProcessBuilder("/usr/libexec/java_home")
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().use { it.readText() }.trim()
            if (proc.waitFor() == 0 && out.isNotEmpty()) {
                val macHome = File(out)
                if (hasJniH(macHome)) return macHome.absolutePath
            }
        } catch (_: Exception) {
            /* fall through */
        }
    }

    throw GradleException(
        buildString {
            appendLine("Could not find a JDK with JNI headers (include/jni.h) for :nativeEngine desktop CMake.")
            appendLine("Set JAVA_HOME to a full JDK root, e.g. on macOS:")
            appendLine("  export JAVA_HOME=\"\$(/usr/libexec/java_home -v 17)\"")
            appendLine("Then re-run the build.")
            appendLine("java.home=${System.getProperty("java.home")}")
            System.getenv("JAVA_HOME")?.let { appendLine("JAVA_HOME=$it (invalid or incomplete)") }
        }
    )
}

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
        val buildTaskName =
            "buildLlamaRunnerCMake${kotlinArchName.replaceFirstChar { it.uppercase() }}"

        tasks.register(buildTaskName, Exec::class) {
            dependsOn(preparePatchedLlamaSource)
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

                val args = mutableListOf(
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
                    "-DLLAMA_CURL=OFF",
                )
                args.addPinnedLlamaSourceArguments()
                commandLine(args)
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
                "--target", "diffusion_runner",
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

                val sdLibs = listOf(
                    file("$libPath/sd-build/libstable-diffusion.a")
                )
                val sdZipLibs = fileTree(file("$libPath/sd-build")) {
                    include("**/libzip.a")
                    exclude("**/CMakeFiles/**")
                }.files.map { file(it.absolutePath) }

                val requiredLibs = listOf(
                    file("$libPath/libllama_runner.a"),
                    file("$libPath/libdiffusion_runner.a"),
                    file("$llamaBuild/src/libllama.a"),
                    // Recent llama.cpp split common into two static libs
                    // (`libllama-common.a` and `libllama-common-base.a`) and
                    // renamed the old `libcommon.a`. Both are required to
                    // resolve symbols pulled in by llama_runner_core.cpp.
                    file("$llamaBuild/common/libllama-common.a"),
                    file("$llamaBuild/common/libllama-common-base.a"),
                    file("$llamaBuild/ggml/src/libggml.a"),
                    file("$llamaBuild/ggml/src/libggml-base.a"),
                    file("$llamaBuild/ggml/src/ggml-blas/libggml-blas.a"),
                    file("$llamaBuild/ggml/src/ggml-metal/libggml-metal.a")
                )
                val libs = requiredLibs + cppHttplibLibs + ggmlCpuLibs + sdLibs + sdZipLibs
                val missing = requiredLibs.filter { !it.exists() }
                if (missing.isNotEmpty() || ggmlCpuLibs.isEmpty() || cppHttplibLibs.isEmpty() || sdLibs.any { !it.exists() }) {
                    val msg = buildString {
                        append("Missing static libraries for merge:\n")
                        missing.forEach { append("  - ${it.absolutePath}\n") }
                        if (ggmlCpuLibs.isEmpty()) {
                            append("  - ggml-cpu (no libggml-cpu*.a found under $llamaBuild)\n")
                        }
                        if (cppHttplibLibs.isEmpty()) {
                            append("  - cpp-httplib (no libcpp-httplib*.a found under $llamaBuild)\n")
                        }
                        val missingSdLibs = sdLibs.filter { !it.exists() }
                        if (missingSdLibs.isNotEmpty()) {
                            append("  - stable-diffusion libraries:\n")
                            missingSdLibs.forEach { append("    - ${it.absolutePath}\n") }
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
    dependsOn(preparePatchedLlamaSource)

    val javaHome = resolveJavaHomeForJni(logger)
    environment("JAVA_HOME", javaHome)

    doFirst {
        if (desktopPlatform == "macos") {
            delete(
                desktopJniBuildDir.resolve(
                    "llama-build/ggml/src/ggml-metal/autogenerated/ggml-metal-embed.s",
                ),
            )
        }
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
            "-DCMAKE_BUILD_TYPE=Release",
        )
        args.addPinnedLlamaSourceArguments()
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

val compileLlamaRunnerHardeningTestDesktop by tasks.registering(Exec::class) {
    group = "verification"
    description = "Build deterministic native ownership and cleanup regressions"
    dependsOn(buildLlamaRunnerDesktop)
    commandLine(
        desktopCmakePath,
        "--build", desktopJniBuildDir.absolutePath,
        "--target", "llama_runner_hardening_test",
        "--config", "Release",
    )
}

val nativeHardeningTestBinary = desktopJniBuildDir.resolve(
    if (desktopPlatform == "windows") "Release/llama_runner_hardening_test.exe"
    else "llama_runner_hardening_test",
)

val testLlamaRunnerNativeDesktop by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run deterministic native ownership and cleanup regressions"
    dependsOn(compileLlamaRunnerHardeningTestDesktop)
    commandLine(nativeHardeningTestBinary.absolutePath)
}

val diffusionNativeTestTargets = listOf(
    "diffusion_bundled_components_test",
    "diffusion_max_vram_test",
    "diffusion_stream_layers_test",
    "diffusion_context_publication_test",
)

val compileDiffusionRunnerNativeTestsDesktop by tasks.registering(Exec::class) {
    group = "verification"
    description = "Build deterministic stable-diffusion preflight regressions"
    dependsOn(buildLlamaRunnerDesktop)
    commandLine(
        listOf(
            desktopCmakePath,
            "--build",
            desktopJniBuildDir.absolutePath,
            "--config",
            "Release",
            "--target",
        ) + diffusionNativeTestTargets,
    )
}

val testDiffusionRunnerNativeDesktop by tasks.registering(Exec::class) {
    group = "verification"
    description = "Run deterministic stable-diffusion preflight regressions"
    dependsOn(compileDiffusionRunnerNativeTestsDesktop)
    commandLine(
        findTool("ctest"),
        "--test-dir",
        desktopJniBuildDir.absolutePath,
        "--build-config",
        "Release",
        "--output-on-failure",
        "--tests-regex",
        "^diffusion_(bundled_components|max_vram|stream_layers|context_publication)_test$",
    )
}

val compileArtifactFsDesktop by tasks.registering(Exec::class) {
    group = "llama-native"
    description = "Build the bounded secure artifact filesystem JNI library for desktop ($desktopPlatform)"
    dependsOn(buildLlamaRunnerDesktop)
    commandLine(
        desktopCmakePath,
        "--build", desktopJniBuildDir.absolutePath,
        "--target", "artifact_fs",
        "--config", "Release",
    )
}

val artifactFsDesktopLibraryName = when (desktopPlatform) {
    "macos" -> "libartifact_fs.dylib"
    "linux" -> "libartifact_fs.so"
    "windows" -> "artifact_fs.dll"
    else -> throw GradleException("Unsupported desktop platform: $desktopPlatform")
}
val artifactFsDesktopLibrary = layout.buildDirectory.file(
    "${CaramlNativeLayout.DESKTOP_SUBDIR}/$desktopPlatform/$artifactFsDesktopLibraryName",
)

val verifyArtifactFsDesktopLibrary by tasks.registering {
    group = "verification"
    description = "Build and assert the configuration-independent artifact_fs desktop runtime path"
    dependsOn(compileArtifactFsDesktop)
    inputs.file(artifactFsDesktopLibrary)
    doLast {
        val runtime = artifactFsDesktopLibrary.get().asFile
        if (!runtime.isFile || runtime.length() <= 0L) {
            throw GradleException("artifact_fs desktop runtime was not produced at the stable packaging path")
        }
    }
}

// ----------------------------------------------------------------------------
// License-reviewed, digest-addressed native parity fixtures.
//
// The repository-owned manifest is parsed with an exact schema. External
// redirects are followed manually through a fixed HTTPS host allowlist, every
// response is streamed under its declared cap, and bytes are published only
// after SHA-256 verification. Model metadata can never supply a destination.
// ----------------------------------------------------------------------------

val nativePreflightFixtureManifest = layout.projectDirectory.file(
    "src/testFixtures/native-preflight-fixtures.json",
)
val nativePreflightFixtureDirectory = layout.buildDirectory.dir("native-preflight-fixtures")
val nativeFixtureGenerators = listOf(
    layout.projectDirectory.file("src/testFixtures/generators/corrupt-llama-v1.gguf.txt"),
    layout.projectDirectory.file("src/testFixtures/generators/corrupt-diffusion-v1.safetensors.txt"),
)

fun Any?.requiredJsonObject(label: String): Map<String, Any?> {
    val raw = this as? Map<*, *> ?: throw GradleException("$label must be an object")
    return raw.entries.associate { (key, value) ->
        val stringKey = key as? String ?: throw GradleException("$label contains a non-string key")
        stringKey to value
    }
}

fun Map<String, Any?>.requireExactKeys(label: String, expected: Set<String>) {
    if (keys != expected) {
        throw GradleException("$label has unexpected or missing fields")
    }
}

fun Any?.requiredString(label: String, maxLength: Int): String {
    val value = this as? String ?: throw GradleException("$label must be a string")
    if (value.isBlank() || value.length > maxLength || value.any { it.code !in 0x20..0x7e }) {
        throw GradleException("$label is invalid")
    }
    return value
}

fun Any?.requiredLong(label: String): Long {
    val number = this as? Number ?: throw GradleException("$label must be an integer")
    val value = number.toLong()
    if (number.toString() != value.toString()) throw GradleException("$label must be an exact integer")
    return value
}

fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        DigestInputStream(input, digest).use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (stream.read(buffer) >= 0) Unit
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

fun openPinnedFixtureStream(rawUrl: String, decodedByteCap: Long): InputStream {
    val allowedManifestUrls = setOf(
        "https://huggingface.co/ggml-org/models-moved/resolve/499bc8821c6b12b4e53c5bffcb21ec206f212d81/tinyllamas/stories260K.gguf",
    )
    if (rawUrl !in allowedManifestUrls) throw GradleException("Native fixture URL is not allowlisted")
    val allowedRedirectHosts = setOf(
        "huggingface.co",
        "us.aws.cdn.hf.co",
        "cas-bridge.xethub.hf.co",
        "cdn-lfs.hf.co",
        "cdn-lfs-us-1.hf.co",
    )
    var current = URI(rawUrl)
    repeat(6) {
        if (current.scheme != "https" || current.userInfo != null ||
            current.port !in setOf(-1, 443) || current.host !in allowedRedirectHosts
        ) {
            throw GradleException("Native fixture redirect target is not allowlisted")
        }
        val connection = current.toURL().openConnection() as? HttpsURLConnection
            ?: throw GradleException("Native fixture connection must use HTTPS")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("Accept-Encoding", "identity")
        val status = connection.responseCode
        if (status in setOf(
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
            )
        ) {
            val location = connection.getHeaderField("Location")
                ?.takeIf { it.length in 1..4_096 }
                ?: throw GradleException("Native fixture redirect is missing a bounded location")
            current = current.resolve(location)
            connection.disconnect()
            return@repeat
        }
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw GradleException("Native fixture request failed with HTTP $status")
        }
        val contentEncoding = connection.contentEncoding
        if (contentEncoding != null && !contentEncoding.equals("identity", ignoreCase = true)) {
            connection.disconnect()
            throw GradleException("Native fixture response uses unsupported content encoding")
        }
        val declaredLength = connection.contentLengthLong
        if (declaredLength > decodedByteCap) {
            connection.disconnect()
            throw GradleException("Native fixture exceeds its decoded byte cap")
        }
        return object : FilterInputStream(connection.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    connection.disconnect()
                }
            }
        }
    }
    throw GradleException("Native fixture exceeded the redirect limit")
}

fun writeVerifiedFixture(
    input: InputStream,
    destination: File,
    decodedByteCap: Long,
    expectedSha256: String,
) {
    val part = destination.resolveSibling("${destination.name}.part")
    Files.deleteIfExists(part.toPath())
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    try {
        input.use { source ->
            DigestInputStream(source, digest).use { verifiedSource ->
                part.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = verifiedSource.read(buffer)
                        if (read < 0) break
                        if (total > decodedByteCap - read) {
                            throw GradleException("Native fixture exceeds its decoded byte cap")
                        }
                        sink.write(buffer, 0, read)
                        total += read
                    }
                }
            }
        }
        val actualSha256 = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha256 != expectedSha256) {
            throw GradleException("Native fixture SHA-256 mismatch")
        }
        try {
            Files.move(
                part.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(part.toPath())
    }
}

val verifyNativePreflightFixtures by tasks.registering {
    group = "verification"
    description = "Acquire bounded native preflight fixtures and verify their exact digests"
    inputs.file(nativePreflightFixtureManifest)
    inputs.files(nativeFixtureGenerators)
    outputs.dir(nativePreflightFixtureDirectory)
    outputs.upToDateWhen { false }

    doLast {
        val manifestFile = nativePreflightFixtureManifest.asFile
        if (!manifestFile.isFile || manifestFile.length() !in 1L..65_536L) {
            throw GradleException("Native fixture manifest is missing or exceeds 64 KiB")
        }
        val root = try {
            JsonSlurper().parse(manifestFile).requiredJsonObject("fixture manifest")
        } catch (error: GradleException) {
            throw error
        } catch (_: Exception) {
            throw GradleException("Native fixture manifest is malformed")
        }
        root.requireExactKeys("fixture manifest", setOf("schemaVersion", "fixtures"))
        if (root["schemaVersion"].requiredLong("schemaVersion") != 1L) {
            throw GradleException("Unsupported native fixture schema")
        }
        val fixtures = root["fixtures"] as? List<*>
            ?: throw GradleException("fixtures must be an array")
        if (fixtures.size !in 1..16) throw GradleException("fixtures exceeds its bounded count")

        val outputDirectory = nativePreflightFixtureDirectory.get().asFile
        outputDirectory.mkdirs()
        val seenIds = mutableSetOf<String>()
        val seenDigests = mutableSetOf<String>()
        fixtures.forEachIndexed { index, rawFixture ->
            val label = "fixtures[$index]"
            val fixture = rawFixture.requiredJsonObject(label)
            fixture.requireExactKeys(
                label,
                setOf(
                    "id", "source", "licenseSpdx", "licenseSource", "sha256",
                    "decodedByteCap", "expectedFormat", "outputExtension",
                ),
            )
            val id = fixture["id"].requiredString("$label.id", 64)
            if (!id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}")) || !seenIds.add(id)) {
                throw GradleException("$label.id is invalid or duplicated")
            }
            val license = fixture["licenseSpdx"].requiredString("$label.licenseSpdx", 64)
            if (license !in setOf("MIT", "Apache-2.0", "CC0-1.0", "LicenseRef-CaraML-Test-Fixture")) {
                throw GradleException("$label has no reviewed allowlisted license")
            }
            fixture["licenseSource"].requiredString("$label.licenseSource", 512)
            val sha256 = fixture["sha256"].requiredString("$label.sha256", 64).lowercase()
            if (!sha256.matches(Regex("[0-9a-f]{64}")) || !seenDigests.add(sha256)) {
                throw GradleException("$label.sha256 is invalid or duplicated")
            }
            val cap = fixture["decodedByteCap"].requiredLong("$label.decodedByteCap")
            if (cap !in 1L..67_108_864L) throw GradleException("$label decoded byte cap is invalid")
            val expectedFormat = fixture["expectedFormat"].requiredString("$label.expectedFormat", 32)
            val extension = fixture["outputExtension"].requiredString("$label.outputExtension", 16)
            val expectedExtension = when (expectedFormat) {
                "GGUF", "CORRUPT_GGUF" -> "gguf"
                "CORRUPT_SAFETENSORS" -> "safetensors"
                else -> throw GradleException("$label.expectedFormat is unsupported")
            }
            if (extension != expectedExtension) throw GradleException("$label output extension is invalid")
            val source = fixture["source"].requiredJsonObject("$label.source")
            source.requireExactKeys("$label.source", setOf("type", "value"))
            val sourceType = source["type"].requiredString("$label.source.type", 16)
            val sourceValue = source["value"].requiredString("$label.source.value", 1_024)
            val destination = outputDirectory.resolve("$sha256.$extension")
            if (destination.isFile && destination.length() <= cap && sha256Hex(destination) == sha256) {
                return@forEachIndexed
            }

            val stream = when (sourceType) {
                "url" -> openPinnedFixtureStream(sourceValue, cap)
                "generator" -> {
                    val allowedGenerators = nativeFixtureGenerators.associateBy {
                        it.asFile.relativeTo(rootProject.projectDir).invariantSeparatorsPath
                    }
                    val generator = allowedGenerators[sourceValue]?.asFile
                        ?: throw GradleException("$label generator is not allowlisted")
                    if (!generator.isFile || generator.length() > cap) {
                        throw GradleException("$label generator payload is invalid")
                    }
                    FileInputStream(generator)
                }
                else -> throw GradleException("$label source type is unsupported")
            }
            writeVerifiedFixture(stream, destination, cap, sha256)
        }
    }
}

android {
    namespace = "com.debanshu777.nativeengine"
    compileSdk = 36

    defaultConfig {
        minSdk = 28

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
                arguments += "-DLLAMA_SRC=$patchedLlamaSourcePath"
                arguments += "-DLLAMA_BUILD_COMMIT=${pinnedLlamaCommit.get()}"
                arguments += "-DLLAMA_BUILD_NUMBER=${pinnedLlamaBuildNumber.get()}"
                // Required so every .so packaged into the APK supports Android
                // 16 KB page-size devices (Google Play requirement, 2025-11-01).
                arguments += "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
            }
        }
    }

    ndkVersion = "28.1.13356709"

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

// Produce the isolated llama source before AGP's externalNativeBuild tasks. AGP
// generates per-variant tasks (e.g. configureCMakeRelWithDebInfo[arm64-v8a],
// buildCMakeRelWithDebInfo[arm64-v8a]) lazily, so we use a name-prefix match
// in configureEach to cover all of them as they appear.
tasks.matching {
    val n = it.name
    n.startsWith("configureCMake") || n.startsWith("buildCMake") ||
            n.startsWith("externalNativeBuild")
}.configureEach {
    dependsOn(preparePatchedLlamaSource)
}

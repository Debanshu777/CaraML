import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app:composeApp"))
    implementation(project(":native-runtime"))
    implementation(project(":plugin-llama"))
    implementation(project(":plugin-diffusion"))
    implementation(compose.desktop.currentOs)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutinesCore)
}

val desktopPlatform = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    System.getProperty("os.name").lowercase().contains("win") -> "windows"
    else -> error("Unsupported desktop platform")
}
val artifactFsLibraryName = when (desktopPlatform) {
    "macos" -> "libartifact_fs.dylib"
    "linux" -> "libartifact_fs.so"
    "windows" -> "artifact_fs.dll"
    else -> throw GradleException("Unsupported desktop platform for secure artifact storage")
}
val nativeAppResourcesRoot = layout.buildDirectory.dir("generated/nativeAppResources")
val stageNativeAppResources by tasks.registering(Sync::class) {
    dependsOn(":nativeEngine:stageDesktopRuntime")
    from(project(":nativeEngine").layout.buildDirectory.dir("desktop-runtime/$desktopPlatform"))
    into(nativeAppResourcesRoot.map { it.dir("common") })
}

compose.desktop {
    application {
        mainClass = "com.debanshu777.caraml.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(nativeAppResourcesRoot)
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.debanshu777.caraml"
            packageVersion = "1.0.0"
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageNativeAppResources)
}

val nativeRuntimeInstalledImageSmoke by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Exercises the filesystem and selected inference runtimes from a relocated application image"
    dependsOn("createDistributable")
    mainClass.set("com.debanshu777.caraml.DesktopNativeRuntimeSmoke")
    args(providers.gradleProperty("caramlNativeEngines").orElse("llama,diffusion").get())
    doFirst {
        val imageRoot = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val resources = imageRoot.walkTopDown().singleOrNull { directory ->
            directory.isDirectory && directory.name == "resources" &&
                directory.resolve(artifactFsLibraryName).isFile
        } ?: throw GradleException("artifact_fs is missing from the desktop application image")
        val appDirectory = resources.parentFile
            ?: throw GradleException("Invalid desktop application image")
        // Copy the distributable's jars and resources away from both the image
        // and native build output. No development library path is available.
        val relocated = layout.buildDirectory.dir("verification/relocated-native-runtime").get().asFile
        delete(relocated)
        copy {
            from(appDirectory)
            include("*.jar", "resources/**")
            into(relocated)
        }
        val emptyLibraryPath = relocated.resolve("empty-library-path").also { it.mkdirs() }
        workingDir = relocated
        classpath = files(fileTree(relocated) { include("*.jar") })
        systemProperty("compose.application.resources.dir", relocated.resolve("resources").absolutePath)
        systemProperty("java.library.path", emptyLibraryPath.absolutePath)
        environment.remove("LD_LIBRARY_PATH")
        environment.remove("DYLD_LIBRARY_PATH")
        environment.remove("DYLD_FALLBACK_LIBRARY_PATH")
    }
}

tasks.register("artifactFsInstalledImageSmoke") {
    group = "verification"
    description = "Compatibility alias for the complete installed native-runtime smoke check"
    dependsOn(nativeRuntimeInstalledImageSmoke)
}

// Hot reload launches Java directly instead of using Compose's resource-aware
// distributable runner. Give those development processes the staged runtime.
tasks.withType<JavaExec>().matching {
    it.name == "run" || it.name.endsWith("Run") || it.name.startsWith("hotRun")
}.configureEach {
    dependsOn(stageNativeAppResources)
    systemProperty(
        "caraml.native.lib.dir",
        project(":nativeEngine").layout.buildDirectory.dir("desktop-runtime/$desktopPlatform").get().asFile.absolutePath,
    )
    systemProperty("caraml.recommendation.debugBuild", "true")
}

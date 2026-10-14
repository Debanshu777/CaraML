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
// Desktop JNI output: keep in sync with CaramlNativeLayout.DESKTOP_SUBDIR in nativeEngine/build.gradle.kts
val nativeDir =
    project(":nativeEngine").layout.buildDirectory.dir("llama-runner-desktop/$desktopPlatform").get().asFile.absolutePath
val artifactFsLibraryName = when (desktopPlatform) {
    "macos" -> "libartifact_fs.dylib"
    "linux" -> "libartifact_fs.so"
    "windows" -> "artifact_fs.dll"
    else -> throw GradleException("Unsupported desktop platform for secure artifact storage")
}
val artifactFsAppResourcesRoot = layout.buildDirectory.dir("generated/artifactFsAppResources")
val stageArtifactFsAppResources by tasks.registering(Sync::class) {
    dependsOn(":nativeEngine:verifyArtifactFsDesktopLibrary")
    from(project(":nativeEngine").layout.buildDirectory.file("llama-runner-desktop/$desktopPlatform/$artifactFsLibraryName"))
    into(artifactFsAppResourcesRoot.map { it.dir("common") })
}

compose.desktop {
    application {
        mainClass = "com.debanshu777.caraml.MainKt"

        // Compose Desktop's `run` task is AbstractRunDistributableTask, NOT JavaExec.
        // Standard `tasks.withType<JavaExec>` filter never matches it, so jvmArgs
        // must be injected via the Compose DSL here to actually take effect.
        jvmArgs += listOf(
            "-Djava.library.path=$nativeDir",
        )

        nativeDistributions {
            appResourcesRootDir.set(artifactFsAppResourcesRoot)
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.debanshu777.caraml"
            packageVersion = "1.0.0"
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(stageArtifactFsAppResources)
}

tasks.register<JavaExec>("artifactFsInstalledImageSmoke") {
    group = "verification"
    description = "Loads and exercises artifact_fs from the generated desktop application image"
    dependsOn("createDistributable")
    mainClass.set("com.debanshu777.caraml.core.download.transfer.ArtifactFsInstalledImageSmoke")
    doFirst {
        val imageRoot = layout.buildDirectory.dir("compose/binaries/main/app").get().asFile
        val resources = imageRoot.walkTopDown().singleOrNull { directory ->
            directory.isDirectory && directory.name == "resources" &&
                directory.resolve(artifactFsLibraryName).isFile
        } ?: throw GradleException("artifact_fs is missing from the desktop application image")
        val appDirectory = resources.parentFile
            ?: throw GradleException("Invalid desktop application image")
        classpath = files(fileTree(appDirectory) { include("*.jar") })
        systemProperty("compose.application.resources.dir", resources.absolutePath)
    }
}

tasks.matching { it.name == "run" || it.name.endsWith("Run") }.configureEach {
    dependsOn(":nativeEngine:compileLlamaRunnerDesktop")
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    systemProperty("caraml.recommendation.debugBuild", "true")
}

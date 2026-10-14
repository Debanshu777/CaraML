import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
}

// Generate resource accessors under the same root as the application code.
compose.resources {
    packageOfResClass = "com.debanshu777.caraml.generated.resources"
}

val minIos = "17.2"

kotlin {
    android {
        namespace = "com.debanshu777.caraml.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        withHostTest {}
    }

    val nativeEngineProject = project(":nativeEngine")
    val nativeEngineBuildDir =
        nativeEngineProject.layout.buildDirectory
            .get()
            .asFile

    listOf(
        Triple(iosArm64(), "iPhoneOS", "iosArm64"),
        Triple(iosSimulatorArm64(), "iPhoneSimulator", "iosSimulatorArm64"),
    ).forEach { (target, sdkName, archName) ->
        val mergeTaskName = "mergeLlamaRunnerStatic${archName.replaceFirstChar { it.uppercase() }}"
        nativeEngineProject.tasks.findByName(mergeTaskName)?.let { mergeTask ->
            tasks.matching { it.name.startsWith("link") && it.name.contains(archName) }.configureEach {
                dependsOn(mergeTask)
            }
        }
        val libPath = nativeEngineBuildDir.resolve("llama-runner-ios/$sdkName/$archName").absolutePath
        val mergedLib = "$libPath/libllama_runner_merged.a"
        target.binaries.framework {
            baseName = "ComposeApp"
            isStatic = false
            freeCompilerArgs += "-Xbinary=bundleId=com.debanshu777.caraml"
            freeCompilerArgs += "-Xoverride-konan-properties=osVersionMin.ios_simulator_arm64=$minIos;osVersionMin.ios_arm64=$minIos"
            linkerOpts(
                "-L$libPath",
                "-Wl,-force_load",
                mergedLib,
                "-framework",
                "Metal",
                "-framework",
                "Accelerate",
                "-framework",
                "Foundation",
                "-framework",
                "UserNotifications",
                "-Wl,-no_implicit_dylibs",
            )
        }
    }

    jvm()

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.work.runtime.ktx)
        }
        commonMain.dependencies {
            implementation(libs.haze.blur)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            implementation(project(":model-discovery"))
            implementation(project.dependencies.platform(libs.ktor))
            implementation(libs.ktor.client.core)
            implementation(project(":plugin-llama"))
            implementation(project(":plugin-diffusion"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.paging.common)
            implementation(libs.icons.extended)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.navigation3.compose.ui)
            implementation(libs.navigation3.viewmodel)
            implementation(libs.kotlinx.collections.immutable)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.materialkolor)
            implementation(libs.okio)
            implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.45.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
            implementation(libs.compose.ui.test)
            implementation(project.dependencies.platform(libs.ktor))
            implementation("io.ktor:ktor-client-mock")
            implementation(libs.okio.fakefilesystem)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(project.dependencies.platform(libs.ktor))
            implementation(libs.ktor.serialization.kotlinx.json)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesSwing)
            implementation("org.slf4j:slf4j-simple:2.0.9")
        }
    }
}

// JVM tests exercise the native artifact filesystem without a desktop application wrapper.
val desktopPlatform = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    System.getProperty("os.name").lowercase().contains("win") -> "windows"
    else -> error("Unsupported desktop platform")
}
val nativeDir = project(":nativeEngine").layout.buildDirectory
    .dir("llama-runner-desktop/$desktopPlatform").get().asFile.absolutePath

tasks.matching { it.name == "jvmTest" }.configureEach {
    dependsOn(":nativeEngine:verifyArtifactFsDesktopLibrary")
    (this as Test).systemProperty("caraml.native.lib.dir", nativeDir)
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

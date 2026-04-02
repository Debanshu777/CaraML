import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlinSerialization)
}

val minIos = "17.2"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
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
                "-Wl,-no_implicit_dylibs",
            )
        }
    }

    jvm()

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.android)
            implementation(libs.koin.core)
        }
        commonMain.dependencies {
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.androidx.datastore)
            implementation(libs.androidx.datastore.preferences)
            implementation(project(":huggingFaceManager"))
            implementation(project(":runner"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.icons.extended)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.navigation3.compose.ui)
            implementation(libs.navigation3.viewmodel)
            implementation(libs.kotlinx.collections.immutable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation("org.slf4j:slf4j-simple:2.0.9")
        }
    }
}

android {
    namespace = "com.debanshu777.caraml"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.debanshu777.caraml"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

compose.desktop {
    application {
        mainClass = "com.debanshu777.caraml.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.debanshu777.caraml"
            packageVersion = "1.0.0"
        }
    }
}

val desktopPlatform = when {
    System.getProperty("os.name").lowercase().contains("mac") -> "macos"
    System.getProperty("os.name").lowercase().contains("linux") -> "linux"
    System.getProperty("os.name").lowercase().contains("win") -> "windows"
    else -> "macos"
}
// Desktop JNI output: keep in sync with CaramlNativeLayout.DESKTOP_SUBDIR in nativeEngine/build.gradle.kts
val nativeDir =
    project(":nativeEngine").layout.buildDirectory.dir("llama-runner-desktop/$desktopPlatform").get().asFile.absolutePath

tasks.matching { it.name == "run" || it.name.endsWith("Run") }.configureEach {
    dependsOn(":nativeEngine:compileLlamaRunnerDesktop")
}

tasks.withType(org.gradle.api.tasks.JavaExec::class.java).configureEach {
    if (name == "run" || name.endsWith("Run")) {
        dependsOn(":nativeEngine:compileLlamaRunnerDesktop")
        jvmArgs(
            "-Djava.library.path=$nativeDir",
            "-Dcaraml.native.lib.dir=$nativeDir"
        )
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Base64

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    providers.gradleProperty("iosSimulatorDevice").orNull?.let { simulator ->
        device.set(simulator)
        standalone.set(false)
    }
}

val ortVersion = providers.gradleProperty("onnxRuntimeVersion").getOrElse("1.31.0")
val appleFixtures = layout.buildDirectory.dir("generated/onnxAppleTest")
val generateAppleFixtures = tasks.register("generateOnnxAppleTestFixtures") {
    inputs.files(fileTree("src/jvmTest/resources/tiny-onnx") { include("*.json", "*.onnx") })
    outputs.dir(appleFixtures)
    doLast {
        val destination = appleFixtures.get().file("AppleOnnxFixtures.kt").asFile
        destination.parentFile.mkdirs()
        val files = fileTree("src/jvmTest/resources/tiny-onnx") { include("*.json", "*.onnx") }.files.sortedBy { it.name }
        destination.writeText("package com.debanshu777.caraml.sdk.plugin.onnx\ninternal val appleOnnxFixtures = mapOf(\n" +
            files.joinToString(",\n") { "\"${it.name}\" to \"${Base64.getEncoder().encodeToString(it.readBytes())}\"" } + "\n)\n")
    }
}

kotlin {
    android {
        namespace = "com.debanshu777.caraml.sdk.plugin.onnx"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = 28
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest {}
    }
    jvm()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        val output = layout.buildDirectory.dir("apple/${target.name}")
        val prepare = tasks.register<Exec>("prepareOnnx${target.name.replaceFirstChar { it.uppercase() }}") {
            inputs.files("tools/prepare_apple.py", "src/iosMain/cpp/onnx_bridge.cpp", "src/iosMain/cpp/onnx_bridge.h")
            outputs.file(output.map { it.file("libcaraml_onnx_apple.a") })
            commandLine("python3", "${projectDir}/tools/prepare_apple.py", "--target", target.name)
        }
        target.compilations.getByName("main").cinterops.create("onnxBridge") {
            defFile("src/iosMain/cpp/onnx_bridge.def")
            compilerOpts("-I${projectDir}/src/iosMain/cpp")
            extraOpts("-libraryPath", output.get().asFile.absolutePath)
            tasks.named(interopProcessingTaskName).configure {
                dependsOn(prepare)
                inputs.file(output.map { it.file("libcaraml_onnx_apple.a") })
            }
        }
    }
    sourceSets {
        // The Android KMP library target does not participate in withAndroidTarget().
        androidMain { kotlin.srcDir("src/javaMain/kotlin") }
        jvmMain { kotlin.srcDir("src/javaMain/kotlin") }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies { implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion") }
        jvmMain.dependencies { implementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion") }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutinesTest)
        }
        iosTest { kotlin.srcDir(appleFixtures) }
    }
}

tasks.matching { it.name.startsWith("compileTestKotlinIos") }.configureEach { dependsOn(generateAppleFixtures) }

tasks.withType<Test>().configureEach {
    providers.gradleProperty("onnxFixtureDir").orNull?.let { systemProperty("caraml.onnx.fixture.dir", it) }
}

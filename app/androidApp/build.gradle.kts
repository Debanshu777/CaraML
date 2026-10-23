plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val onnxWebGpuEnabled = providers.gradleProperty("onnxWebGpu").map(String::toBoolean).getOrElse(false)
val onnxWebGpuOutput = layout.buildDirectory.dir("generated/onnxWebGpuJni")
val buildOnnxWebGpu = tasks.register<Exec>("buildOnnxWebGpu") {
    onlyIf { onnxWebGpuEnabled }
    inputs.file(rootProject.file("plugin-onnx/tools/build_webgpu_android.py"))
    inputs.file(rootProject.file("plugin-onnx/tools/prepare_android_gpu_discovery.py"))
    outputs.file(onnxWebGpuOutput.map { it.file("arm64-v8a/libonnxruntime_providers_webgpu.so") })
    val sdk = androidComponents.sdkComponents.sdkDirectory.get().asFile
    commandLine("python3", rootProject.file("plugin-onnx/tools/build_webgpu_android.py"),
        "--sdk", sdk, "--ndk", sdk.resolve("ndk/28.2.13676358"),
        "--cmake", sdk.resolve("cmake/3.31.1/bin/cmake"), "--output", onnxWebGpuOutput.get().asFile)
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

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // GGML_BACKEND_DL=ON builds CPU backends as MODULE .so files loaded at runtime.
            // Force extraction so MODULE libs appear at nativeLibraryDir for dlopen scanning.
            useLegacyPackaging = true
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
    if (onnxWebGpuEnabled) sourceSets.getByName("main").jniLibs.srcDir(onnxWebGpuOutput.get().asFile)
}

if (onnxWebGpuEnabled) tasks.named("preBuild").configure { dependsOn(buildOnnxWebGpu) }

dependencies {
    debugImplementation(project(":model-discovery"))
    implementation(project(":app:composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.core)
    debugImplementation(libs.compose.uiTooling)
    debugImplementation(libs.compose.foundation)
    debugImplementation(libs.compose.material3)
    debugImplementation(libs.navigation3.compose.ui)
    debugImplementation(libs.kotlinx.collections.immutable)
    debugImplementation(project(":plugin-llama"))
    debugImplementation(project(":plugin-onnx"))
    debugImplementation(libs.kotlinx.coroutinesCore)
}

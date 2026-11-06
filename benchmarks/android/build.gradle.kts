plugins {
    alias(libs.plugins.androidTest)
}

android {
    namespace = "com.debanshu777.caraml.benchmark.tests"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        // Android 12+ exposes frameOverrunMs via FrameTimeline.
        minSdk = 31
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["notAnnotation"] =
            "com.debanshu777.caraml.benchmark.tests.ProfileGeneration," +
                "com.debanshu777.caraml.benchmark.tests.MeasurementExperiment"
    }
    targetProjectPath = ":app:androidApp"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    buildTypes {
        create("benchmark") {
            isDebuggable = true // The controller is debuggable; the measured app is not.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    if (providers.gradleProperty("benchmarkFullTracing").orNull == "true") {
        implementation(libs.androidx.perfetto.tracing)
        implementation(libs.androidx.perfetto.binary)
    }
    implementation(libs.androidx.benchmark.macro)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.junit)
}

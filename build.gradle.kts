plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
}

tasks.register("verifyProject") {
    group = "verification"
    description = "Run the JVM test suites used by local development and CI."
    dependsOn(
        ":composeApp:jvmTest",
        ":huggingFaceManager:jvmTest",
        ":runner:jvmTest",
        ":diffusionRunner:jvmTest",
        ":nativeEngine:testDiffusionRunnerNativeDesktop",
    )
}

import org.gradle.api.artifacts.component.ProjectComponentIdentifier

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
}

val sdkModules = listOf(":core-api", ":core", ":storage", ":model-discovery", ":native-runtime")

tasks.register("verifySdk") {
    group = "verification"
    description = "Run portable SDK tests without configuring inference producers (-PsdkOnly=true)."
    dependsOn(sdkModules.flatMap { listOf("$it:jvmTest", "$it:compileCommonMainKotlinMetadata") })
    dependsOn("verifySdkBoundaries")
}

tasks.register("verifySdkBoundaries") {
    group = "verification"
    description = "Reject app, native engine, or provider coupling in portable SDK runtime graphs."
    doLast {
        val allowedProjects = mapOf(
            ":core-api" to emptySet(),
            ":native-runtime" to emptySet(),
            ":core" to setOf(":core-api"),
            ":storage" to setOf(":core-api"),
            ":model-discovery" to setOf(":core-api"),
        )
        allowedProjects.forEach { (module, allowed) ->
            val graph = project(module).configurations.getByName("jvmRuntimeClasspath")
                .incoming.resolutionResult.allComponents
            val projectPaths = graph.mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }
                .filter { it != module }.toSet()
            check((projectPaths - allowed).isEmpty()) { "$module has forbidden dependencies: ${projectPaths - allowed}" }
            if (module == ":core" || module == ":core-api" || module == ":native-runtime") {
                val forbidden = graph.mapNotNull { it.moduleVersion?.group }.filter {
                    it.startsWith("io.ktor") || it.startsWith("io.insert-koin") ||
                        it.startsWith("androidx.room") || it.startsWith("androidx.datastore") ||
                        it.startsWith("org.jetbrains.compose")
                }
                check(forbidden.isEmpty()) { "$module leaks framework dependencies: $forbidden" }
            }
        }
    }
}

if (!providers.gradleProperty("sdkOnly").map(String::toBoolean).getOrElse(false)) {
    tasks.register("verifyEngineBoundaries") {
        group = "verification"
        description = "Verify each plugin resolves only its own bridge and the shared runtime guard."
        doLast {
            listOf(":plugin-llama", ":plugin-diffusion").forEach { module ->
                val projects = project(module).configurations.getByName("jvmRuntimeClasspath")
                    .incoming.resolutionResult.allComponents
                    .mapNotNull { (it.id as? ProjectComponentIdentifier)?.projectPath }.toSet()
                val allowed = setOf(module, ":core-api", ":native-runtime")
                check((projects - allowed).isEmpty()) { "$module resolves forbidden modules: ${projects - allowed}" }
            }
        }
    }
}

tasks.register("verifyProject") {
    group = "verification"
    description = "Run the JVM test suites used by local development and CI."
    dependsOn("verifySdk")
    if (!providers.gradleProperty("sdkOnly").map(String::toBoolean).getOrElse(false)) dependsOn(
        "verifyEngineBoundaries",
        ":plugin-llama:jvmTest",
        ":plugin-diffusion:jvmTest",
        ":composeApp:jvmTest",
        ":nativeEngine:verifyNativeSubmodulePins",
        ":nativeEngine:testJniUtf8Desktop",
        ":nativeEngine:testDiffusionRunnerNativeDesktop",
        ":nativeEngine:testArtifactFsAndroidRootDesktop",
    )
}

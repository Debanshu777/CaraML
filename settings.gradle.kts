rootProject.name = "CaraML"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The portable SDK gate must not configure native producers or application projects.
// This is also usable in a checkout without initialized inference submodules.
val sdkOnly = providers.gradleProperty("sdkOnly").map(String::toBoolean).getOrElse(false)
include(":core-api", ":core", ":storage", ":model-discovery", ":native-runtime")
if (!sdkOnly) {
    include(":composeApp")
    include(":androidApp")
    include(":nativeEngine")
    include(":plugin-llama", ":plugin-diffusion")
}

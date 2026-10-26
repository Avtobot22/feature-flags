pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "feature-flags"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":feature-flags-core", ":feature-flags-json", ":feature-flags-retrofit", ":feature-flags-android")

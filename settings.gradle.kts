pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "feature-flags"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
include(":feature-flags-core", ":feature-flags-json")

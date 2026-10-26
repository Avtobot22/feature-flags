import io.gitlab.arturbosch.detekt.Detekt

plugins {
    alias(libs.plugins.detekt)
}

detekt {
    toolVersion = libs.versions.detekt.get()
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "build.gradle.kts",
            "settings.gradle.kts",
            "buildSrc",
            "feature-flags-core",
            "feature-flags-json",
            "feature-flags-retrofit",
            "feature-flags-android",
        ),
    )
    basePath = rootDir.absolutePath
    parallel = true
    ignoreFailures = false
    autoCorrect = false
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

tasks.withType<Detekt>().configureEach {
    include("**/*.kt", "**/*.kts")
    exclude("**/build/**", "**/.*/**")
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        md.required.set(false)
    }
}

tasks.register("qualityCheck") {
    group = "verification"
    description = "Runs Kotlin analysis, JVM tests, Android lint, and builds SDK libraries."
    dependsOn(
        "detekt",
        ":feature-flags-core:build",
        ":feature-flags-json:build",
        ":feature-flags-retrofit:build",
        ":feature-flags-android:build",
        ":feature-flags-android:assembleDebugAndroidTest",
    )
}

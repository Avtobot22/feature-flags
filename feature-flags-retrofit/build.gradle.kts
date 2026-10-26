plugins {
    alias(libs.plugins.featureflags.kotlin.library)
}

dependencies {
    api(projects.featureFlagsCore)
    api(libs.retrofit)
    testImplementation(projects.featureFlagsJson)
    testImplementation(libs.okhttp.mockwebserver)
}

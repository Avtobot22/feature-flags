plugins {
    alias(libs.plugins.featureflags.android.library)
}

android {
    namespace = "io.github.avtobot22.featureflags.android"
}

dependencies {
    api(projects.featureFlagsCore)
    androidTestImplementation(projects.featureFlagsJson)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

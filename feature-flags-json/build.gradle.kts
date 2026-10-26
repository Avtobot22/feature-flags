plugins {
    alias(libs.plugins.featureflags.kotlin.library)
}

dependencies {
    api(projects.featureFlagsCore)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)
}

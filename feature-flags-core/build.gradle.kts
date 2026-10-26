plugins {
    alias(libs.plugins.featureflags.kotlin.library)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}

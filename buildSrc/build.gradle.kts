import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `kotlin-dsl`
}

val buildJdkVersion = 21

kotlin {
    jvmToolchain(buildJdkVersion)
}

dependencies {
    implementation(files(LibrariesForLibs::class.java.protectionDomain.codeSource.location))
    implementation(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = libs.plugins.featureflags.kotlin.library.get().pluginId
            implementationClass = "KotlinLibraryConventionPlugin"
        }
    }
}

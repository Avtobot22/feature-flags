import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaLibraryPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapper

private const val BUILD_JDK_VERSION = 21

class KotlinLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply(JavaLibraryPlugin::class.java)
            pluginManager.apply(KotlinPluginWrapper::class.java)

            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
                withSourcesJar()
            }
            extensions.configure<KotlinJvmProjectExtension> {
                jvmToolchain(BUILD_JDK_VERSION)
                explicitApi()
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                    allWarningsAsErrors.set(true)
                }
            }

            val libs = extensions.getByType<LibrariesForLibs>()
            dependencies.add("testImplementation", dependencies.platform(libs.junit.bom))
            dependencies.add("testImplementation", libs.junit.jupiter)
            dependencies.add("testImplementation", libs.kotlin.test)
            dependencies.add("testRuntimeOnly", libs.junit.platform.launcher)
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                failOnNoDiscoveredTests.set(true)
                testLogging {
                    events("failed", "skipped")
                }
            }
        }
    }
}

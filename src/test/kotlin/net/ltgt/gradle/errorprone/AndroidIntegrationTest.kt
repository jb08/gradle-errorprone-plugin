package net.ltgt.gradle.errorprone

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Properties

class AndroidIntegrationTest : AbstractPluginIntegrationTest() {
    private val androidSdkHome = System.getProperty("test.android-sdk-home")
    private val androidPluginVersion = System.getProperty("android-plugin.version")!!

    override val additionalPluginManagementRepositories: String
        get() = """
            google()
            mavenCentral()
        """

    override val additionalPluginManagementResolutionStrategyEachPlugin: String
        get() = """
            if (requested.id.namespace == "com.android") {
                useModule("com.android.tools.build:gradle:${'$'}{requested.version}")
            }
        """

    @Before
    fun setupAndroid() {
        assume().that(GradleVersion.version(testGradleVersion)).isAtLeast(GradleVersion.version("4.10.1"))
        assertThat(androidSdkHome).isNotEmpty()

        Properties().apply {
            set("sdk.dir", androidSdkHome)
            testProjectDir.newFile("local.properties").outputStream().use {
                store(it, null)
            }
        }

        buildFile.appendText("""
            plugins {
                id("${ErrorPronePlugin.PLUGIN_ID}")
                id("com.android.application") version "$androidPluginVersion"
            }

            android {
                compileSdkVersion(28)
                defaultConfig {
                    minSdkVersion(15)
                    targetSdkVersion(28)
                    versionCode = 1
                    versionName = "1.0"
                }
            }

            repositories {
                mavenCentral()
                google()
            }
            dependencies {
                errorprone("com.google.errorprone:error_prone_core:$errorproneVersion")
                errorproneJavac("com.google.errorprone:javac:$errorproneJavacVersion")
            }
        """.trimIndent())

        File(testProjectDir.newFolder("src", "main"), "AndroidManifest.xml").writeText("""
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example">
            </manifest>
        """.trimIndent())
    }

    @Test
    fun `compilation succeeds`() {
        // given
        writeSuccessSource()

        // when
        val result = buildWithArgs("compileReleaseJavaWithJavac")

        // then
        assertThat(result.task(":compileReleaseJavaWithJavac")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `compilation fails`() {
        // given
        writeFailureSource()

        // when
        val result = buildWithArgsAndFail("compileReleaseJavaWithJavac")

        // then
        assertThat(result.task(":compileReleaseJavaWithJavac")?.outcome).isEqualTo(TaskOutcome.FAILED)
        assertThat(result.output).contains(FAILURE_SOURCE_COMPILATION_ERROR)
    }

    @Test
    fun `can configure errorprone`() {
        // given
        buildFile.appendText("""

            afterEvaluate {
                tasks.withType<JavaCompile>().configureEach {
                    options.errorprone {
                        check("ArrayEquals", CheckSeverity.OFF)
                    }
                }
            }
        """.trimIndent())
        writeFailureSource()

        // when
        val result = buildWithArgs("compileReleaseJavaWithJavac")

        // then
        assertThat(result.task(":compileReleaseJavaWithJavac")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `can disable errorprone`() {
        // given
        buildFile.appendText("""

            afterEvaluate {
                tasks.withType<JavaCompile>().configureEach {
                    options.errorprone.isEnabled.set(false)
                }
            }
        """.trimIndent())
        writeFailureSource()

        // when
        val result = buildWithArgs("compileReleaseJavaWithJavac")

        // then
        assertThat(result.task(":compileReleaseJavaWithJavac")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }
}

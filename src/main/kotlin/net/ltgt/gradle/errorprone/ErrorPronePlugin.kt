package net.ltgt.gradle.errorprone

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.FeatureExtension
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.TestExtension
import com.android.build.gradle.TestedExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.api.UnitTestVariant
import java.util.concurrent.atomic.AtomicBoolean
import org.gradle.api.JavaVersion
import org.gradle.api.Named
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.ClasspathNormalizer
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.compile.ForkOptions
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.* // ktlint-disable no-wildcard-imports
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.util.GradleVersion

class ErrorPronePlugin : Plugin<Project> {

    companion object {
        const val PLUGIN_ID = "net.ltgt.errorprone"

        const val CONFIGURATION_NAME = "errorprone"

        const val JAVAC_CONFIGURATION_NAME = "errorproneJavac"

        private val LOGGER = Logging.getLogger(ErrorPronePlugin::class.java)

        internal const val NO_JAVAC_DEPENDENCY_WARNING_MESSAGE =
"""No dependency was configured in configuration $JAVAC_CONFIGURATION_NAME, compilation with Error Prone will likely fail as a result.
Add a dependency to com.google.errorprone:javac with the appropriate version corresponding to the version of Error Prone you're using:

    dependencies {
        $JAVAC_CONFIGURATION_NAME("com.google.errorprone:javac:${'$'}errorproneJavacVersion")
    }
"""
    }

    override fun apply(project: Project) {
        if (GradleVersion.current() < GradleVersion.version("4.10")) {
            throw UnsupportedOperationException("$PLUGIN_ID requires at least Gradle 4.10")
        }

        val errorproneConfiguration = project.configurations.create(CONFIGURATION_NAME) {
            description = "Error Prone dependencies, will be extended by all source sets' annotationProcessor configurations"
            isVisible = false
            isCanBeConsumed = false
            // This configuration is not meant to be resolved, but there's no reason it couldn't be (and there's a test that does this)
            isCanBeResolved = true

            exclude(group = "com.google.errorprone", module = "javac")
        }
        val javacConfiguration = project.configurations.create(JAVAC_CONFIGURATION_NAME) {
            description = "Error Prone Javac dependencies, will only be used when using JDK 8 (i.e. not JDK 9 or superior)"
            isVisible = false
            isCanBeConsumed = false
            isCanBeResolved = true
        }

        val noJavacDependencyNotified = AtomicBoolean()
        project.tasks.withType<JavaCompile>().configureEach {
            val errorproneOptions =
                // XXX: service injection was added in Gradle 5.2, remove project.objects argument when changing min version
                (options as ExtensionAware).extensions.create(ErrorProneOptions.NAME, ErrorProneOptions::class.java, project.objects)
            options
                .compilerArgumentProviders
                .add(ErrorProneCompilerArgumentProvider(errorproneOptions))

            if (JavaVersion.current().isJava8) {
                // We don't know yet whether the task will use the same JVM (and need the Error Prone javac),
                // but chances are really high that this will be the case, so configure task inputs anyway.
                inputs.files(javacConfiguration).withPropertyName(JAVAC_CONFIGURATION_NAME).withNormalizer(ClasspathNormalizer::class)
                doFirst("configure errorprone in bootclasspath") {
                    if (options.errorprone.isEnabled.getOrElse(false) &&
                        (!options.isFork || (options.forkOptions.javaHome == null && options.forkOptions.executable == null))) {
                        // We now know that we need the Error Prone javac
                        if (!options.isFork) {
                            options.isFork = true
                            // reset forkOptions in case they were configured
                            options.forkOptions = ForkOptions()
                        }
                        javacConfiguration.asPath.also {
                            if (it.isNotBlank()) {
                                options.forkOptions.jvmArgs!!.add("-Xbootclasspath/p:$it")
                            } else if (noJavacDependencyNotified.compareAndSet(false, true)) {
                                LOGGER.warn(NO_JAVAC_DEPENDENCY_WARNING_MESSAGE)
                            }
                        }
                    }
                }
            }
        }

        project.plugins.withType<JavaBasePlugin> {
            project.extensions.getByName<SourceSetContainer>("sourceSets").configureEach {
                project.configurations[annotationProcessorConfigurationName].extendsFrom(errorproneConfiguration)
                // XXX: move to tasks.named<JavaCompile>(compileJavaTaskName) { … } when changing min version to 5.+
                project.tasks.withType<JavaCompile>().named(compileJavaTaskName).configure {
                    options.errorprone.isEnabled.byConvention(true)
                }
            }
        }

        project.plugins.withType<JavaPlugin> {
            // XXX: move to tasks.named<JavaCompile>(JavaPlugin.COMPILE_TEST_JAVA_TASK_NAME) { … } when changing min version 5.+
            project.tasks.withType<JavaCompile>().named(JavaPlugin.COMPILE_TEST_JAVA_TASK_NAME).configure {
                options.errorprone.isCompilingTestOnlyCode.byConvention(true)
            }
        }

        arrayOf("application", "library", "feature", "test", "instantapp").forEach {
            project.plugins.withId("com.android.$it") {
                fun BaseVariant.configure() {
                    annotationProcessorConfiguration.extendsFrom(errorproneConfiguration)
                    javaCompileProvider.configure {
                        options.errorprone {
                            isEnabled.byConvention(true)
                            if (this@configure is TestVariant || this@configure is UnitTestVariant) {
                                isCompilingTestOnlyCode.byConvention(true)
                            }
                        }
                    }
                }

                val android = project.extensions.getByName<BaseExtension>("android")
                (android as? AppExtension)?.applicationVariants?.configureEach(BaseVariant::configure)
                (android as? LibraryExtension)?.libraryVariants?.configureEach(BaseVariant::configure)
                (android as? FeatureExtension)?.featureVariants?.configureEach(BaseVariant::configure)
                (android as? TestExtension)?.applicationVariants?.configureEach(BaseVariant::configure)
                if (android is TestedExtension) {
                    android.testVariants.configureEach(BaseVariant::configure)
                    android.unitTestVariants.configureEach(BaseVariant::configure)
                }
            }
        }
    }
}

internal class ErrorProneCompilerArgumentProvider(
    private val errorproneOptions: ErrorProneOptions
) : CommandLineArgumentProvider, Named {

    override fun getName(): String = "errorprone"

    @Suppress("unused")
    @Nested
    @Optional
    fun getErrorproneOptions(): ErrorProneOptions? {
        return errorproneOptions.takeIf { it.isEnabled.getOrElse(false) }
    }

    override fun asArguments(): Iterable<String> {
        return when {
            errorproneOptions.isEnabled.getOrElse(false) -> listOf("-Xplugin:ErrorProne $errorproneOptions", "-XDcompilePolicy=simple")
            else -> emptyList()
        }
    }
}

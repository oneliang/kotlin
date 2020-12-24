/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ProjectDependency
import org.jetbrains.kotlin.caching.*
import org.jetbrains.plugins.gradle.tooling.ErrorMessageBuilder
import org.jetbrains.plugins.gradle.tooling.ModelBuilderService
import java.io.File
import java.io.Serializable
import java.lang.reflect.InvocationTargetException

interface KotlinGradleModel : Serializable {
    val hasKotlinPlugin: Boolean
    val flatCompilerArgumentsBySourceSet: FlatCompilerArgumentBySourceSet
    val coroutines: String?
    val platformPluginId: String?
    val implements: List<String>
    val kotlinTarget: String?
    val kotlinTaskProperties: KotlinTaskPropertiesBySourceSet
    val gradleUserHome: String
}

data class KotlinGradleModelImpl(
    override val hasKotlinPlugin: Boolean,
    override val flatCompilerArgumentsBySourceSet: FlatCompilerArgumentBySourceSet,
    override val coroutines: String?,
    override val platformPluginId: String?,
    override val implements: List<String>,
    override val kotlinTarget: String? = null,
    override val kotlinTaskProperties: KotlinTaskPropertiesBySourceSet,
    override val gradleUserHome: String,
) : KotlinGradleModel

abstract class AbstractKotlinGradleModelBuilder : ModelBuilderService {
    companion object {
        val kotlinCompileJvmTaskClasses = listOf(
            "org.jetbrains.kotlin.gradle.tasks.KotlinCompile_Decorated",
            "org.jetbrains.kotlin.gradle.tasks.KotlinCompileWithWorkers_Decorated"
        )

        val kotlinCompileTaskClasses = kotlinCompileJvmTaskClasses + listOf(
            "org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile_Decorated",
            "org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommon_Decorated",
            "org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompileWithWorkers_Decorated",
            "org.jetbrains.kotlin.gradle.tasks.KotlinCompileCommonWithWorkers_Decorated"
        )
        val platformPluginIds = listOf("kotlin-platform-jvm", "kotlin-platform-js", "kotlin-platform-common")
        val pluginToPlatform = linkedMapOf(
            "kotlin" to "kotlin-platform-jvm",
            "kotlin2js" to "kotlin-platform-js"
        )
        val kotlinPluginIds = listOf("kotlin", "kotlin2js", "kotlin-android")
        val ABSTRACT_KOTLIN_COMPILE_CLASS = "org.jetbrains.kotlin.gradle.tasks.AbstractKotlinCompile"

        val kotlinProjectExtensionClass = "org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension"
        val kotlinSourceSetClass = "org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet"

        val kotlinPluginWrapper = "org.jetbrains.kotlin.gradle.plugin.KotlinPluginWrapperKt"

        fun Task.getSourceSetName(): String = try {
            javaClass.methods.firstOrNull { it.name.startsWith("getSourceSetName") && it.parameterTypes.isEmpty() }?.invoke(this) as? String
        } catch (e: InvocationTargetException) {
            null // can be thrown if property is not initialized yet
        } ?: "main"

        @Suppress("UNCHECKED_CAST")
        fun Task.getCompilerArgumentsBucket(methodName: String): FlatCompilerArgumentsBucket? {
            return try {
                if (!isOptimalStrategySuitable)
                    return null
                val unsafeBucket = javaClass.getDeclaredMethod(methodName).invoke(this) as List<Any?>
                val classpathParts = unsafeBucket[0] as Pair<String, List<String>>?
                val singleArguments = unsafeBucket[1] as MutableMap<String, String>
                val multipleArguments = unsafeBucket[2] as MutableMap<String, List<String>>
                val flagArguments = unsafeBucket[3] as MutableList<String>
                val internalArguments = unsafeBucket[4] as MutableList<String>
                val freeArgs = unsafeBucket[5] as MutableList<String>
                return FlatCompilerArgumentsBucket(
                    classpathParts,
                    singleArguments,
                    multipleArguments,
                    flagArguments,
                    internalArguments,
                    freeArgs
                )
            } catch (e: Exception) {
                // No argument accessor method is available
                null
            }
        }
    }
}

val Task.isOptimalStrategySuitable: Boolean
    get() = javaClass.declaredMethods.any { it.name == "getFlatCompilerArgumentsBucket" }

class KotlinGradleModelBuilder : AbstractKotlinGradleModelBuilder() {
    private var converter: RawToFlatCompilerArgumentsBucketConverter? = null

    override fun getErrorMessageBuilder(project: Project, e: Exception): ErrorMessageBuilder {
        return ErrorMessageBuilder.create(project, e, "Gradle import errors")
            .withDescription("Unable to build Kotlin project configuration")
    }

    override fun canBuild(modelName: String?): Boolean = modelName == KotlinGradleModel::class.java.name

    private fun getImplementedProjects(project: Project): List<Project> {
        return listOf("expectedBy", "implement")
            .flatMap { project.configurations.findByName(it)?.dependencies ?: emptySet<Dependency>() }
            .filterIsInstance<ProjectDependency>()
            .mapNotNull { it.dependencyProject }
    }

    // see GradleProjectResolverUtil.getModuleId() in IDEA codebase
    private fun Project.pathOrName() = if (path == ":") name else path

    @Suppress("UNCHECKED_CAST")
    private fun Task.getCompilerArguments(methodName: String): List<String>? {
        return try {
            javaClass.getDeclaredMethod(methodName).invoke(this) as List<String>
        } catch (e: Exception) {
            // No argument accessor method is available
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Task.getDependencyClasspath(): List<String> {
        try {
            val abstractKotlinCompileClass = javaClass.classLoader.loadClass(ABSTRACT_KOTLIN_COMPILE_CLASS)
            val getCompileClasspath = abstractKotlinCompileClass.getDeclaredMethod("getCompileClasspath").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            return (getCompileClasspath.invoke(this) as Collection<File>).map { it.path }
        } catch (e: ClassNotFoundException) {
            // Leave arguments unchanged
        } catch (e: NoSuchMethodException) {
            // Leave arguments unchanged
        } catch (e: InvocationTargetException) {
            // We can safely ignore this exception here as getCompileClasspath() gets called again at a later time
            // Leave arguments unchanged
        }
        return emptyList()
    }

    private fun getCoroutines(project: Project): String? {
        val kotlinExtension = project.extensions.findByName("kotlin") ?: return null
        val experimentalExtension = try {
            kotlinExtension::class.java.getMethod("getExperimental").invoke(kotlinExtension)
        } catch (e: NoSuchMethodException) {
            return null
        }

        return try {
            experimentalExtension::class.java.getMethod("getCoroutines").invoke(experimentalExtension)?.toString()
        } catch (e: NoSuchMethodException) {
            null
        }
    }

    override fun buildAll(modelName: String?, project: Project): KotlinGradleModel {

        val kotlinPluginId = kotlinPluginIds.singleOrNull { project.plugins.findPlugin(it) != null }
        val platformPluginId = platformPluginIds.singleOrNull { project.plugins.findPlugin(it) != null }

        val extraProperties = HashMap<String, KotlinTaskProperties>()
        val platform = platformPluginId ?: pluginToPlatform.entries.singleOrNull { project.plugins.findPlugin(it.key) != null }?.value
        val implementedProjects = getImplementedProjects(project)
        val compileTasksList = project.getAllTasks(false)[project]?.filter { it.javaClass.name in kotlinCompileTaskClasses }.orEmpty()
        val flatArgumentsBySourceSet = compileTasksList.collectFlatArgsBySourceSet(extraProperties)
        return KotlinGradleModelImpl(
            kotlinPluginId != null || platformPluginId != null,
            flatArgumentsBySourceSet,
            getCoroutines(project),
            platform,
            implementedProjects.map { it.pathOrName() },
            platform ?: kotlinPluginId,
            extraProperties,
            project.gradle.gradleUserHomeDir.absolutePath
        )
    }

    private fun List<Task>.collectFlatArgsBySourceSet(extraProperties: HashMap<String, KotlinTaskProperties>) =
        LinkedHashMap<String, FlatArgsInfo>().also {
            forEach { compileTask ->
                val sourceSetName = compileTask.getSourceSetName()
                val dependencyClasspath = compileTask.getDependencyClasspath()

                val currentCompilerArgumentsBucket: FlatCompilerArgumentsBucket
                val defaultCompilerArgumentsBucket: FlatCompilerArgumentsBucket
                if (compileTask.isOptimalStrategySuitable) {
                    currentCompilerArgumentsBucket = compileTask.getCompilerArgumentsBucket("getFlatCompilerArgumentsBucket")
                        ?: compileTask.getCompilerArgumentsBucket("getFlatCompilerArgumentsBucketIgnoreClasspathIssues")
                                ?: FlatCompilerArgumentsBucket()
                    defaultCompilerArgumentsBucket = compileTask.getCompilerArgumentsBucket("getFlatCompilerArgumentsBucket")
                        ?: FlatCompilerArgumentsBucket()
                } else {
                    val converter = this@KotlinGradleModelBuilder.converter
                        ?: RawToFlatCompilerArgumentsBucketConverter(compileTask::class.java.classLoader).also {
                            this@KotlinGradleModelBuilder.converter = it
                        }
                    currentCompilerArgumentsBucket = converter.convert(
                        compileTask.getCompilerArguments("getSerializedCompilerArguments")
                            ?: compileTask.getCompilerArguments("getSerializedCompilerArgumentsIgnoreClasspathIssues")
                            ?: emptyList()
                    )
                    defaultCompilerArgumentsBucket = compileTask.getCompilerArgumentsBucket("getFlatCompilerArgumentsBucket")
                        ?: converter.convert(compileTask.getCompilerArguments("getDefaultSerializedCompilerArguments").orEmpty())
                }
                it[sourceSetName] = FlatArgsInfoImpl(
                    currentCompilerArgumentsBucket,
                    defaultCompilerArgumentsBucket,
                    dependencyClasspath
                )
                extraProperties.acknowledgeTask(compileTask, null)
            }
        }
}

package com.bytedance.android.plugin

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApkSigningConfig
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import com.bytedance.android.plugin.extensions.AabResGuardExtension
import com.bytedance.android.plugin.tasks.AabResGuardTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

class AabResGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        if (!project.plugins.hasPlugin("com.android.application")) {
            throw GradleException("Android Application plugin required")
        }

        val extension = project.extensions.create("aabResGuard", AabResGuardExtension::class.java)

        val androidComponents = project.extensions.getByType(
            ApplicationAndroidComponentsExtension::class.java
        )

        androidComponents.onVariants { variant ->
            val capitalized = variant.name.replaceFirstChar { it.uppercase() }
            val taskName = "aabresguard$capitalized"

            val taskProvider = project.tasks.register(taskName, AabResGuardTask::class.java) { task ->
                task.description = "Obfuscate resources for ${variant.name} bundle"
                task.group = "bundle"

                task.enableObfuscate.set(extension.enableObfuscate)
                extension.mappingFile?.let { task.mappingFile.set(it.toFile()) }
                task.whiteList.set(extension.whiteList ?: emptySet())
                task.mergeDuplicatedRes.set(extension.mergeDuplicatedRes)
                task.enableFilterFiles.set(extension.enableFilterFiles)
                task.filterList.set(extension.filterList ?: emptySet())
                task.enableFilterStrings.set(extension.enableFilterStrings)
                extension.unusedStringPath?.takeIf { it.isNotBlank() }?.let {
                    task.unusedStringPath.set(it)
                }
                task.languageWhiteList.set(extension.languageWhiteList ?: emptySet())

                resolveSigningConfig(project, variant)?.let { sc ->
                    sc.storeFile?.let { task.storeFile.set(it) }
                    sc.storePassword?.let { task.storePassword.set(it) }
                    sc.keyAlias?.let { task.keyAlias.set(it) }
                    sc.keyPassword?.let { task.keyPassword.set(it) }
                }
            }

            variant.artifacts
                .use(taskProvider)
                .wiredWithFiles(AabResGuardTask::inputBundle, AabResGuardTask::outputBundle)
                .toTransform(SingleArtifact.BUNDLE)
        }
    }

    private fun resolveSigningConfig(
        project: Project,
        variant: ApplicationVariant
    ): ApkSigningConfig? {
        val androidExt = project.extensions.findByType(ApplicationExtension::class.java) ?: return null
        val buildTypeName = variant.buildType ?: return null
        return androidExt.buildTypes.findByName(buildTypeName)?.signingConfig
    }
}

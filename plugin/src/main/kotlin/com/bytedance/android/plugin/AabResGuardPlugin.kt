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
import org.gradle.api.artifacts.Configuration

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

                task.enableJiaguHarden.set(extension.enableJiaguHarden)
                task.jiaguCertHash.set(extension.jiaguCertHash)
                if (extension.enableJiaguHarden) {
                    // classpath phụ cho pack.jar (subprocess): proto để sửa manifest AAB + zstd để nén.
                    // Chỉ tạo/giải khi BẬT jiagu → không phát sinh chi phí khi tắt.
                    task.jiaguClasspath.from(jiaguTooling(project))
                }
            }

            variant.artifacts
                .use(taskProvider)
                .wiredWithFiles(AabResGuardTask::inputBundle, AabResGuardTask::outputBundle)
                .toTransform(SingleArtifact.BUNDLE)
        }
    }

    /**
     * Configuration chứa classpath phụ pack.jar cần lúc chạy -aab: proto (com.android.aapt.Resources)
     * để sửa manifest AAB + zstd-jni để nén dex. Tạo 1 lần trong project consumer, resolve từ google()/mavenCentral().
     */
    private fun jiaguTooling(project: Project): Configuration {
        project.configurations.findByName("jiaguHardenTooling")?.let { return it }
        val cfg = project.configurations.create("jiaguHardenTooling") { c ->
            c.isCanBeConsumed = false
            c.isCanBeResolved = true
            c.isVisible = false
        }
        project.dependencies.add(cfg.name, "com.android.tools.build:aapt2-proto:0.4.0")
        project.dependencies.add(cfg.name, "com.google.protobuf:protobuf-java:3.25.5")
        project.dependencies.add(cfg.name, "com.github.luben:zstd-jni:1.5.6-3")
        return cfg
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

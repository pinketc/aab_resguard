package com.bytedance.android.plugin.tasks

import com.bytedance.android.aabresguard.commands.ObfuscateBundleCommand
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class AabResGuardTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputBundle: RegularFileProperty

    @get:OutputFile
    abstract val outputBundle: RegularFileProperty

    @get:Input
    abstract val enableObfuscate: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val mappingFile: Property<File>

    @get:Input
    abstract val whiteList: SetProperty<String>

    @get:Input
    abstract val mergeDuplicatedRes: Property<Boolean>

    @get:Input
    abstract val enableFilterFiles: Property<Boolean>

    @get:Input
    abstract val filterList: SetProperty<String>

    @get:Input
    abstract val enableFilterStrings: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val unusedStringPath: Property<String>

    @get:Input
    abstract val languageWhiteList: SetProperty<String>

    @get:Input
    @get:Optional
    abstract val storeFile: Property<File>

    @get:Input
    @get:Optional
    abstract val storePassword: Property<String>

    @get:Input
    @get:Optional
    abstract val keyAlias: Property<String>

    @get:Input
    @get:Optional
    abstract val keyPassword: Property<String>

    @TaskAction
    fun execute() {
        val input = inputBundle.get().asFile.toPath()
        val output = outputBundle.get().asFile.toPath()
        // bundletool refuses to overwrite — clear stale output if Gradle pre-created it
        outputBundle.get().asFile.takeIf { it.exists() }?.delete()

        printSignConfiguration()

        // Defensive copies: ObfuscateBundleCommand passes these sets through to
        // executors that mutate them (e.g. BundleFileFilter#addAll). SetProperty.get()
        // returns an immutable view, so we must give the core a mutable copy.
        val builder = ObfuscateBundleCommand.builder()
            .setEnableObfuscate(enableObfuscate.get())
            .setBundlePath(input)
            .setOutputPath(output)
            .setMergeDuplicatedResources(mergeDuplicatedRes.get())
            .setWhiteList(HashSet(whiteList.get()))
            .setFilterFile(enableFilterFiles.get())
            .setFileFilterRules(HashSet(filterList.get()))
            .setRemoveStr(enableFilterStrings.get())
            .setLanguageWhiteList(HashSet(languageWhiteList.get()))

        if (mappingFile.isPresent) {
            builder.setMappingPath(mappingFile.get().toPath())
        }
        if (unusedStringPath.isPresent) {
            builder.setUnusedStrPath(unusedStringPath.get())
        }
        if (storeFile.isPresent && storeFile.get().exists()) {
            builder.setStoreFile(storeFile.get().toPath())
            storeAlias("keyAlias", keyAlias)?.let { builder.setKeyAlias(it) }
            storeAlias("keyPassword", keyPassword)?.let { builder.setKeyPassword(it) }
            storeAlias("storePassword", storePassword)?.let { builder.setStorePassword(it) }
        }

        builder.build().execute()
    }

    private fun storeAlias(name: String, prop: Property<String>): String? {
        return if (prop.isPresent) prop.get() else null
    }

    private fun printSignConfiguration() {
        println("-------------- sign configuration --------------")
        println("\tstoreFile : ${storeFile.orNull}")
        println("\talias : ${mask(keyAlias.orNull)}")
        println("\tkeyPassword : ${mask(keyPassword.orNull)}")
        println("\tstorePassword : ${mask(storePassword.orNull)}")
        println("-------------- sign configuration --------------")
    }

    private fun mask(value: String?): String {
        if (value == null) return "/"
        if (value.length > 2) return value.substring(0, value.length / 2) + "****"
        return "****"
    }
}

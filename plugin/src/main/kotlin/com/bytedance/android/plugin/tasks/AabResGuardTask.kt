package com.bytedance.android.plugin.tasks

import com.bytedance.android.aabresguard.commands.ObfuscateBundleCommand
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

abstract class AabResGuardTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

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

    // ---- jiagu hardening (lớp mã hóa DEX chạy sau obfuscate, thay thế outputBundle) ----
    @get:Input
    abstract val enableJiaguHarden: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val jiaguCertHash: Property<String>

    /** Classpath phụ cho pack.jar (subprocess): aapt2-proto + protobuf + zstd-jni. */
    @get:Classpath
    abstract val jiaguClasspath: ConfigurableFileCollection

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

        // jiagu: mã hóa DEX trên chính AAB vừa obfuscate; kết quả thay thế outputBundle (= output của bundleRelease).
        if (enableJiaguHarden.get()) {
            hardenWithJiagu(outputBundle.get().asFile)
        }
    }

    private fun hardenWithJiagu(obfuscatedAab: File) {
        logger.lifecycle("-------------- jiagu hardening (mã hóa DEX) --------------")
        if (!(storeFile.isPresent && storeFile.get().exists())) {
            throw GradleException(
                "enableJiaguHarden=true nhưng thiếu signingConfig (storeFile) cho buildType này. " +
                    "jiagu cần keystore để ký AAB — khai signingConfigs.release rồi gán vào buildTypes.release."
            )
        }
        val tools = extractJiaguTools()
        val outDir = File(temporaryDir, "jiagu-out").apply { deleteRecursively(); mkdirs() }
        val packJar = File(tools, "pack.jar")
        val cp = (listOf(packJar) + jiaguClasspath.files).joinToString(File.pathSeparator) { it.absolutePath }
        val javaBin = File(
            File(System.getProperty("java.home"), "bin"),
            if (isWindows()) "java.exe" else "java"
        ).absolutePath

        val keyPw = if (keyPassword.isPresent) keyPassword.get() else storePassword.get()
        val args = mutableListOf(
            javaBin, "-cp", cp, "com.frezrik.jiagu.pack.Main",
            "-aab", obfuscatedAab.absolutePath,
            "-out", outDir.absolutePath,
            "-key", storeFile.get().absolutePath,
            "-kp", storePassword.get(),
            "-alias", keyAlias.get(),
            "-ap", keyPw
        )
        val certHash = jiaguCertHash.orNull
        if (!certHash.isNullOrBlank()) {
            args.add("-certhash"); args.add(certHash)
        }

        execOps.exec { it.commandLine(args) }

        val base = obfuscatedAab.name.replace(Regex("(?i)\\.aab$"), "")
        val packed = File(outDir, "${base}_packed.aab")
        if (!packed.exists()) {
            throw GradleException("jiagu: pack.jar không tạo output (${packed.absolutePath}) — xem log jiagu ở trên.")
        }
        Files.copy(packed.toPath(), obfuscatedAab.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("jiagu: ✓ hardened -> ${obfuscatedAab.name} (${obfuscatedAab.length() / 1024} KB)")
    }

    /** Bung pack.jar + shell dựng-sẵn (classes.dex + libjiagu*.so) từ plugin jar ra thư mục tạm của task. */
    private fun extractJiaguTools(): File {
        val dir = File(temporaryDir, "jiagu-tools")
        val names = listOf(
            "jiagu/pack.jar",
            "jiagu/bin/classes.dex",
            "jiagu/bin/jni/armeabi-v7a/libjiagu.so",
            "jiagu/bin/jni/arm64-v8a/libjiagu.so",
            "jiagu/bin/jni/x86/libjiagu.so",
            "jiagu/bin/jni/x86_64/libjiagu.so"
        )
        val cl = javaClass.classLoader
        for (res in names) {
            val target = File(dir, res.removePrefix("jiagu/"))
            target.parentFile.mkdirs()
            val stream = cl.getResourceAsStream(res)
                ?: throw GradleException("jiagu: thiếu resource '$res' trong plugin jar (build lại plugin).")
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        return dir
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

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

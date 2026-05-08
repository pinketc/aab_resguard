package com.bytedance.android.plugin.extensions

import java.nio.file.Path

open class AabResGuardExtension {
    var enableObfuscate: Boolean = true
    var mappingFile: Path? = null
    var whiteList: Set<String>? = HashSet()

    /**
     * Ignored when running on AGP 9.x: the obfuscated AAB now replaces the
     * BUNDLE artifact in place via the Variant API, so its filename is
     * controlled by AGP. Kept for DSL backwards compatibility only.
     */
    var obfuscatedBundleFileName: String = ""

    var mergeDuplicatedRes: Boolean = false
    var enableFilterFiles: Boolean = false
    var filterList: Set<String>? = HashSet()
    var enableFilterStrings: Boolean = false
    var unusedStringPath: String? = ""
    var languageWhiteList: Set<String>? = HashSet()

    override fun toString(): String {
        return "AabResGuardExtension\n" +
                "\tenableObfuscate=$enableObfuscate\n" +
                "\tmappingFile=$mappingFile\n" +
                "\twhiteList=$whiteList\n" +
                "\tmergeDuplicatedRes=$mergeDuplicatedRes\n" +
                "\tenableFilterFiles=$enableFilterFiles\n" +
                "\tfilterList=$filterList\n" +
                "\tenableFilterStrings=$enableFilterStrings\n" +
                "\tunusedStringPath=$unusedStringPath\n" +
                "\tlanguageWhiteList=$languageWhiteList"
    }
}

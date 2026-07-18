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

    /**
     * jiagu hardening (mã hóa DEX + native shell 360-style) — chạy NGAY SAU khi obfuscate resource,
     * trên chính AAB đầu ra. Bật = `bundleRelease` ra AAB đã resguard + đã hardening + đã ký, MỘT lần bấm.
     * Cần signingConfig của buildType (để ký). Mặc định TẮT để không đổi hành vi hiện có.
     */
    var enableJiaguHarden: Boolean = false

    /**
     * (Tùy chọn) SHA-256 của **App Signing key** trên Google Play (64 hex, bỏ dấu ':').
     * Nhúng làm C1 chống repackaging (warn-only). Trống = tắt (up Play sạch). KHÔNG phải upload key.
     */
    var jiaguCertHash: String = ""

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
                "\tlanguageWhiteList=$languageWhiteList\n" +
                "\tenableJiaguHarden=$enableJiaguHarden\n" +
                "\tjiaguCertHash=${if (jiaguCertHash.isBlank()) "(none)" else "set"}"
    }
}

package com.strike.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

internal fun verifyUpdateApk(context: Context, apk: File, release: Release): Long {
    require(apk.length() == release.bytes && fileDigest(apk) == release.sha256) {
        "The APK changed. Download it again"
    }
    val packages = context.packageManager
    val current = packages.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val next = packages.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        ?: throw IllegalArgumentException("Android could not read the APK")
    require(next.packageName == current.packageName) { "This APK is not Strike" }
    require(next.longVersionCode > current.longVersionCode) { "The release needs a higher versionCode" }
    require(compareVersions(next.versionName ?: "", release.version) == 0) {
        "The APK version does not match the release tag"
    }
    val trusted = current.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
    val offered = next.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
    require(!trusted.isNullOrEmpty() && offered == trusted) {
        "The signing key differs. Install a matching release build manually first"
    }
    require((next.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT) {
        "This APK needs a newer Android version"
    }
    ZipFile(apk).use { zip ->
        require(zip.getEntry("lib/${Build.SUPPORTED_ABIS.first()}/libstrike.so") != null) {
            "This APK is for a different processor"
        }
    }
    return next.longVersionCode
}

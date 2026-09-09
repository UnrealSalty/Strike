package com.strike.server

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import java.io.File

internal class DashboardContext(base: Context, private val installed: Context, private val directory: File) :
    ContextWrapper(base) {
    override fun getPackageName(): String = "com.strike"
    override fun getApplicationInfo(): ApplicationInfo = installed.applicationInfo
    override fun getAssets(): AssetManager = installed.assets
    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = directory
    override fun getCacheDir(): File = File(directory, "cache").also {
        check(it.isDirectory || it.mkdir()) { "Cannot open the dashboard cache" }
    }
}

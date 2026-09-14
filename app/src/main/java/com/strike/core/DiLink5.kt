package com.strike.core

import android.os.Build
import java.io.File

object DiLink5 {
    val isSupported: Boolean by lazy {
        supportsDiLink5(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.orEmpty().contains("arm64-v8a")) {
            File("/vendor/lib64/libais_client.so").isFile || File("/system/lib64/libais_client.so").isFile
        }
    }
}

internal inline fun supportsDiLink5(sdk: Int, arm64: Boolean, hasAis: () -> Boolean): Boolean =
    sdk >= 30 && arm64 && hasAis()

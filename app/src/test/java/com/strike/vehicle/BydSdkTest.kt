package com.strike.vehicle

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydSdkTest {
    @Test
    fun aMissingNativeDependencyLeavesTheDeviceUnavailable() {
        val context = ContextWrapper(null)
        val name = "com.strike.vehicle.BydSdkTest\$MissingNativeDependency"
        assertNull(BydSdk.deviceClass(name, context))
        assertNull(BydSdk.deviceClass(name, context))
        assertEquals(String::class.java, BydSdk.deviceClass("java.lang.String", context))
    }

    private object MissingNativeDependency {
        init {
            throw UnsatisfiedLinkError("Missing OEM native library")
        }
    }
}

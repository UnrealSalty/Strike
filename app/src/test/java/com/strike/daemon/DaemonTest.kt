package com.strike.daemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DaemonTest {

    @Test
    fun readsTheApkPathOutOfPmOutput() {
        assertEquals(
            "/data/app/~~ab==/com.strike-cd==/base.apk",
            apkFrom("package:/data/app/~~ab==/com.strike-cd==/base.apk\n")
        )
    }

    @Test
    fun ignoresLinesThatAreNotAPackagePath() {
        assertEquals(
            "/data/app/com.strike-1/base.apk",
            apkFrom("Warning: something\npackage:/data/app/com.strike-1/base.apk")
        )
    }

    @Test
    fun hasNoApkPathWhenTheShellSaidNothing() {
        assertNull(apkFrom(null))
        assertNull(apkFrom(""))
    }

}

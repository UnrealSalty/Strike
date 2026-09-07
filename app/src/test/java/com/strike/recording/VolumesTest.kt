package com.strike.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val LISTING = """
private mounted null
emulated;0 mounted null
public:179,65 mounted 3439-3138
public:8,1 mounted A1B2-C3D4
public:179,66 unmounted null
"""

class VolumesTest {

    @Test
    fun onlyMountedPublicVolumesWithAUuidCanHoldClips() {
        val mounts = parseVolumes(LISTING, null)

        assertEquals(2, mounts.size)
        assertEquals("/storage/3439-3138", mounts[0].path)
        assertEquals("/storage/A1B2-C3D4", mounts[1].path)
    }

    @Test
    fun theFirmwareSdUuidNamesTheCardAndEverythingElseIsUsb() {
        val mounts = parseVolumes(LISTING, "A1B2-C3D4")

        assertEquals("usb", mounts[0].location)
        assertEquals("sd", mounts[1].location)
    }

    @Test
    fun withoutTheUuidTheBlockMajorDecides() {
        assertEquals("sd", classify("public:179,65", "3439-3138", null))
        assertEquals("usb", classify("public:8,1", "A1B2-C3D4", null))
    }

    @Test
    fun dfReportsTheVolumeInMegabytes() {
        val output = "Filesystem     1K-blocks   Used Available Use% Mounted on\n" +
            "/dev/block/vold/public:179,65 31191040 524288 30666752   2% /storage/3439-3138"

        val room = parseDf(output)!!

        assertEquals(30460, room.totalMb)
        assertEquals(29948, room.freeMb)
    }

    @Test
    fun aPublicRowStillNamesTheVolumeWhenItIsUnmounted() {
        val listing = """
            public:8,1 unmounted 7000-8000
            public:179,65 mounted 3439-3138
        """.trimIndent()

        assertEquals("public:8,1", volumeIdFor(listing, "7000-8000"))
        assertEquals(listOf("public:8,1"), mountIds(listing, "7000-8000"))
        assertEquals(
            listOf("public:8,1", "public:179,65"),
            mountIds("public:8,1 unmounted null\npublic:179,65 mounted 3439-3138", "7000-8000")
        )
        assertEquals("7000-8000", storageUuid("/storage/7000-8000/Strike/clips"))
        assertEquals(null, storageUuid("/storage/emulated/0/Strike/clips"))
    }

    @Test
    fun anUnmountedCardStillNamesTheChosenVolume() {
        val listing = """
            public:8,1 unmounted 7000-8000
            public:179,65 mounted 3439-3138
        """.trimIndent()

        assertEquals("/storage/7000-8000", volumePathFor("usb", listing, null))
        assertEquals("/storage/3439-3138", volumePathFor("sd", listing, null))
        assertEquals("/storage/7000-8000", volumePathFor("sd", listing, "7000-8000"))
        assertEquals("/storage/emulated/0", volumePathFor("internal", listing, null))
        assertEquals(listOf("public:8,1", "public:179,65"), allPublicIds(listing))
    }

    @Test
    fun aVolumeThatCannotBeMeasuredIsNotOffered() {
        assertNull(parseDf("df: /storage/3439-3138: No such file or directory"))
        assertTrue(parseVolumes("", "3439-3138").isEmpty())
    }
}

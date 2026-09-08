package com.strike.surveillance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun listIsNewestFirstAndSkipsRecordings() {
        write("event_20260905_180000.mp4")
        write("watch_20260905_193000.mp4")
        write("drive_20260905_200000.mp4")
        write("event_20260904_090000.mp4")

        val events = EventStore(folder.root).list()

        assertEquals(3, events.size)
        assertEquals("watch_20260905_193000.mp4", events[0].id)
        assertEquals("event_20260905_180000.mp4", events[1].id)
        assertEquals("event_20260904_090000.mp4", events[2].id)
    }

    @Test
    fun nameCarriesKindDateAndTime() {
        write("event_20260905_184530.mp4")
        val event = EventStore(folder.root).list().single()

        assertEquals(EventKind.EVENT, event.kind)
        assertEquals("2026-09-05", event.date)
        assertEquals("18:45", event.time)
    }

    @Test
    fun theContinuousTapeIsItsOwnKind() {
        write("watch_20260905_184530.mp4")
        assertEquals(EventKind.WATCH, EventStore(folder.root).list().single().kind)
    }

    @Test
    fun anUnflaggedClipSaysNothingWasSeen() {
        write("event_20260905_184530.mp4")
        val event = EventStore(folder.root).list().single()

        assertNull(event.seen)
        assertEquals(0f, event.score, 0f)
    }

    @Test
    fun flaggingNamesWhatWasSeenAndKeepsTheHero() {
        write("event_20260905_184530.mp4")
        val store = EventStore(folder.root)

        store.flag("event_20260905_184530.mp4", PERSON, 0.82f, byteArrayOf(1, 2, 3))
        val event = store.list().single()

        assertEquals(PERSON, event.seen)
        assertEquals(0.82f, event.score, 0.001f)
        assertEquals(3, store.hero("event_20260905_184530.mp4")!!.length())
    }

    @Test
    fun aClipWithNoHeroHasNone() {
        write("event_20260905_184530.mp4")
        val store = EventStore(folder.root)

        store.flag("event_20260905_184530.mp4", VEHICLE, 0.5f, null)

        assertEquals(VEHICLE, store.list().single().seen)
        assertNull(store.hero("event_20260905_184530.mp4"))
    }

    @Test
    fun aLaterSightingKeepsTheEarlierTimeline() {
        val name = "event_20260905_184530.mp4"
        write(name)
        val store = EventStore(folder.root)
        store.flag(name, PERSON, 0.8f, null)
        store.mark(name, 1_000L, listOf(Mark(1_500L, PERSON)))

        store.flag(name, VEHICLE, 0.9f, null)
        store.mark(name, 1_000L, listOf(Mark(3_000L, VEHICLE)))

        val event = store.list().single()
        assertEquals(VEHICLE, event.seen)
        assertEquals(2, event.bands.size)
        assertEquals(PERSON, event.bands[0].seen)
        assertEquals(500L, event.bands[0].startMs)
        assertEquals(VEHICLE, event.bands[1].seen)
    }

    @Test
    fun deletingTakesTheSidecarsWithIt() {
        write("event_20260905_184530.mp4")
        val store = EventStore(folder.root)
        store.flag("event_20260905_184530.mp4", PERSON, 0.9f, byteArrayOf(7))

        assertTrue(store.delete("event_20260905_184530.mp4"))

        assertTrue(store.list().isEmpty())
        assertFalse(File(folder.root, "event_20260905_184530.json").exists())
        assertFalse(File(folder.root, "event_20260905_184530.jpg").exists())
    }

    @Test
    fun onlyRealEventsResolveToAFile() {
        write("event_20260905_180000.mp4")
        val store = EventStore(folder.root)

        assertEquals("event_20260905_180000.mp4", store.file("event_20260905_180000.mp4")!!.name)
        assertNull(store.file("drive_20260905_180000.mp4"))
        assertNull(store.file("event_20260905_996530.mp4"))
        assertNull(store.file("../../local.properties"))
    }

    @Test
    fun aMissingDirectoryListsNothing() {
        assertTrue(EventStore(File(folder.root, "never-mounted")).list().isEmpty())
    }

    private fun write(name: String) {
        folder.newFile(name).writeText(name)
    }

    @Test
    fun dashboardCountsClipsAndPreviewsTheNewestAcrossBothKinds() {
        write("event_20260905_180000.mp4")
        write("watch_20260905_193000.mp4")
        write("drive_20260905_200000.mp4")
        folder.newFolder("event_20260905_210000.mp4")
        val store = EventStore(folder.root)
        store.flag("watch_20260905_193000.mp4", PERSON, 0.8f, byteArrayOf(1))

        val summary = store.summary()

        assertEquals(2, summary.clips)
        assertEquals("watch_20260905_193000.mp4", summary.latest!!.id)
        assertEquals(PERSON, summary.latest.seen)
    }

    @Test
    fun dashboardWithoutStorageHasNoLatestClip() {
        val summary = EventStore(File(folder.root, "unmounted")).summary()
        assertEquals(0, summary.clips)
        assertNull(summary.latest)
    }
}

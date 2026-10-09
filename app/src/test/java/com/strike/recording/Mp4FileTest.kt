package com.strike.recording

import com.strike.daemon.AudioConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

private const val FRAME_US = 66_666L
private const val AAC_FRAME_US = 21_333L
private val AAC = AudioConfig(48_000, 1, 64_000, hex("1188"))

class Mp4FileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun clipIndexSitsBeforeMediaData() {
        val clip = finished(frames = 30)
        val types = topLevel(clip).map { it.type }
        assertEquals("ftyp", types.first())
        assertTrue(types.indexOf("moov") < types.indexOf("mdat"))
    }

    @Test
    fun everySampleOffsetPointsAtItsBytes() {
        val file = File(folder.root, "clip.mp4")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), AAC, 60_000L)
        val audio = ArrayList<ByteArray>()
        for (i in 0 until 30) {
            written.writeVideo(frame(i, i % 15 == 0), i * FRAME_US, i % 15 == 0)
            val sound = ByteArray(12) { (i + 7).toByte() }
            audio.add(sound)
            written.writeAudio(sound, i * FRAME_US + 1)
        }
        assertTrue(written.finish())
        val clip = file.readBytes()
        val (video, sound) = tracks(clip)

        for (i in 0 until 30) {
            val unit = frame(i, i % 15 == 0).copyOfRange(4, 45)
            val at = video.offsets[i].toInt()
            assertEquals(unit.size.toLong(), u32(clip, at.toLong()))
            assertArrayEquals(unit, clip.copyOfRange(at + 4, at + 4 + unit.size))
            assertArrayEquals(audio[i], clip.copyOfRange(sound.offsets[i].toInt(), sound.offsets[i].toInt() + 12))
        }
    }

    @Test
    fun keyframesAreListedAsSyncSamples() {
        val video = tracks(finished(frames = 40)).single()
        assertEquals(listOf(1, 16, 31), video.syncSamples)
    }

    @Test
    fun audioAndVideoDurationsMatchTimestamps() {
        val file = File(folder.root, "clip.mp4")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), AAC, 60_000L)
        for (i in 0 until 30) written.writeVideo(frame(i, i == 0), i * 66_667L, i == 0)
        for (i in 0 until 94) written.writeAudio(ByteArray(8) { 1 }, i * 1_000_000L * 1024 / 48_000)
        assertTrue(written.finish())
        val (video, sound) = tracks(file.readBytes())

        assertEquals("vide", video.handler)
        assertEquals(90_000L, video.timescale)
        assertEquals(180_000L, video.durationTicks, 30.0)
        assertEquals("soun", sound.handler)
        assertEquals(48_000L, sound.timescale)
        assertEquals(94L * 1024, sound.durationTicks, 2.0)
    }

    @Test
    fun lateAudioStartGetsAnEmptyEdit() {
        val file = File(folder.root, "clip.mp4")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), AAC, 60_000L)
        for (i in 0 until 30) written.writeVideo(frame(i, i == 0), 10_000_000L + i * FRAME_US, i == 0)
        for (i in 0 until 40) written.writeAudio(ByteArray(8) { 1 }, 10_500_000L + i * AAC_FRAME_US)
        assertTrue(written.finish())
        val (video, sound) = tracks(file.readBytes())

        assertNull(video.edits)
        assertEquals(500L to 0xFFFF_FFFFL, sound.edits!!.first())
        assertEquals(0L, sound.edits!![1].second)
    }

    @Test
    fun aClipCutOffMidWriteKeepsEverythingUpToItsLastCheckpoint() {
        val file = File(folder.root, "clip.mp4.tmp")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), null, 60_000L)
        val frameUs = CHECKPOINT_US / 240
        for (i in 0 until 680) written.writeVideo(frame(i, i % 16 == 0, 2_000), i * frameUs, i % 16 == 0)
        val cut = file.readBytes()
        val checkpointed = 481
        val sealed = File(folder.root, "whole.mp4.tmp").also { it.writeBytes(cut) }
        assertTrue(sealUnfinished(sealed))
        val saved = tracks(sealed.readBytes()).single().let { it.offsets.last() + it.sizes.last() }
        val unsaved = (cut.size - saved).toInt()
        assertTrue("media written after the checkpoint reached the file", unsaved > 0)

        for (lostBytes in listOf(0, 1, unsaved / 2, unsaved)) {
            val copy = File(folder.root, "cut-$lostBytes.mp4.tmp")
            copy.writeBytes(cut.copyOf(cut.size - lostBytes))
            assertFalse(lacksClipIndex(copy))
            assertTrue(sealUnfinished(copy))

            val clip = copy.readBytes()
            val video = tracks(clip).single()
            assertEquals(checkpointed, video.sizes.size)
            assertTrue(video.offsets.last() + video.sizes.last() <= clip.size)
            assertEquals(topLevel(clip).last().at + topLevel(clip).last().size, clip.size.toLong())
        }
    }

    @Test
    fun aClipCutOffBeforeItsFirstCheckpointHasNoIndex() {
        val file = File(folder.root, "clip.mp4.tmp")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), null, 60_000L)
        for (i in 0 until 20) written.writeVideo(frame(i, i == 0, 20_000), i * FRAME_US, i == 0)

        assertTrue(lacksClipIndex(file))
        assertFalse(sealUnfinished(file))
    }

    @Test
    fun nearlyFullAsksForRotationBeforeOverflow() {
        val file = File(folder.root, "clip.mp4")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), null, 30_000L)
        var i = 0
        while (!written.isNearlyFull) {
            assertTrue(written.writeVideo(frame(i, i % 15 == 0), i * FRAME_US, i % 15 == 0))
            i++
        }
        val roomAfterWarning = (0 until 1_000).count { written.writeVideo(frame(i + it, false), (i + it) * FRAME_US, false) }
        assertTrue(written.finish())

        assertTrue("only $roomAfterWarning samples fit after the warning", roomAfterWarning >= 9 * 30)
        assertTrue(roomAfterWarning < 1_000)
        assertEquals(i + roomAfterWarning, tracks(file.readBytes()).single().sizes.size)
    }

    @Test
    fun theLongestClipAtTheFastestRateWithAudioRunsItsFullLength() {
        val file = File(folder.root, "clip.mp4")
        val clipMs = 10 * 60_000L
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), AAC, clipMs + 30_000L)
        val frameUs = 1_000_000L / 30
        var audioAt = 0L
        var frames = 0
        while (frames * frameUs < clipMs * 1000) {
            assertTrue(written.writeVideo(frame(frames, frames % 30 == 0, 4), frames * frameUs, frames % 30 == 0))
            assertFalse("asked to end at ${frames * frameUs / 1_000_000} s", written.isNearlyFull)
            while (audioAt <= frames * frameUs) {
                assertTrue(written.writeAudio(ByteArray(4) { 1 }, audioAt))
                audioAt += AAC_FRAME_US
            }
            frames++
        }
        assertTrue(written.finish())
        assertEquals(frames, tracks(file.readBytes())[0].sizes.size)
    }

    @Test
    fun everySnapshotTakenWhileWritingIsPlayableOnceSealed() {
        val file = File(folder.root, "clip.mp4.tmp")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), AAC, 120_000L)
        val frameUs = CHECKPOINT_US / 240
        var audioAt = 0L
        var saved = 0
        for (i in 0 until 1_000) {
            written.writeVideo(frame(i, i % 16 == 0, 3_000), i * frameUs, i % 16 == 0)
            while (audioAt <= i * frameUs) {
                written.writeAudio(ByteArray(200) { 3 }, audioAt)
                audioAt += AAC_FRAME_US
            }
            if (i % 37 != 0) continue
            val copy = File(folder.root, "snapshot-$i.mp4.tmp")
            copy.writeBytes(file.readBytes())
            if (!sealUnfinished(copy)) {
                assertTrue("frame $i had a saved index but no seal", i < 240)
                continue
            }
            val clip = copy.readBytes()
            topLevel(clip)
            for (track in tracks(clip)) {
                assertTrue(track.offsets.indices.all { track.offsets[it] + track.sizes[it] <= clip.size })
            }
            val shown = tracks(clip)[0].sizes.size
            assertTrue(shown >= saved)
            saved = shown
        }
        assertTrue(written.finish())
        assertEquals(1_000, tracks(file.readBytes())[0].sizes.size)
    }

    @Test
    fun bFramesWriteCompositionOffsets() {
        val file = File(folder.root, "clip.mp4")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), null, 60_000L)
        val shown = listOf(0, 3, 1, 2, 6, 4, 5)
        shown.forEachIndexed { i, at -> written.writeVideo(frame(i, i == 0), at * 100_000L, i == 0) }
        assertTrue(written.finish())
        val video = tracks(file.readBytes()).single()

        val decoded = video.durations.runningFold(0L) { sum, it -> sum + it }.take(shown.size)
        val presented = decoded.indices.map { decoded[it] + video.compositions!![it] }
        assertEquals(shown.map { it * 9_000L }, presented.map { it - presented.min() })
        assertTrue(video.durations.all { it == 9_000L })
        assertEquals(9_000L, video.edits!!.single().second)
        assertEquals(700L, video.edits!!.single().first)
    }

    @Test
    fun offsetsPastFourGigabytesUseSixtyFourBitTables() {
        val track = TrackIndex(90_000, avc().sampleEntry, isVideo = true)
        track.add(5_000_000_000L, 100, 0L, true)
        track.add(5_000_000_100L, 100, FRAME_US, false)
        val moov = movieIndex(listOf(track))
        val video = tracks(moov).single()

        assertTrue(video.wideOffsets)
        assertEquals(listOf(5_000_000_000L, 5_000_000_100L), video.offsets)
    }

    @Test
    fun aClipWithoutVideoIsNotFinished() {
        val file = File(folder.root, "clip.mp4")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), AAC, 60_000L)
        written.writeAudio(ByteArray(8), 0L)
        assertFalse(written.finish())
    }

    private fun finished(frames: Int): ByteArray {
        val file = File(folder.root, "clip.mp4")
        val written = Mp4File(RandomAccessFile(file, "rw"), avc(), null, 60_000L)
        for (i in 0 until frames) written.writeVideo(frame(i, i % 15 == 0), i * FRAME_US, i % 15 == 0)
        assertTrue(written.finish())
        return file.readBytes()
    }

    private fun avc() = avcTrack(320, 240, AVC_SPS, AVC_PPS)
}

private fun assertEquals(expected: Long, actual: Long, tolerance: Double) =
    org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), tolerance)

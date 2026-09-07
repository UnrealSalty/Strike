package com.strike.surveillance

import com.strike.camera.FrameBus
import com.strike.camera.FrameSink
import com.strike.camera.Mosaic
import com.strike.core.Config
import com.strike.daemon.DaemonLog
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "Sentry"
private const val POLL_MS = 200L

/** Twice a second is enough to catch someone walking up and cheap to read back. */
internal const val SAMPLE_MS = 500L

/** Bounds one clip's timeline at the 2 minute clip cap, twice a second. */
private const val MARK_CAP = 240

/** Quiet looks after arm, so parked cars are scenery before the first event. */
private const val SEED_MS = 4_000L

/** What the daemon writes beside an event clip once it has a name. */
class Flag(val seen: String, val score: Float, val hero: ByteArray?)

/** When one sighting happened, so the player can band the clip's timeline. */
class Mark(val atMs: Long, val seen: String)

/**
 * Smart mode, running in the daemon because that is where the frames are.
 * Block motion first, then YOLO on the frame that moved, and only a confirmed
 * person or vehicle writes a clip.
 */
class Sentry(apkPath: String?, private val screen: RedScreen) {

    private val sink = FrameSink(SAMPLE_MS)
    private val motion = MotionDetector()
    private val yolo = Yolo(apkPath)
    private val scene = Scene()

    private var bus: FrameBus? = null
    private var thread: Thread? = null
    private var armedAtMs = 0L
    private var lastMissAtMs = 0L

    @Volatile
    private var generation = 0L

    @Volatile
    private var armed = false

    private val flag = AtomicReference<Flag?>()

    private val marks = ConcurrentLinkedQueue<Mark>()

    @Volatile
    var triggeredUntilMs = 0L
        private set

    @Volatile
    var events = 0
        private set

    /** Pushed by the app each poll. Preview does not read this; an event does. */
    @Volatile
    var screenOn = Config.getBool(SurveillanceSettings.SCREEN, false)

    val isArmed: Boolean get() = armed

    /** True once the model is loaded. False means motion alone writes the clip. */
    val isConfirming: Boolean get() = yolo.isOpen

    @Synchronized
    fun arm(bus: FrameBus) {
        if (armed || thread?.isAlive == true) return
        sink.start(bus)
        this.bus = bus
        armed = true
        lastMissAtMs = 0L
        armedAtMs = System.currentTimeMillis()
        triggeredUntilMs = 0L
        generation++
        thread = Thread({
            try {
                watch()
            } finally {
                yolo.close()
            }
        }, "sentry").also { it.start() }
        DaemonLog.d(TAG, "armed")
    }

    /**
     * The HAL stalled and the strip was reopened. Keep the trigger window
     * and the red layer; only the pixels come from the new bus.
     */
    @Synchronized
    fun rebind(next: FrameBus) {
        if (!armed || bus === next) return
        sink.stop(bus)
        sink.start(next)
        bus = next
        armedAtMs = System.currentTimeMillis()
        generation++
        DaemonLog.d(TAG, "camera reopened, still watching")
    }

    @Synchronized
    fun disarm() {
        if (!armed) return
        armed = false
        generation++
        sink.stop(bus)
        bus = null
        triggeredUntilMs = 0L
        screen.hide()
        DaemonLog.d(TAG, "stood down")
    }

    /** Taken once, by the supervisor, as soon as the event clip has a name. */
    fun takeFlag(): Flag? = flag.getAndSet(null)

    /**
     * Drained every supervisor pass so the queue stays short, into a list the
     * supervisor holds until the clip it belongs to has a name.
     */
    fun takeMarks(into: MutableList<Mark>) {
        while (true) {
            val mark = marks.poll() ?: break
            if (into.size < MARK_CAP) into.add(mark)
        }
    }

    private fun watch() {
        // Loading the model costs a second, and it must not delay arming.
        try {
            if (!yolo.open()) {
                DaemonLog.w(TAG, "no detector on this build, so movement alone will write a clip")
            }
        } catch (e: RuntimeException) {
            DaemonLog.w(TAG, "detector could not load, recording movement: ${e.message}")
        }
        var cameraGeneration = -1L
        var sawFrame = false
        var warnedQuiet = false
        var seedUntilMs = 0L
        while (armed) {
            val sampledGeneration = generation
            if (cameraGeneration != sampledGeneration) {
                cameraGeneration = sampledGeneration
                motion.forget()
                scene.forget()
                sawFrame = false
                warnedQuiet = false
            }
            val mosaic = sink.take()
            if (mosaic == null) {
                if (!sawFrame && !warnedQuiet && System.currentTimeMillis() - armedAtMs > 10_000L) {
                    warnedQuiet = true
                    DaemonLog.w(TAG, "the camera has not given the detector a frame yet")
                }
                Thread.sleep(POLL_MS)
                continue
            }
            if (!sawFrame) {
                sawFrame = true
                seedUntilMs = System.currentTimeMillis() + SEED_MS
                val luma = sampleLuma(mosaic)
                if (luma < 4) {
                    DaemonLog.w(TAG, "first frame is black (luma $luma), rails may not be held")
                } else {
                    DaemonLog.d(TAG, "detector has a frame")
                }
            }
            val proximity = proximity()
            val verdict = motion.evaluate(mosaic, movedShareFor(proximity))
            val seeding = System.currentTimeMillis() < seedUntilMs
            if (!verdict.moved && !seeding) {
                Thread.sleep(POLL_MS)
                continue
            }
            if (!yolo.isOpen) {
                if (verdict.moved) trigger(null, sampledGeneration)
                continue
            }
            val sightings = try {
                yolo.look(mosaic, boxShareFor(proximity))
            } catch (e: RuntimeException) {
                yolo.close()
                DaemonLog.w(TAG, "detector stopped, recording movement: ${e.message}")
                if (verdict.moved) trigger(null, sampledGeneration)
                continue
            }
            val sighting = synchronized(this) {
                if (!armed || generation != sampledGeneration) return@synchronized null
                scene.observe(sightings, quiet = !verdict.moved)
                if (verdict.moved) {
                    movingSighting(sightings, scene, verdict, mosaic.width, mosaic.height)
                } else null
            }
            if (!armed || generation != sampledGeneration) continue
            if (!verdict.moved) {
                Thread.sleep(POLL_MS)
                continue
            }
            if (sightings.isEmpty()) {
                miss("movement, but nothing in range")
                Thread.sleep(POLL_MS)
                continue
            }
            if (sighting == null) {
                miss("movement, but the people and vehicles in range are still")
                Thread.sleep(POLL_MS)
                continue
            }
            trigger(sighting, sampledGeneration, boxed(mosaic, sighting))
        }
    }

    private fun boxed(mosaic: Mosaic, sighting: Sighting): ByteArray? = try {
        heroJpeg(mosaic, sighting)
    } catch (e: RuntimeException) {
        DaemonLog.w(TAG, "the still would not draw: ${e.message}")
        null
    }

    @Synchronized
    private fun trigger(sighting: Sighting?, sampledGeneration: Long, hero: ByteArray? = null) {
        if (!armed || generation != sampledGeneration) return
        val now = System.currentTimeMillis()
        val first = triggeredUntilMs < now
        if (sighting != null) flag.set(Flag(sighting.seen, sighting.score, hero))
        triggeredUntilMs = now + EVENT_TAIL_MS
        marks.add(Mark(now, sighting?.seen ?: "movement"))
        if (screenOn) {
            val text = message()
            val hold = seconds()
            Thread({
                synchronized(this) {
                    if (armed && generation == sampledGeneration) screen.show(text, hold)
                }
            }, "deterrent").start()
        } else if (first) {
            DaemonLog.d(TAG, "the red screen is off")
        }
        if (!first) return
        events++
        val what = sighting?.seen ?: "movement"
        DaemonLog.d(TAG, "event: $what")
    }

    private fun miss(line: String) {
        val now = System.currentTimeMillis()
        if (now - lastMissAtMs <= 5_000L) return
        lastMissAtMs = now
        DaemonLog.d(TAG, line)
    }

    private fun proximity(): Int = setting(SurveillanceSettings.PROXIMITY).toInt()

    private fun seconds(): Int = setting(SurveillanceSettings.SCREEN_SECONDS).toInt()

    private fun message(): String =
        Config.getString(SurveillanceSettings.MESSAGE, SurveillanceSettings.MESSAGE_FALLBACK)

    private fun setting(key: String): String =
        Config.getString(key, SurveillanceSettings.fallback(key))
}

internal fun boxMovedShare(seen: String): Float = if (seen == VEHICLE) 0.12f else 0.10f

internal fun movingSighting(
    sightings: List<Sighting>,
    scene: Scene,
    verdict: Verdict,
    mosaicWidth: Int,
    mosaicHeight: Int
): Sighting? {
    var person: Sighting? = null
    var vehicle: Sighting? = null
    for (sighting in sightings) {
        if (scene.isParked(sighting)) continue
        if (!verdict.movedInBox(
                sighting.x, sighting.y, sighting.width, sighting.height,
                mosaicWidth, mosaicHeight, boxMovedShare(sighting.seen)
            )
        ) continue
        if (sighting.seen == PERSON) {
            if (person == null || sighting.score > person.score) person = sighting
        } else {
            if (vehicle == null || sighting.score > vehicle.score) vehicle = sighting
        }
    }
    return person ?: vehicle
}

/** Mean luma of a few samples. All-zero AVM frames after ACC off sit at 0. */
internal fun sampleLuma(mosaic: Mosaic): Int {
    val bytes = mosaic.rgba
    if (bytes.size < 4) return 0
    val step = ((bytes.size / 64).coerceAtLeast(4) / 4) * 4
    var total = 0L
    var n = 0
    var i = 0
    while (i + 2 < bytes.size) {
        val red = bytes[i].toInt() and 0xFF
        val green = bytes[i + 1].toInt() and 0xFF
        val blue = bytes[i + 2].toInt() and 0xFF
        total += (red * 77 + green * 151 + blue * 28) shr 8
        n++
        i += step
    }
    return if (n == 0) 0 else (total / n).toInt()
}

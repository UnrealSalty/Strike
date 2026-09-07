package com.strike.recording

import android.content.Context
import com.strike.core.Config
import com.strike.daemon.DaemonClient
import com.strike.daemon.Shell
import com.strike.surveillance.EventStorage
import com.strike.surveillance.LOCK_FALLBACK_MS
import com.strike.vehicle.VehicleSnapshot
import com.strike.vehicle.VehicleTelemetry

private const val EVERY_MS = 5_000L
private const val PARKED = "P"
private const val OFF = "off"

/** Supplies app-side telemetry, cabin audio and mounted storage to the daemon. */
class Triggers(context: Context) {

    private val vehicle = VehicleTelemetry(context)
    private val daemon = DaemonClient()
    private val shell = Shell(context)
    private val events = EventStorage(context, shell)
    private val clips = Storage(context, shell)
    private val cabin = CabinAudio()

    fun start() {
        Thread({ watch() }, "triggers").also { it.isDaemon = true }.start()
    }

    private fun watch() {
        while (true) {
            val snapshot = vehicle.parkingSnapshot()
            val connected = daemon.vehicle(snapshot)
            superviseAudio(connected && shouldRecord(mode(), snapshot), snapshot)
            if (snapshot.accOn != true) {
                if (snapshot.accOn == false) remountCards()
                events.publish(shell)
                clips.publish(shell)
            }
            Thread.sleep(EVERY_MS)
        }
    }

    /**
     * Held from the moment recording is wanted rather than once it is running:
     * a muxer cannot add a track after it starts, so audio that arrives after
     * the daemon opened the clip misses it entirely.
     *
     * The car must say it is awake. A car that will not say is treated as
     * parked here, because nobody is in it to record.
     */
    private fun superviseAudio(shouldRecord: Boolean, snapshot: VehicleSnapshot?) {
        val wanted = shouldRecord && snapshot?.accOn == true &&
            Config.getBool(RecordingSettings.AUDIO, false)
        if (wanted && !cabin.isCapturing) cabin.start()
        if (!wanted && cabin.isCapturing) cabin.stop()
    }

    /** The system drops the card when the key goes out. `sm mount` brings it back. */
    private fun remountCards() {
        val listing = shell.read("sm list-volumes all") ?: return
        for (id in allPublicIds(listing)) shell.check("sm mount $id")
        forgetMounted()
    }

    private fun mode(): String =
        Config.getString(RecordingSettings.MODE, RecordingSettings.fallback(RecordingSettings.MODE))

}

/**
 * Continuous means continuous: the head unit only runs when the car is awake,
 * so a car that reports nothing still gets recorded rather than losing the
 * drive. Driving mode needs the gear, and waits when the car will not say.
 */
internal fun shouldRecord(mode: String, snapshot: VehicleSnapshot?): Boolean = when (mode) {
    "continuous" -> snapshot == null || snapshot.accOn != false
    "driving" -> snapshot != null && snapshot.accOn != false &&
        snapshot.gear != null && snapshot.gear != PARKED
    else -> false
}

// An unknown power reading must not turn a parked session back into a drive.
internal fun driveWanted(mode: String, snapshot: VehicleSnapshot?, wasWanted: Boolean): Boolean =
    if (mode != "continuous" || snapshot?.accOn != null ||
        (snapshot?.gear != null && snapshot.gear != PARKED)) shouldRecord(mode, snapshot) else wasWanted

/**
 * Off while the car is in use. Null means the car will not say — AccOff already
 * armed, and sending off here would drop the parked rails.
 */
internal fun sentryMode(
    enabled: Boolean,
    mode: String,
    snapshot: VehicleSnapshot?,
    arm: String = "off",
    parkedForMs: Long = 0L
): String? {
    if (!enabled) return OFF
    if (snapshot?.accOn == true) return OFF
    if (snapshot?.gear != null && snapshot.gear != PARKED) return OFF
    if (snapshot == null || snapshot.accOn == null) return null
    if (arm == "lock") {
        if (snapshot.locked == true) return mode
        if (snapshot.locked == false) return OFF
        return if (parkedForMs >= LOCK_FALLBACK_MS) mode else OFF
    }
    return mode
}


package com.strike.daemon

import java.io.File

private const val LEGACY_DIR = "/data/local/tmp/strike"
private const val SHELL_DATA_DIR = "/data/user_de/0/com.android.shell"
internal const val DILINK5_MARKER = "/system/lib64/libais_test_util.so"

// DiLink 5 firmware such as the Shark 6 denies the shell /data/local/tmp. The shell's own
// data dir carries the same label there, and the marker reads the same in the app and the shell.
internal fun strikeDir(diLink5: Boolean): String = if (diLink5) "$SHELL_DATA_DIR/strike" else LEGACY_DIR

/** Shared paths are shell-owned and app-readable; the app cannot write them. */
val STRIKE_DIR = strikeDir(File(DILINK5_MARKER).exists())

val CONFIG_PATH = "$STRIKE_DIR/config.json"
val CAM_LOG_PATH = "$STRIKE_DIR/cam.log"
val CAM_LOCK_PATH = "$STRIKE_DIR/cam.lock"
val CAM_SENTINEL_PATH = "$STRIKE_DIR/cam.disabled"
val CAM_SCRIPT_PATH = "$STRIKE_DIR/start_cam.sh"
val CAM_WATCHDOG_PID_PATH = "$STRIKE_DIR/cam_watchdog.pid"
internal val PANEL_LOCK_PATH = "$STRIKE_DIR/parked-panel.lock"
val PIN_RESET_PATH = if (STRIKE_DIR == LEGACY_DIR) "/data/local/tmp/.strike_pin_reset" else "$STRIKE_DIR/pin_reset"

/** Runs after `mkdir -p $STRIKE_DIR`: the shell data dir ships 0700, and settings follow the move once. */
internal fun strikeDirSetup(dir: String = STRIKE_DIR): String =
    if (dir == LEGACY_DIR) "true"
    else "{ chmod 711 $SHELL_DATA_DIR; [ -f $dir/config.json ] || [ ! -r $LEGACY_DIR/config.json ] || " +
        "{ cp $LEGACY_DIR/config.json $dir/config.json && chmod 644 $dir/config.json; }; }"

/** Overdrive holds 19876 on this same head unit and both may be installed. */
const val COMMAND_PORT = 19886

const val PACKET_PORT = 19887

// The app captures cabin audio; the daemon muxes it, so AAC crosses a separate socket.
const val AUDIO_PORT = 19888

const val CAM_PROCESS = "strike_cam"

/** The daemon and the watchdog script must read this the same way. */
const val EXIT_ALREADY_RUNNING = 3

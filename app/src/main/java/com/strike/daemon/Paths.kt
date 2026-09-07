package com.strike.daemon

/**
 * Everything the app process and the daemon process must agree on. The app
 * cannot write in this directory, only read, so it is created by the shell at
 * 755 and each file is made world readable as it is written.
 */
const val STRIKE_DIR = "/data/local/tmp/strike"

const val CONFIG_PATH = "$STRIKE_DIR/config.json"
const val CAM_LOG_PATH = "$STRIKE_DIR/cam.log"
const val CAM_LOCK_PATH = "$STRIKE_DIR/cam.lock"
const val CAM_SENTINEL_PATH = "$STRIKE_DIR/cam.disabled"
const val CAM_SCRIPT_PATH = "$STRIKE_DIR/start_cam.sh"
const val CAM_WATCHDOG_PID_PATH = "$STRIKE_DIR/cam_watchdog.pid"

/** Overdrive holds 19876 on this same head unit and both may be installed. */
const val COMMAND_PORT = 19886

/** Encoded live frames, kept off the command port so JSON stays line based. */
const val PACKET_PORT = 19887

/**
 * Cabin audio, travelling the other way. Only the app can open the microphone,
 * only the daemon owns the muxer, so encoded AAC has to cross between them.
 */
const val AUDIO_PORT = 19888

const val CAM_PROCESS = "strike_cam"

/** The daemon and the watchdog script must read this the same way. */
const val EXIT_ALREADY_RUNNING = 3

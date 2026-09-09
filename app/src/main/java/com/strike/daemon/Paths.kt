package com.strike.daemon

// Shared paths are shell-owned and app-readable; the app cannot write /data/local/tmp.
const val STRIKE_DIR = "/data/local/tmp/strike"

const val CONFIG_PATH = "$STRIKE_DIR/config.json"
const val CAM_LOG_PATH = "$STRIKE_DIR/cam.log"
const val CAM_LOCK_PATH = "$STRIKE_DIR/cam.lock"
const val CAM_SENTINEL_PATH = "$STRIKE_DIR/cam.disabled"
const val CAM_SCRIPT_PATH = "$STRIKE_DIR/start_cam.sh"
const val CAM_WATCHDOG_PID_PATH = "$STRIKE_DIR/cam_watchdog.pid"
internal const val PANEL_LOCK_PATH = "$STRIKE_DIR/parked-panel.lock"

/** Overdrive holds 19876 on this same head unit and both may be installed. */
const val COMMAND_PORT = 19886

const val PACKET_PORT = 19887

// The app captures cabin audio; the daemon muxes it, so AAC crosses a separate socket.
const val AUDIO_PORT = 19888

const val CAM_PROCESS = "strike_cam"

/** The daemon and the watchdog script must read this the same way. */
const val EXIT_ALREADY_RUNNING = 3

package com.strike.daemon

// The watchdog writes stdout to cam.log; the app parses the same format.
object DaemonLog {

    fun watchCrashes() {
        val earlier = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            e("Daemon", "uncaught failure on ${thread.name}: $error")
            error.printStackTrace(System.out)
            System.out.flush()
            earlier?.uncaughtException(thread, error)
        }
    }

    fun d(tag: String, message: String) = say("debug", tag, message)

    fun w(tag: String, message: String) = say("warn", tag, message)

    fun e(tag: String, message: String) = say("error", tag, message)

    private fun say(level: String, tag: String, message: String) {
        println("${System.currentTimeMillis()} $level $tag $message")
        System.out.flush()
    }
}

package com.strike

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import com.strike.web.WebUi

private val NEEDED = arrayOf(
    Manifest.permission.READ_EXTERNAL_STORAGE,
    Manifest.permission.WRITE_EXTERNAL_STORAGE,
    Manifest.permission.RECORD_AUDIO
)

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        askForPermissions()
        WebUi.mount(findViewById<WebView>(R.id.webRoot))
    }

    override fun onResume() {
        super.onResume()
        if ((application as StrikeApp).pin.isSet()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * The daemon writes clips as uid 2000 onto the card, but listing, playing
     * and deleting them happens in the app, which cannot read the card at all
     * until this is granted. Cabin audio is captured here for the same reason.
     */
    private fun askForPermissions() {
        val missing = NEEDED.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}

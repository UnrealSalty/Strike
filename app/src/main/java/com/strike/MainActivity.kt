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
        (application as StrikeApp).updates.resume()
        if ((application as StrikeApp).pin.isSet()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        (application as StrikeApp).setup.foreground(hasFocus)
    }

    override fun onPause() {
        (application as StrikeApp).setup.foreground(false)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            (application as StrikeApp).setup.permissionsFinished(
                grantResults.any { it == PackageManager.PERMISSION_GRANTED })
        }
    }

    // Storage access and microphone capture run under the app UID.
    private fun askForPermissions() {
        val missing = NEEDED.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            (application as StrikeApp).setup.permissionsRequested()
            requestPermissions(missing.toTypedArray(), 1)
        }
    }
}

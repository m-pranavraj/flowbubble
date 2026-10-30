package com.flowbubble.app.capture

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class CaptureConsentActivity : ComponentActivity() {
    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            ScreenCaptureService.start(this, result.resultCode, result.data!!)
            Toast.makeText(this, "Screen access is on for this session.", Toast.LENGTH_SHORT).show()
        } else {
            ScreenCapture.setError("Screen access was not granted.")
            Toast.makeText(this, "Screen access stays off.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setDimAmount(0.35f)
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            ScreenCapture.setError("Screen capture is unavailable on this device.")
            finish()
            return
        }
        runCatching { launcher.launch(manager.createScreenCaptureIntent()) }
            .onFailure {
                ScreenCapture.setError("Couldn't open the screen access dialog.")
                finish()
            }
    }
}

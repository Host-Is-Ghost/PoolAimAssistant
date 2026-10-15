package com.example.poolaimassistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var mpManager: MediaProjectionManager
    private val targetGamePackage = "com.miniclip.eightballpool"

    private val screenShareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, AimOverlayService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
            }
            startForegroundService(serviceIntent)
            launchTargetPoolGame()
        } else {
            Toast.makeText(this, "Screen Share required for auto-detection", Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            screenShareLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(64, 64, 64, 64)
        }

        val title = TextView(this).apply {
            text = "🎱 AIM MASTER PRO"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val subtitle = TextView(this).apply {
            text = "Auto-Table Detection • 3-Line • Cushion Compression • Spin Curve"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 64)
        }

        val playBtn = Button(this).apply {
            text = "TAP TO PLAY"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.BLACK)
            setPadding(72, 36, 72, 36)
            setOnClickListener { startOneTapFlow() }
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(playBtn)
        setContentView(root)
    }

    private fun startOneTapFlow() {
        if (Settings.canDrawOverlays(this)) {
            screenShareLauncher.launch(mpManager.createScreenCaptureIntent())
        } else {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun launchTargetPoolGame() {
        val launchIntent = packageManager.getLaunchIntentForPackage(targetGamePackage)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            finish()
        } else {
            Toast.makeText(
                this,
                "Overlay Active! Open your pool game to start auto-tracking.",
                Toast.LENGTH_LONG
            ).show()
            moveTaskToBack(true)
        }
    }
}

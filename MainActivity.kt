package com.example.adblock

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var toggle: Button
    private val handler = Handler(Looper.getMainLooper())

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) startBlocking()
        }

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 26f; gravity = Gravity.CENTER }
        count = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER }
        toggle = Button(this).apply { textSize = 18f }
        val battery = Button(this).apply {
            text = "Keep running in background (battery)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        val hint = TextView(this).apply {
            text = "Tip: notification shade me edit (pencil) kholo aur " +
                "Ad Block, Wi-Fi, Bluetooth tiles add karo."
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        root.addView(status); root.addView(count); root.addView(toggle)
        root.addView(battery); root.addView(hint)
        setContentView(root)

        toggle.setOnClickListener {
            if (AdBlockVpnService.running) {
                startService(
                    Intent(this, AdBlockVpnService::class.java)
                        .setAction(AdBlockVpnService.ACTION_STOP)
                )
            } else {
                val i = VpnService.prepare(this)   // system "Connection request" popup
                if (i != null) vpnPermission.launch(i) else startBlocking()
            }
        }

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startBlocking() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_START)
        )
    }

    private fun render() {
        val on = AdBlockVpnService.running
        status.text = if (on) "Ad Block: ON" else "Ad Block: OFF"
        count.text = "Blocked requests: ${AdBlockVpnService.blockedCount}"
        toggle.text = if (on) "Turn OFF" else "Turn ON"
    }

    override fun onResume() { super.onResume(); handler.post(ticker) }
    override fun onPause() { super.onPause(); handler.removeCallbacks(ticker) }
}

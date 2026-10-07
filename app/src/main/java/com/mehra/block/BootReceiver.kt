package com.example.adblock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val wasOn = AdBlockVpnService.prefs(context).getBoolean("enabled", false)
        if (wasOn && VpnService.prepare(context) == null) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AdBlockVpnService::class.java)
                    .setAction(AdBlockVpnService.ACTION_START)
            )
        }
    }
}

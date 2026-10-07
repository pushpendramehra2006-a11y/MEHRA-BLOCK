package com.example.adblock

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

private fun TileService.launchAndCollapse(i: Intent) {
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) {
        val pi = PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        startActivityAndCollapse(pi)
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(i)
    }
}

/** Ad Block ON/OFF from the notification shade. */
class AdTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (AdBlockVpnService.running) {
            startService(
                Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_STOP)
            )
        } else if (VpnService.prepare(this) == null) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, AdBlockVpnService::class.java).setAction(AdBlockVpnService.ACTION_START)
            )
        } else {
            // first time: need the system VPN permission popup
            launchAndCollapse(Intent(this, MainActivity::class.java))
        }
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (AdBlockVpnService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Ad Block"
            updateTile()
        }
    }
}

/** Opens the system Internet panel (Wi-Fi / mobile data toggles) as a bottom sheet. */
class WifiTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        launchAndCollapse(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
    }
}

/** Opens Bluetooth settings. */
class BluetoothTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }
    override fun onClick() {
        launchAndCollapse(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}

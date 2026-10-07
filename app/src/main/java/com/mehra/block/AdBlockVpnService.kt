package com.example.adblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.service.quicksettings.TileService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local "VPN" that only captures DNS traffic. Nothing leaves the phone except
 * allowed DNS queries (forwarded to UPSTREAM_DNS). Ad domains get NXDOMAIN.
 */
class AdBlockVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.example.adblock.START"
        const val ACTION_STOP = "com.example.adblock.STOP"
        private const val CHANNEL_ID = "adblock"
        private const val NOTIF_ID = 1

        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val UPSTREAM_DNS = "8.8.8.8"

        @Volatile var running = false
        @Volatile var blockedCount = 0

        fun prefs(c: Context) = c.getSharedPreferences("adblock", Context.MODE_PRIVATE)
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private var pool: ExecutorService? = null
    private var blocked: HashSet<String> = HashSet()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        showForeground()
        loadBlocklist()
        blockedCount = 0

        tun = Builder()
            .setSession("Ad Block")
            .addAddress("10.111.222.1", 24)
            .addDnsServer(VIRTUAL_DNS)
            .addRoute(VIRTUAL_DNS, 32)   // only DNS goes through us
            .setBlocking(true)
            .establish()

        if (tun == null) {
            stopSelf()
            return
        }

        running = true
        prefs(this).edit().putBoolean("enabled", true).apply()
        pool = Executors.newFixedThreadPool(8)
        worker = Thread { loop() }.also { it.start() }
        refreshTile()
    }

    private fun stopVpn() {
        running = false
        prefs(this).edit().putBoolean("enabled", false).apply()
        try { tun?.close() } catch (_: IOException) {}
        tun = null
        pool?.shutdownNow()
        worker?.interrupt()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        refreshTile()
    }

    override fun onRevoke() {
        // user turned VPN off from system settings
        stopVpn()
    }

    override fun onDestroy() {
        running = false
        try { tun?.close() } catch (_: IOException) {}
        pool?.shutdownNow()
        refreshTile()
        super.onDestroy()
    }

    // ---------- packet loop ----------

    private fun loop() {
        val fd = tun?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val buf = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(buf)
                if (n <= 0) continue
                handle(buf.copyOf(n), output)
            }
        } catch (_: IOException) {
            // tun closed
        }
    }

    private fun handle(p: ByteArray, out: FileOutputStream) {
        if (p.size < 28) return
        if ((p[0].toInt() shr 4) != 4) return                 // IPv4 only
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p[9].toInt() != 17) return                        // UDP only
        val dstPort = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
        if (dstPort != 53) return

        val query = p.copyOfRange(ihl + 8, p.size)
        if (query.size < 12) return
        val (name, qEnd) = parseQuestion(query) ?: return

        pool?.execute {
            val reply: ByteArray? = if (isBlocked(name)) {
                blockedCount++
                nxdomain(query, qEnd)
            } else {
                forward(query)
            }
            if (reply != null) {
                val pkt = buildPacket(p, ihl, reply)
                try {
                    synchronized(out) { out.write(pkt) }
                } catch (_: IOException) {}
            }
        }
    }

    // ---------- DNS helpers ----------

    /** Returns (domain, index just after the question section) */
    private fun parseQuestion(q: ByteArray): Pair<String, Int>? {
        var i = 12
        val sb = StringBuilder()
        while (i < q.size) {
            val len = q[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if ((len and 0xC0) != 0) return null
            if (i + 1 + len > q.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(q, i + 1, len, Charsets.US_ASCII).lowercase())
            i += 1 + len
        }
        if (i + 4 > q.size) return null
        return Pair(sb.toString(), i + 4)
    }

    private fun isBlocked(name: String): Boolean {
        var d = name
        while (true) {
            if (blocked.contains(d)) return true
            val i = d.indexOf('.')
            if (i < 0) return false
            d = d.substring(i + 1)
        }
    }

    private fun nxdomain(q: ByteArray, qEnd: Int): ByteArray {
        val r = q.copyOfRange(0, qEnd)
        r[2] = 0x81.toByte()   // QR=1, RD=1
        r[3] = 0x83.toByte()   // RA=1, RCODE=3 (NXDOMAIN)
        for (i in 6..11) r[i] = 0   // AN/NS/AR counts = 0
        return r
    }

    private fun forward(q: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { s ->
                protect(s)               // keep this socket outside the VPN
                s.soTimeout = 4000
                s.send(DatagramPacket(q, q.size, InetAddress.getByName(UPSTREAM_DNS), 53))
                val b = ByteArray(4096)
                val rp = DatagramPacket(b, b.size)
                s.receive(rp)
                b.copyOf(rp.length)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Builds an IPv4+UDP packet carrying [dns] back to the app that asked. */
    private fun buildPacket(req: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val total = ihl + 8 + dns.size
        val o = ByteArray(total)
        System.arraycopy(req, 0, o, 0, ihl + 8)
        System.arraycopy(req, 12, o, 16, 4)          // dst = old src
        System.arraycopy(req, 16, o, 12, 4)          // src = old dst
        System.arraycopy(req, ihl, o, ihl + 2, 2)    // dst port = old src port
        System.arraycopy(req, ihl + 2, o, ihl, 2)    // src port = old dst port
        o[2] = (total shr 8).toByte(); o[3] = total.toByte()
        o[8] = 64
        o[10] = 0; o[11] = 0
        val udpLen = 8 + dns.size
        o[ihl + 4] = (udpLen shr 8).toByte(); o[ihl + 5] = udpLen.toByte()
        o[ihl + 6] = 0; o[ihl + 7] = 0               // UDP checksum optional on IPv4
        System.arraycopy(dns, 0, o, ihl + 8, dns.size)

        var sum = 0
        var i = 0
        while (i < ihl) {
            sum += ((o[i].toInt() and 0xFF) shl 8) or (o[i + 1].toInt() and 0xFF)
            i += 2
        }
        while ((sum shr 16) != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val c = sum.inv() and 0xFFFF
        o[10] = (c shr 8).toByte(); o[11] = c.toByte()
        return o
    }

    private fun loadBlocklist() {
        val set = HashSet<String>()
        try {
            assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.forEach { raw ->
                    val line = raw.substringBefore('#').trim()
                    if (line.isNotEmpty()) {
                        val d = line.split(Regex("\\s+")).last().lowercase()
                        if (d != "localhost" && d != "0.0.0.0") set.add(d)
                    }
                }
            }
        } catch (_: IOException) {}
        blocked = set
    }

    // ---------- notification / tile ----------

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ad Block", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, AdBlockVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Ad Block ON")
            .setContentText("Ads are being blocked in all apps")
            .setContentIntent(open)
            .addAction(0, "Turn off", stop)
            .setOngoing(true)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 34)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun refreshTile() {
        try {
            TileService.requestListeningState(this, ComponentName(this, AdTileService::class.java))
        } catch (_: Exception) {}
    }
}

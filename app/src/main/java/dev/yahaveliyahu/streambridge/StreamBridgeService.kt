package dev.yahaveliyahu.streambridge

import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import java.io.File
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service


class StreamBridgeService : Service() {

    companion object {
        private const val TAG = "StreamBridgeService"

        // actions
        const val ACTION_START = "dev.yahaveliyahu.streambridge.action.START"
        const val ACTION_STOP = "dev.yahaveliyahu.streambridge.action.STOP"
        const val ACTION_SEND_LOCAL_FILE = "dev.yahaveliyahu.streambridge.action.SEND_LOCAL_FILE"

        // extras
        const val EXTRA_LOCAL_PATH = "extra_local_path"

        // notification
        private const val CHANNEL_ID = "streambridge_channel"
        private const val NOTIF_ID = 1001


        /** Allow other components to check service state without binding. */
        @Volatile var instance: StreamBridgeService? = null

        /**
         * Set by MainActivity so the pairing AlertDialog appears in the right context.
         * The service forwards every pairing request through this callback.
         */
        var onPairingRequestStatic:
                ((pcName: String, pcIp: String, callback: (Boolean) -> Unit) -> Unit)? = null
    }

    // Using applicationContext to not have an Activity
    private lateinit var serverManager: ServerManager
    private lateinit var discoveryService: DiscoveryService

    // ─────────── Lifecycle ───────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        serverManager = ServerManager(applicationContext)
        discoveryService = DiscoveryService(applicationContext)

        // Forward pairing requests to whoever is currently listening (MainActivity)
        discoveryService.onPairingRequest = { pcName, pcIp, callback ->
            val handler = onPairingRequestStatic
            if (handler != null) handler(pcName, pcIp, callback)
            else callback(false)   // No UI → auto-deny to avoid hanging socket
        }

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startServersIfNeeded()
            ACTION_STOP -> {
                stopServers()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SEND_LOCAL_FILE -> {
                val path = intent.getStringExtra(EXTRA_LOCAL_PATH)
                if (!path.isNullOrBlank()) {
                    startServersIfNeeded() // If we're not running yet, let's lift.
                    sendLocalFile(path)
                }
            }

            // If the Service came up without action
            else -> startServersIfNeeded()
            }
        // If the system kills – return and re-launch
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stopServers()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────── Public API ───────────

    fun isRunning(): Boolean = serverManager.isRunning()

    /**
     * The file MUST already exist at [path] (inside filesDir/shared/).
     * Sends a FILE_TRANSFER WebSocket message so the PC pulls it over HTTP,
     * writes to chat history, and notifies FileBrowserActivity if it is open.
     */
    fun sendLocalFile(path: String) {
        val f = File(path)
        if (!f.exists()) { Log.e(TAG, "sendLocalFile: not found – $path"); return }

        val mime = MimeUtils.getMimeTypeFromName(f.name) ?: "application/octet-stream"
        val now  = System.currentTimeMillis()
        try {
            val json = JSONObject().apply {
                put("type",         "FILE_TRANSFER")
                put("fileName",     f.name)
                put("fileSize",     f.length())
                put("mimeType",     mime)
                put("downloadPath", "/files/shared/${f.name}")
                put("timestamp",    now)
            }
            ServerManager.sendToPC(json.toString())

            ChatHistoryStore.append(applicationContext, JSONObject().apply {
                put("type", "FILE"); put("name", f.name); put("path", f.absolutePath)
                put("size", f.length()); put("mime", mime); put("out", true); put("time", now)
            })

            val bi = Intent("STREAMBRIDGE_CHAT_EVENT").apply { putExtra("message", json.toString()) }
            LocalBroadcastManager.getInstance(this).sendBroadcast(bi)

            Log.d(TAG, "sendLocalFile OK: ${f.name}")
        } catch (e: Exception) { Log.e(TAG, "sendLocalFile error", e) }
    }

    // ─────────── Private helpers ───────────

    private fun startServersIfNeeded() {
        if (serverManager.isRunning()) return

            try {
                serverManager.startServer()
                val ip = getLocalIp()
                discoveryService.startDiscovery(ip, 8080)
                updateNotification("Connected – $ip:8080")
                Log.d(TAG, "Servers started on $ip")
            } catch (e: Exception) { Log.e(TAG, "Failed to start servers", e) }
    }

    private fun stopServers() {
        try { discoveryService.stopDiscovery() } catch (e: Exception) { Log.e(TAG, "stopDiscovery", e) }
        try { serverManager.stopServer()       } catch (e: Exception) { Log.e(TAG, "stopServer",    e) }
    }

    private fun getLocalIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress ?: NetworkUtils.getWifiIp()
    } catch (_: Exception) { NetworkUtils.getWifiIp() }

    // ─────────── Notification ───────────

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamBridge")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    private fun createNotificationChannel() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "StreamBridge Background Service", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
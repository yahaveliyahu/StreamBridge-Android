package dev.yahaveliyahu.streambridge

import android.content.Intent
import android.os.Build
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
import android.net.nsd.NsdManager


class StreamBridgeService : Service() {

    companion object {
        private const val TAG = "StreamBridgeService"
        private const val SERVICE_NAME = "StreamBridge"
        private const val SERVICE_TYPE = "_phonepclink._tcp."

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

    // ✅ שים לב: משתמשים ב-applicationContext כדי לא להחזיק Activity
    private lateinit var serverManager: ServerManager
    private lateinit var discoveryService: DiscoveryService
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

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
//                updateNotification("Running")
            ACTION_STOP -> {
                stopServers()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_SEND_LOCAL_FILE -> {
                val path = intent.getStringExtra(EXTRA_LOCAL_PATH)
                if (!path.isNullOrBlank()) {
                    startServersIfNeeded() // אם עוד לא רצים, נרים
                    sendLocalFile(path)
                }
            }

            // אם ה-Service עלה בלי action
            else -> startServersIfNeeded()
            }
        // ✅ חשוב: אם המערכת תהרוג – תחזיר ותרים מחדש
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
                // לפי מה שהיה אצלך במיין: discovery
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
            ?.hostAddress ?: NetworkUtils.getWifiIp(this)
    } catch (_: Exception) { NetworkUtils.getWifiIp(this) }

//    private fun sendLocalFile(path: String) {
//        val f = File(path)
//        if (!f.exists()) {
//            Log.e(TAG, "File not found: $path")
//            return
//        }

    // ─────────── Notification ───────────

    private fun buildNotification(status: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
                    or PendingIntent.FLAG_UPDATE_CURRENT
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "StreamBridge Background Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

//    private fun createNotificationChannel() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//            val channel = NotificationChannel(
//                CHANNEL_ID,
//                "StreamBridge Background Service",
//                NotificationManager.IMPORTANCE_LOW
//            )
//            nm.createNotificationChannel(channel)
//        }
//    }
//}

//    // ✅ פה אתה מחבר ללוגיקה שלך לשידור למחשב.
//        // אם אצלך השידור מתבצע דרך serverManager / websocketServer וכו'
//        // דוגמה: serverManager.sendFileToPC(f, mime)
//        val mime = MimeUtils.getMimeTypeFromName(f.name) ?: "application/octet-stream"
//
//        try {
//            // תחליף לשם הפונקציה האמיתית שיש אצלך
//            serverManager.sendFileToPC(f, mime)
//            Log.d(TAG, "Sent file to PC: ${f.name} ($mime)")
//        } catch (e: Exception) {
//            Log.e(TAG, "sendLocalFile error", e)
//        }
//    }







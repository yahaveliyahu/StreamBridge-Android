//package dev.yahaveliyahu.streambridge
//
//import android.app.*
//import android.content.Intent
//import android.os.Build
//import android.os.IBinder
//import android.util.Log
//import androidx.core.app.NotificationCompat
//import java.io.File
//
//import android.app.Notification
//import android.app.NotificationChannel
//import android.app.NotificationManager
//import android.app.PendingIntent
//import android.app.Service
//import android.net.nsd.NsdManager
//
//import android.content.Context
//import android.net.nsd.NsdServiceInfo
//import android.net.wifi.WifiManager
//import android.text.format.Formatter
//
//class StreamBridgeService : Service() {
//
//    companion object {
//        private const val TAG = "StreamBridgeService"
//        private const val SERVICE_NAME = "StreamBridge"
//        private const val SERVICE_TYPE = "_phonepclink._tcp."
//
//        // actions
//        const val ACTION_START = "dev.yahaveliyahu.streambridge.action.START"
//        const val ACTION_STOP = "dev.yahaveliyahu.streambridge.action.STOP"
//        const val ACTION_SEND_LOCAL_FILE = "dev.yahaveliyahu.streambridge.action.SEND_LOCAL_FILE"
//
//        // extras
//        const val EXTRA_LOCAL_PATH = "extra_local_path"
//
//        // notification
//        private const val CHANNEL_ID = "streambridge_channel"
//        private const val NOTIF_ID = 1001
//    }
//
//    // ✅ שים לב: משתמשים ב-applicationContext כדי לא להחזיק Activity
//    private lateinit var serverManager: ServerManager
//    private lateinit var discoveryService: DiscoveryService
//
//    private var nsdManager: NsdManager? = null
//    private var registrationListener: NsdManager.RegistrationListener? = null
//
//    override fun onCreate() {
//        super.onCreate()
//        serverManager = ServerManager(applicationContext)
//        discoveryService = DiscoveryService(applicationContext)
//
//        createNotificationChannel()
//        startForeground(NOTIF_ID, buildNotification("Running"))
//    }
//
//    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
//        when (intent?.action) {
//            ACTION_START -> {
//                startServersIfNeeded()
//                updateNotification("Running")
//            }
//
//            ACTION_STOP -> {
//                stopServers()
//                stopForeground(STOP_FOREGROUND_REMOVE)
//                stopSelf()
//            }
//
//            ACTION_SEND_LOCAL_FILE -> {
//                val path = intent.getStringExtra(EXTRA_LOCAL_PATH)
//                if (!path.isNullOrBlank()) {
//                    startServersIfNeeded() // אם עוד לא רצים, נרים
//                    sendLocalFile(path)
//                }
//            }
//
//            else -> {
//                // אם ה-Service עלה בלי action
//                startServersIfNeeded()
//            }
//        }
//
//        // ✅ חשוב: אם המערכת תהרוג – תחזיר ותרים מחדש
//        return START_STICKY
//    }
//
//    private fun startServersIfNeeded() {
//        if (!serverManager.isRunning()) {
//            try {
//                serverManager.startServer()
//
//                // לפי מה שהיה אצלך במיין: discovery
//                val ip = NetworkUtils.getWifiIp(applicationContext)
//                discoveryService.startDiscovery(ip, 8080)
//
//                Log.d(TAG, "Servers started. IP=$ip")
//            } catch (e: Exception) {
//                Log.e(TAG, "Failed to start servers", e)
//            }
//        }
//    }
//
//    private fun stopServers() {
//        try {
//            discoveryService.stopDiscovery()
//        } catch (e: Exception) {
//            Log.e(TAG, "stopDiscovery error", e)
//        }
//
//        try {
//            serverManager.stopServer()
//        } catch (e: Exception) {
//            Log.e(TAG, "stopServer error", e)
//        }
//        Log.d(TAG, "Servers stopped")
//    }
//
//    private fun sendLocalFile(path: String) {
//        val f = File(path)
//        if (!f.exists()) {
//            Log.e(TAG, "File not found: $path")
//            return
//        }
//
//        // ✅ פה אתה מחבר ללוגיקה שלך לשידור למחשב.
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
//
//    override fun onDestroy() {
//        stopServers()
//        super.onDestroy()
//    }
//
//    override fun onBind(intent: Intent?): IBinder? = null
//
//    private fun buildNotification(status: String): Notification {
//        val openAppIntent = Intent(this, MainActivity::class.java)
//        val pi = PendingIntent.getActivity(
//            this, 0, openAppIntent,
//            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT
//        )
//
//        return NotificationCompat.Builder(this, CHANNEL_ID)
//            .setContentTitle("StreamBridge")
//            .setContentText("Server: $status")
//            .setSmallIcon(R.drawable.ic_launcher_foreground) // תחליף לאייקון שלך אם יש
//            .setContentIntent(pi)
//            .setOngoing(true)
//            .build()
//    }
//
//    private fun updateNotification(status: String) {
//        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
//        nm.notify(NOTIF_ID, buildNotification(status))
//    }
//
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

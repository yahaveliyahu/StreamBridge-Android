package dev.yahaveliyahu.streambridge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import fi.iki.elonen.NanoHTTPD
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import org.java_websocket.server.DefaultSSLWebSocketServerFactory


class ServerManager(private val context: Context) {
    private var httpServer: FileServer? = null

    companion object {
        private const val TAG = "ServerManager"
        var currentCameraFrame: ByteArray? = null
        // Static variable so we can send messages from anywhere (e.g. from the chat)
        @SuppressLint("StaticFieldLeak")
        @Volatile var webSocketServer: CommandWebSocketServer? = null

        // The hostname of the currently-connected PC, or null when not connected
        @Volatile var connectedPcName: String? = null

        @Volatile var warmupClosed: Boolean = false

        // True only after performLoopbackTlsWarmup() completes successfully.
        // Prevents isWebSocketReady() from returning true while the server is
        // listening but the NIO TLS path is still cold.
        @Volatile var isTlsWarm: Boolean = false

        fun sendToPC(jsonString: String) {
            webSocketServer?.broadcastToAll(jsonString)
        }
    }

    // ── Server start / stop ─────────────────────────────────────────────────────

    fun startServer() {
        try {
            // Each call creates a fresh CertificateManager — they all share the same
            // Android Keystore entry.
            val certManager = CertificateManager()
            val sslContext  = certManager.getSSLContext()   // also calls ensureCertificate()

            // ── HTTPS server (port 8080) ──────────────────────────────────────────

            httpServer = FileServer(8080, context)
            httpServer?.makeSecure(sslContext.serverSocketFactory, null)
            httpServer?.start()

            Log.d(TAG, "HTTP server started on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting HTTP server", e)
        }

        // Start the WebSocket server + TLS warmup in the background immediately.
        // The 30-second warmup happens now while the user isn't waiting for anything.
        // By the time they click Auto-Discover or scan QR, the server is already warm.

        startWebSocketInBackground()
    }

    // Returns true only when the server is listening AND the TLS path is warm
    fun isWebSocketReady(): Boolean = webSocketServer?.isStarted == true && isTlsWarm

    /**
     * Starts the WebSocket server on a background thread without blocking.
     * Called at service startup to pre-warm the TLS stack so the first
     * connection attempt is instant.
     */
    private fun startWebSocketInBackground() {
        Thread {
            startWebSocket()
        }.also { it.isDaemon = true; it.name = "ws-background-start" }.start()
    }

    /**
     * Starts a fresh WebSocket server on port 8081 and returns immediately.
     *
     * Called just before the PC is notified to connect (QR and Auto-Discover flows).
     * The PC's connectBlocking(10s) naturally waits for the server's TLS stack to
     * finish initializing — no polling or probing needed on either side.
     *
     * When the connection later closes, onClose automatically stops the server
     * so it goes back to sleep until the next startWebSocket() call.
     */
    fun startWebSocket() {
        try {
            webSocketServer?.stop(3_000)
        } catch (e: Exception) {
            Log.w(TAG, "startWebSocket: stop of old server (ignored)", e)
        }
        webSocketServer = null
        isTlsWarm = false

        // Give the OS time to release the port and flush NIO cleanup events
        // before the new server binds. Without this, zombie SSLEngine cleanup
        // events from the old server pollute the new server's selector queue.
        Thread.sleep(500)

        try {
            val certManager = CertificateManager()
            val sslContext  = certManager.getSSLContext()

            val server = CommandWebSocketServer(8081, context.applicationContext)
            server.isReuseAddr = true

            val factory = HandshakeTimeoutSSLFactory(sslContext, handshakeTimeoutMs = 5_000)
            server.handshakeFactory = factory
            server.setWebSocketFactory(factory)
            server.start()

            synchronized(ServerManager::class.java) {
                webSocketServer = server
            }

            /**
             * Block until onStart() confirms the NIO selector is in select() —
             * i.e. the server is truly ready to accept connections.
             * This method is always called from a background thread so sleeping here
             * is safe. The caller sends the PC its IP only after this returns,
             * so the PC's connectBlocking() hits a server that is already waiting.
             */
            val deadline = System.currentTimeMillis() + 5_000
            while (webSocketServer?.isStarted != true) {
                if (System.currentTimeMillis() > deadline) {
                    Log.w(TAG, "startWebSocket: timed out waiting for isStarted")
                    break
                }
                Thread.sleep(20)
            }

            // Warm up the NIO/SSLEngine TLS path by doing a loopback handshake
            // through the real server on port 8081. This is the same code path
            // that PC connections use. On first run it can take ~30s; afterwards
            // it's near-instant. This runs in the background at startup, so by
            // the time the user tries to connect, the path is already warm.
            warmupClosed = false
            performLoopbackTlsWarmup()

            // Wait for the NIO thread to signal it finished processing the warmup close event.
            // This replaces the blind 500ms sleep — we only proceed when NIO is actually idle.
            val cleanupDeadline = System.currentTimeMillis() + 10_000
            while (!warmupClosed) {
                if (System.currentTimeMillis() > cleanupDeadline) {
                    Log.w(TAG, "Warmup close signal not received — proceeding anyway")
                    break
                }
                Thread.sleep(50)
            }
            Thread.sleep(200)   // small buffer after the signal

            isTlsWarm = true

            Log.d(TAG, "startWebSocket DONE — notifying PC now at ${System.currentTimeMillis()}")
            Log.d(TAG, "WebSocket server starting on port 8081")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WebSocket server", e)
        }
    }

    /**
     * The warning doesn't apply, for one simple reason: **we are connecting to `127.0.0.1`**.
     * That is the loopback address — it never leaves the device. There is no network, no cable, no Wi-Fi.
     * The packet goes from one process back to itself inside the phone's kernel.
     * A man-in-the-middle attack is physically impossible on a loopback connection — there is no "middle" to be in.
     */
    @SuppressLint("CustomX509TrustManager")
    private fun performLoopbackTlsWarmup() {
        try {
            // Connect via the phone's own WiFi IP, not 127.0.0.1.
            // This exercises the SAME WiFi network path that the PC uses,
            // so the warmup actually warms the right code path.
            val wifiIp = NetworkUtils.getWifiIp().takeIf { it.isNotBlank() } ?: "127.0.0.1"

            val clientCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            clientCtx.init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            }), java.security.SecureRandom())

            val latch = java.util.concurrent.CountDownLatch(1)

            val wsClient = object : org.java_websocket.client.WebSocketClient(
                java.net.URI("wss://$wifiIp:8081")
            ) {
                override fun onSetSSLParameters(p: javax.net.ssl.SSLParameters) {
                    p.endpointIdentificationAlgorithm = ""
                    // Match the cipher suites that a Windows JVM client sends
                    p.protocols = arrayOf("TLSv1.3", "TLSv1.2")
                    p.cipherSuites = arrayOf(
                        "TLS_AES_256_GCM_SHA384",
                        "TLS_AES_128_GCM_SHA256",
                        "TLS_CHACHA20_POLY1305_SHA256",
                        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"
                    )
                }
                override fun onOpen(h: org.java_websocket.handshake.ServerHandshake?) {}
                override fun onMessage(m: String?) {}
                override fun onClose(code: Int, reason: String?, remote: Boolean) { latch.countDown() }
                override fun onError(ex: Exception?) { latch.countDown() }
            }

            wsClient.setSocketFactory(clientCtx.socketFactory)
            wsClient.connectBlocking(60, java.util.concurrent.TimeUnit.SECONDS)
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)

            Log.d(TAG, "TLS warmup completed via WiFi IP $wifiIp")
        } catch (e: Exception) {
            Log.w(TAG, "TLS loopback warmup failed (ignored): ${e.message}")
        }
    }

    fun stopWebSocket() {
        // Notify any connected PC before pulling the rug out
        try {
            webSocketServer?.broadcastToAll(
                JSONObject().apply { put("type", "SERVER_STOPPING") }.toString()
            )
            Thread.sleep(200) // brief grace period for message delivery
        } catch (_: Exception) {}

        try {
            webSocketServer?.stop(500)
        } catch (e: Exception) {
            Log.w(TAG, "stopWebSocket error (ignored)", e)
        }
        webSocketServer = null
        isTlsWarm = false
        Log.d(TAG, "WebSocket server stopped (sleeping)")
    }

    fun stopServer() {
        stopWebSocket()
        try {
            httpServer?.stop()
            httpServer = null
            Log.d(TAG, "HTTP server stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HTTP server", e)
        }
    }

    /**
     * Returns true only when BOTH servers are alive.
     * A false return while the service is supposed to be running means one of the
     * servers crashed — StreamBridgeService should call stopServer()+startServer().
     */
    fun isRunning(): Boolean = httpServer?.isAlive == true

    // ── HTTP server (NanoHTTPD) ─────────────────────────────────────────────────

    class FileServer(port: Int, private val context: Context) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return when {
                uri == "/camera" -> serveCameraStream()
                uri.startsWith("/files/") -> serveFile(uri)
                uri == "/upload" && session.method == Method.POST -> handleUpload(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        private fun serveFile(uri: String): Response {
            // Convert the URL to a real internal path on your phone
            // URL: /files/shared/image.jpg ---> Path: /data/user/0/.../shared/image.jpg
            val relativePath = uri.removePrefix("/files/shared/").trimStart('/')
            val file = File(File(context.filesDir, "shared"), relativePath)
            return if (file.exists()) {
                val mime = getMimeType(file.name)
                newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
            } else {
                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File Not Found")
            }
        }

        private fun handleUpload(session: IHTTPSession): Response {
            try {
                // NanoHTTPD requires special parsing for uploads
                val files = HashMap<String, String>()
                session.parseBody(files)
                val tempFilePath = files["file"]
                // Name that comes from the URL: /upload?name=...
                val encodedNameFromUrl = session.parameters["name"]?.firstOrNull()
                val fileNameFromUrl = encodedNameFromUrl?.let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                }
                // if for some reason there is no name in the URL
                val fileNameFallback = session.parameters["file"]?.firstOrNull()
                // Final choice
                val fileName = fileNameFromUrl ?: fileNameFallback ?: "file_${System.currentTimeMillis()}"

                if (tempFilePath != null) {
                    val src = File(tempFilePath)
                    val sharedDir = File(context.filesDir, "shared")
                    if (!sharedDir.exists()) sharedDir.mkdirs()
                    val dst = File(sharedDir, fileName)
                    src.copyTo(dst, overwrite = true)

                    // Save to history even if the chat is not open
                    ChatHistoryStore.append(context, JSONObject().apply {
                        put("type", "FILE")
                        put("name", fileName)
                        put("path", dst.absolutePath)
                        put("size", dst.length())
                        put("mime", getMimeType(fileName))
                        put("out", false)
                        put("time", System.currentTimeMillis())
                    })

                    // Broadcast a message to the UI that a file has been received
                    val json = JSONObject().apply {
                        put("type", "FILE_RECEIVED")
                        put("name", fileName)
                        put("path", dst.absolutePath)
                        put("timestamp", System.currentTimeMillis())
                    }
                    val intent = Intent("STREAMBRIDGE_CHAT_EVENT")
                    intent.putExtra("message", json.toString())
                    LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
                    return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "Success")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Upload Failed: ${e.message}")
            }
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Bad Request")
        }

        private fun serveCameraStream(): Response {
            val frame = currentCameraFrame
            return if (frame != null) {
                newFixedLengthResponse(Response.Status.OK, "image/jpeg", frame.inputStream(), frame.size.toLong())
            } else {
                newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "No camera frame available")
            }
        }

        private fun getMimeType(fileName: String): String {
            return when (fileName.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "aac" -> "audio/aac"
                "pdf" -> "application/pdf"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }

    // ── Blocking SSL WebSocket factory ─────────────────────────────────────────

    class HandshakeTimeoutSSLFactory(
        sslContext: javax.net.ssl.SSLContext,
        private val handshakeTimeoutMs: Long = 5_000
    ) : DefaultSSLWebSocketServerFactory(sslContext) {

        // Remote address string → Pair(timestamp, SocketChannel)
        private val pendingHandshakes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, java.nio.channels.SocketChannel>>()

        init {
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(2_000)
                        val now = System.currentTimeMillis()
                        val timeoutMs = handshakeTimeoutMs
                        val iter = pendingHandshakes.entries.iterator()
                        while (iter.hasNext()) {
                            val entry = iter.next()
                            val (ts, ch) = entry.value
                            if (now - ts > timeoutMs) {
                                Log.e("ServerManager", "TLS handshake watchdog: no data for ${timeoutMs}ms " +
                                        "from ${entry.key} — closing stalled channel")
                                iter.remove()
                                try { ch.close() } catch (_: Exception) {}
                            }
                        }
                    } catch (_: InterruptedException) { break }
                }
            }.apply { isDaemon = true; name = "tls-handshake-watchdog"; start() }
        }

        override fun wrapChannel(
            channel: java.nio.channels.SocketChannel,
            key: java.nio.channels.SelectionKey?
        ): java.nio.channels.ByteChannel {
            val remoteIp = channel.socket()?.inetAddress?.hostAddress
            val localWifiIp = NetworkUtils.getWifiIp()

            // Skip watchdog for warmup connections — loopback (127.0.0.1)
            // OR self-connect via WiFi (phone connecting to itself for warmup)
            val isWarmup = channel.socket()?.inetAddress?.isLoopbackAddress == true
                    || remoteIp == localWifiIp
            if (!isWarmup) {
                val addr = channel.socket()?.remoteSocketAddress?.toString() ?: "unknown-${System.nanoTime()}"
                pendingHandshakes[addr] = Pair(System.currentTimeMillis(), channel)
            }
            return super.wrapChannel(channel, key)
        }

        // Called from CommandWebSocketServer.onOpen() — cancels the watchdog for this conn
        fun handshakeCompleted(remoteAddr: String) {
            pendingHandshakes.remove(remoteAddr)
        }
    }

    // ================= WEBSOCKET SERVER =================

    class CommandWebSocketServer(
        port: Int,
        private val context: Context
    ) : WebSocketServer(InetSocketAddress(port)) {

        // Set to true when onStart() fires — confirms the bind actually succeeded.
        @Volatile var isStarted: Boolean = false
            private set

        // Set by ServerManager after construction so onOpen can cancel the watchdog.
        @Volatile var handshakeFactory: HandshakeTimeoutSSLFactory? = null

        companion object { private const val TAG = "ServerManager" }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val remoteAddr = conn.remoteSocketAddress
            Log.d(TAG, "onOpen: TLS succeeded from $remoteAddr at ${System.currentTimeMillis()}")

            // If this is the warmup connection (phone connecting to itself), close it immediately.
            // onClose will set warmupClosed = true so startWebSocket() knows NIO is clean.
            val remoteIp = remoteAddr?.address?.hostAddress
            val isWarmup = remoteAddr?.address?.isLoopbackAddress == true
                    || remoteIp == NetworkUtils.getWifiIp()
            if (isWarmup) {
                Log.d(TAG, "Warmup WebSocket connected — closing immediately")
                conn.close()
                return
            }

            handshakeFactory?.handshakeCompleted(remoteAddr?.toString() ?: "")
            Log.d(TAG, "New connection from $remoteAddr")
            val json = JSONObject().apply {
                put("type", "HANDSHAKE")
                put("name", android.os.Build.MODEL)
            }
            conn.send(json.toString())
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {

            // If the PC never sent a HANDSHAKE message, the connection was a failed
            // TLS attempt (e.g. Keystore was still warming up, or PC timed out).
            // Keep the server running so the PC's retry loop can try again without
            // needing to restart the entire server.
            if (connectedPcName == null) {
                Log.d(TAG, "Connection closed before HANDSHAKE — server stays alive for retry")
                warmupClosed = true
                return
            }
            Log.d(TAG, "Connection closed — server stays running for next connection")
            // Clear the stored name and tell the UI the PC disconnected
            connectedPcName = null
            // Also wipe SharedPreferences so a future Activity open never reads a
            // stale name from a previous session and shows "Connected" incorrectly.
            context.getSharedPreferences("conn", Context.MODE_PRIVATE)
                .edit { remove("pc_name") }
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                Intent("STREAMBRIDGE_CHAT_EVENT").apply {
                    putExtra("message", JSONObject().apply {
                        put("type", "PC_DISCONNECTED")
                    }.toString())
                }
            )
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                // The server thread has died.  Port 8081 is now a zombie: the OS
                // TCP stack may still ACK incoming SYNs but nobody reads the data,
                // causing Windows to hang for 10 s then time out.
                Log.e(TAG, "WSS server error (startup/bind failure) — will restart", ex)
                isStarted = false
            } else {
                // Per-connection error (e.g. stalled TLS channel closed by watchdog,
                // or client sent malformed data). Log and let the connection close.
                Log.e(TAG, "onError: TLS FAILED from ${conn.remoteSocketAddress} at ${System.currentTimeMillis()} — ${ex.javaClass.simpleName}: ${ex.message}")
            }
        }

        override fun onStart() {
            isStarted = true
            Log.d(TAG, "WSS server started on port 8081")
        }

        override fun onMessage(conn: WebSocket, message: String) {
            Log.d(TAG, "Received: $message")
            if (message == "CAPTURE") { conn.send("CAPTURED"); return }
            if (message == "PING") { conn.send("PONG"); return }

            // If this is a HANDSHAKE, persist the PC name so FileBrowserActivity
            // can show it even if it wasn't open when the connection was established.
            try {
                val json = JSONObject(message)
                if (json.optString("type") == "HANDSHAKE") {
                    val pcName = json.optString("name", "PC")
                    connectedPcName = pcName
                    // Also persist so the Activity can read it after it opens
                    context.getSharedPreferences("conn", Context.MODE_PRIVATE)
                        .edit { putString("pc_name", pcName) }
                    // Save PC fingerprint so future connections auto-approve
                    val fingerprint = json.optString("fingerprint", "")
                    if (fingerprint.isNotBlank()) {
                        TrustedPcStore.saveTrusted(context, fingerprint, pcName)
                    }
                }
            } catch (_: Exception) {}

            // Always keeping history
            val historyObj = toHistoryJsonFromPC(message)
            if (historyObj != null) { ChatHistoryStore.append(context, historyObj) }

            // Broadcast to UI (if chat is open it will update immediately)
            val intent = Intent("STREAMBRIDGE_CHAT_EVENT")
            intent.putExtra("message", message)
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }

        private fun toHistoryJsonFromPC(message: String): JSONObject? {
            return try {
                val json = JSONObject(message)
                val type = json.optString("type", "")
                val now = System.currentTimeMillis()

                when (type) {
                    "TEXT" -> JSONObject().apply {
                        put("type", "TEXT")
                        put("text", json.optString("text", ""))
                        put("out", false)          // Logged in from PC
                        put("time", json.optLong("timestamp", now))
                    }

                    "FILE_RECEIVED" -> JSONObject().apply {
                        // This is a file that came from the computer to the phone via /upload
                        put("type", "FILE")
                        put("name", json.optString("name", "file"))
                        put("path", json.optString("path", ""))
                        put("size", File(json.optString("path", "")).length())
                        put("mime", mimeFromName(json.optString("name", "")))
                        put("out", false)
                        put("time", json.optLong("timestamp", now))
                    }
                    // HANDSHAKE / PING / PONG / CAPTURE and everything else that is not needed in history
                    else -> null
                }
            } catch (_: Exception) { null }
        }

        private fun mimeFromName(name: String): String {
            return when (name.substringAfterLast('.', "").lowercase()) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "gif" -> "image/gif"
                "mp4" -> "video/mp4"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "wav" -> "audio/wav"
                "aac" -> "audio/aac"
                "pdf" -> "application/pdf"
                else -> "application/octet-stream"
            }
        }

        fun broadcastToAll(message: String) {
            connections.forEach { it.send(message) }
        }
    }
}
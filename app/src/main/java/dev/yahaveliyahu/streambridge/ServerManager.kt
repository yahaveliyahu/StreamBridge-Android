package dev.yahaveliyahu.streambridge


import android.content.Context
import android.content.Intent
import android.util.Log
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
        var webSocketServer: CommandWebSocketServer? = null

        // The hostname of the currently-connected PC, or null when not connected
        @Volatile var connectedPcName: String? = null

        fun sendToPC(jsonString: String) {
            webSocketServer?.broadcastToAll(jsonString)
        }

        // Sending a valid URL path to a computer for all file types
        // A function to send a file to a computer in a way that prevents a crash
        fun sendFileToPC(file: File, mimeType: String) {
            val json = JSONObject().apply {
                put("type", "FILE_TRANSFER")
                put("fileName", file.name)
                put("fileSize", file.length())
                put("mimeType", mimeType)
                // Using a network path (URL) instead of a local path
                put("downloadPath", "/files/shared/${file.name}")
                put("timestamp", System.currentTimeMillis())
            }
            sendToPC(json.toString())
        }
    }

    // ── Server start / stop ─────────────────────────────────────────────────────

    fun startServer() {
        try {
            // Each call creates a fresh CertificateManager — they all share the same
            // Android Keystore entry so this is cheap and correct.
            val certManager = CertificateManager()
            val sslContext  = certManager.getSSLContext()   // also calls ensureCertificate()

            // ── HTTPS server (port 8080) ──────────────────────────────────────────
            httpServer = FileServer(8080, context)
            httpServer?.makeSecure(sslContext.serverSocketFactory, null)
            httpServer?.start()

            // ── WSS server (port 8081) ────────────────────────────────────────────
            // Passing the Context so we can broadcast messages to the UI
            // Using applicationContext prevents Activity Memory Leak

            webSocketServer = CommandWebSocketServer(8081, context.applicationContext)

            // The onServerError callback fires when the SERVER thread itself crashes
            // (e.g. BindException).  We schedule a full restart so port 8081 never
            // stays in a zombie state where it accepts TCP SYNs but hangs TLS.
//            webSocketServer = CommandWebSocketServer(
//                port = 8081,
//                context = context.applicationContext,
//                onServerError = { ex ->
//                    Log.e(TAG, "WSS server crashed — restarting in 2 s", ex)
//                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
//                        restartWssServer(sslContext)
//                    }, 2_000)
//                }
//            )
            webSocketServer?.isReuseAddr = true   // allow rebind while old socket is in TIME_WAIT

//            webSocketServer?.setWebSocketFactory(DefaultSSLWebSocketServerFactory(sslContext))

            // ── TLS factory with explicit handshake timeout ───────────────────────

            // java-websocket's DefaultSSLWebSocketServerFactory uses NIO + SSLEngine.
            // In NIO mode, if the TLS ClientHello never reaches the app layer
            // (e.g. intercepted by the Android network stack on Samsung/Android 15),
            // the SSLEngine sits in NEED_UNWRAP forever — no exception, no log.
            //
            // Fix: use a blocking SSL socket factory instead of NIO.  With blocking
            // sockets, SSLSocket.soTimeout = 15 000 ms ensures startHandshake()
            // throws SSLHandshakeException after 15 s, which reaches onError() and
            // gives us both a log entry and the auto-restart hook.
//            webSocketServer?.setWebSocketFactory(BlockingSSLWebSocketServerFactory(sslContext, handshakeTimeoutMs = 15_000))

            val factory = HandshakeTimeoutSSLFactory(sslContext, handshakeTimeoutMs = 5_000)
            webSocketServer!!.handshakeFactory = factory
            webSocketServer?.setWebSocketFactory(factory)

            webSocketServer?.start()

            Log.d(TAG, "Servers start initiated (TLS enabled)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting servers", e)
        }
    }

//    /**
//     * Tears down and recreates only the WSS server (port 8081).
//     * Called automatically when onError(conn=null) fires.
//     * The sslContext is passed in so we don't need to hit the Keystore again.
//     */
//    private fun restartWssServer(sslContext: javax.net.ssl.SSLContext) {
//        try {
//            Log.d(TAG, "Restarting WSS server…")
//            webSocketServer?.stop()
//            webSocketServer = null
//
//            webSocketServer = CommandWebSocketServer(
//                port = 8081,
//                context = context.applicationContext,
//                onServerError = { ex ->
//                    Log.e(TAG, "WSS server crashed again — restarting in 2 s", ex)
//                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
//                        restartWssServer(sslContext)
//                    }, 2_000)
//                }
//            )
//            webSocketServer?.isReuseAddr = true
//
////            webSocketServer?.setWebSocketFactory(DefaultSSLWebSocketServerFactory(sslContext))
//
//            webSocketServer?.setWebSocketFactory(BlockingSSLWebSocketServerFactory(sslContext, handshakeTimeoutMs = 15_000))
//
//            webSocketServer?.start()
//            Log.d(TAG, "WSS server restart initiated")
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to restart WSS server", e)
//        }
//    }

    fun stopServer() {
        try {
            httpServer?.stop()
            httpServer = null
            webSocketServer?.stop()
            webSocketServer = null
            Log.d(TAG, "Servers stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping servers", e)
        }
    }

//    fun isRunning(): Boolean = httpServer?.isAlive == true

    /**
     * Returns true only when BOTH servers are alive.
     * A false return while the service is supposed to be running means one of the
     * servers crashed — StreamBridgeService should call stopServer()+startServer().
     */
    fun isRunning(): Boolean = httpServer?.isAlive == true && webSocketServer?.isStarted == true

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
//                val params = session.parameters

                val tempFilePath = files["file"]
                // Name that comes from the URL: /upload?name=...
                val encodedNameFromUrl = session.parameters["name"]?.firstOrNull()
                val fileNameFromUrl = encodedNameFromUrl?.let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                }
                // Old fallback: if for some reason there is no name in the URL
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

//    class BlockingSSLWebSocketServerFactory(
//        private val sslContext: javax.net.ssl.SSLContext,
//        private val handshakeTimeoutMs: Int = 15_000
//    ) : DefaultSSLWebSocketServerFactory(sslContext) {

    class HandshakeTimeoutSSLFactory(
        private val sslContext: javax.net.ssl.SSLContext,
        private val handshakeTimeoutMs: Long = 15_000
    ) : DefaultSSLWebSocketServerFactory(sslContext) {

        // Remote address string → Pair(timestamp, SocketChannel)
        private val pendingHandshakes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, java.nio.channels.SocketChannel>>()

        init {
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(2_000)
                        val now = System.currentTimeMillis()
                        val iter = pendingHandshakes.entries.iterator()
                        while (iter.hasNext()) {
                            val entry = iter.next()
                            val (ts, ch) = entry.value
                            if (now - ts > handshakeTimeoutMs) {
                                Log.e("ServerManager",
                                    "TLS handshake watchdog: no data for ${handshakeTimeoutMs}ms " +
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
            // Register for watchdog BEFORE delegating to super so the timeout clock
            // starts immediately when the connection is accepted.
            val addr = channel.socket()?.remoteSocketAddress?.toString() ?: "unknown-${System.nanoTime()}"
            pendingHandshakes[addr] = Pair(System.currentTimeMillis(), channel)
            // Delegate to super: standard NIO + SSLEngine, selector thread is never blocked.
            return super.wrapChannel(channel, key)
        }

        /** Called from CommandWebSocketServer.onOpen() — cancels the watchdog for this conn. */
        fun handshakeCompleted(remoteAddr: String) {
            pendingHandshakes.remove(remoteAddr)
        }
    }

//            // Switch to blocking mode so we can impose a handshake timeout.
//            channel.configureBlocking(true)
//
//            val rawSocket = channel.socket()
//
//            // Wrap the raw socket as an SSLSocket (server mode).
//            val sslSocket = sslContext.socketFactory.createSocket(
//                rawSocket,
//                rawSocket.inetAddress?.hostAddress ?: "",
//                rawSocket.port,
//                true  // autoClose — close underlying socket when SSLSocket closes
//            ) as javax.net.ssl.SSLSocket
//
//            sslSocket.useClientMode = false
//
//            // ── Handshake timeout ─────────────────────────────────────────────────
//            // Without this, startHandshake() blocks forever if the ClientHello
//            // is dropped.  With it, SocketTimeoutException is thrown after
//            // [handshakeTimeoutMs] ms and routed to onError(conn, ex).
//            sslSocket.soTimeout = handshakeTimeoutMs
//
//            try {
//                sslSocket.startHandshake()
//            } catch (e: Exception) {
//                Log.e("ServerManager", "TLS handshake failed from ${rawSocket.inetAddress}: ${e.message}")
//                sslSocket.close()
//                throw e  // Let java-websocket call onError(conn, ex)
//            }
//
//            // After a successful handshake, remove the read timeout so normal
//            // WebSocket traffic can block as long as needed.
//            sslSocket.soTimeout = 0
//
//            // Wrap the SSLSocket's streams as a ByteChannel for java-websocket.
//            return java.nio.channels.Channels.newChannel(sslSocket.inputStream).let { readable ->
//                object : java.nio.channels.ByteChannel {
//                    private val writeCh = java.nio.channels.Channels.newChannel(sslSocket.outputStream)
//                    override fun read(dst: java.nio.ByteBuffer): Int = readable.read(dst)
//                    override fun write(src: java.nio.ByteBuffer): Int = writeCh.write(src)
//                    override fun isOpen(): Boolean = sslSocket.isConnected && !sslSocket.isClosed
//                    override fun close() { sslSocket.close() }
//                }
//            }
//        }


    // ================= WEBSOCKET SERVER =================
//    class CommandWebSocketServer(port: Int, private val context: Context) : WebSocketServer(InetSocketAddress(port)) {

//    class CommandWebSocketServer(port: Int, private val context: Context,
//        // Called when the SERVER itself errors (conn==null) — e.g. BindException.
//        // Distinct from a per-connection error.  ServerManager uses this to restart.
//        private val onServerError: ((Exception) -> Unit)? = null
//    ) : WebSocketServer(InetSocketAddress(port)) {

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
            // TLS + WebSocket handshake completed — cancel the timeout watchdog.
            handshakeFactory?.handshakeCompleted(conn.remoteSocketAddress?.toString() ?: "")
            Log.d(TAG, "New connection from ${conn.remoteSocketAddress}")
            val json = JSONObject().apply {
                put("type", "HANDSHAKE")
                put("name", android.os.Build.MODEL)
            }
            conn.send(json.toString())
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            // Clear the stored name and tell the UI the PC disconnected
            connectedPcName = null
            // Also wipe SharedPreferences so a future Activity open never reads a
            // stale name from a previous session and shows "Connected" incorrectly.
            context.getSharedPreferences("conn", Context.MODE_PRIVATE)
                .edit().remove("pc_name").apply()
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                Intent("STREAMBRIDGE_CHAT_EVENT").apply {
                    putExtra("message", JSONObject().apply {
                        put("type", "PC_DISCONNECTED")
                    }.toString())
                }
            )
        }

//        override fun onError(conn: WebSocket?, ex: Exception) { Log.e(TAG, "WebSocket error", ex) }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn == null) {
                // ── Server-level error (e.g. BindException on startup) ────────────
                // The server thread has died.  Port 8081 is now a zombie: the OS
                // TCP stack may still ACK incoming SYNs but nobody reads the data,
                // causing Windows to hang for 10 s then time out.
                Log.e(TAG, "WSS server error (startup/bind failure) — will restart", ex)
                isStarted = false
//                onServerError?.invoke(ex)
            } else {
                // Per-connection error (e.g. stalled TLS channel closed by watchdog,
                // or client sent malformed data). Log and let the connection close.
                Log.e(TAG, "WebSocket connection error from ${conn.remoteSocketAddress}: ${ex.message}")
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
                        .edit().putString("pc_name", pcName).apply()
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
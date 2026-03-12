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
            webSocketServer?.setWebSocketFactory(DefaultSSLWebSocketServerFactory(sslContext))
            webSocketServer?.start()

            Log.d(TAG, "Servers started successfully (TLS enabled)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting servers", e)
        }
    }

    fun stopServer() {
        try {
            httpServer?.stop()
            webSocketServer?.stop()
            Log.d(TAG, "Servers stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping servers", e)
        }
    }

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

    // ================= WEBSOCKET SERVER =================
    class CommandWebSocketServer(port: Int, private val context: Context) : WebSocketServer(InetSocketAddress(port)) {

        companion object { private const val TAG = "ServerManager" }

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
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

        override fun onError(conn: WebSocket?, ex: Exception) { Log.e(TAG, "WebSocket error", ex) }

        override fun onStart() { Log.d(TAG, "WSS server started on port 8081") }

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
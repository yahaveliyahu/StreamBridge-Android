package dev.yahaveliyahu.streambridge


import android.content.Context
import android.content.Intent
import android.os.Environment
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


class ServerManager(private val context: Context) {
    private var httpServer: FileServer? = null

    companion object {
        private const val TAG = "ServerManager"
        var currentCameraFrame: ByteArray? = null
        // משתנה סטטי כדי שנוכל לשלוח הודעות מכל מקום (למשל מתוך הצ'אט)
        var webSocketServer: CommandWebSocketServer? = null

        /** The hostname of the currently-connected PC, or null when not connected. */
        @Volatile var connectedPcName: String? = null

        // ✅ תיקון שגיאת Unresolved Reference
        fun sendToPC(jsonString: String) {
            webSocketServer?.broadcastToAll(jsonString)
        }

        // ✅ פונקציה לשליחת הודעה למחשב (JSON)
//        fun sendToPC(jsonMessage: String) {
//            webSocketServer?.broadcastToAll(jsonMessage)
//        }

        // ✅ פתרון לבעיה 3: שליחת נתיב URL תקין למחשב לכל סוגי הקבצים
        // ✅ פונקציה לשליחת קובץ למחשב בצורה שתמנע קריסה
        fun sendFileToPC(file: File, mimeType: String) {
            val json = JSONObject().apply {
                put("type", "FILE_TRANSFER")
                put("fileName", file.name)
                put("fileSize", file.length())
                put("mimeType", mimeType)
                // שימוש בנתיב רשת (URL) במקום נתיב מקומי
                put("downloadPath", "/files/shared/${file.name}")
                put("timestamp", System.currentTimeMillis())
            }
            sendToPC(json.toString())
        }
    }

//        fun sendTextToPC(text: String) {
//            val json = JSONObject().apply {
//                put("type", "TEXT")
//                put("text", text)
//                put("timestamp", System.currentTimeMillis())
//            }
//            webSocketServer?.broadcastToAll(json.toString())
//        }
//    }

    fun startServer() {
        try {
            httpServer = FileServer(8080, context)
            httpServer?.start()

            // מעבירים את ה-Context כדי שנוכל לשדר הודעות ל-UI
            // שימוש ב-applicationContext מונע Memory Leak של Activity
            webSocketServer = CommandWebSocketServer(8081, context.applicationContext)
            webSocketServer?.start()

            Log.d(TAG, "Servers started successfully")
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

    fun isRunning(): Boolean {
        return httpServer?.isAlive == true
    }

    // ================= HTTP SERVER (קבצים) =================
    class FileServer(port: Int, private val context: Context) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri

            return when {
                uri == "/camera" -> serveCameraStream()
                uri.startsWith("/files/") -> serveFile(uri)
//                uri == "/file-list" -> serveFileList()
                uri == "/upload" && session.method == Method.POST -> handleUpload(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        }

        private fun serveFile(uri: String): Response {
            // המרת ה-URL לנתיב פנימי אמיתי בטלפון
            // ה-URL: /files/shared/image.jpg  --->  הנתיב: /data/user/0/.../shared/image.jpg
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
                // NanoHTTPD דורש parsing מיוחד להעלאות
                val files = HashMap<String, String>()
                session.parseBody(files)
                val params = session.parameters

                // הקובץ הזמני שאנדרואיד שמר
//                val tempFilePath = files["file"]
//                val fileName = params["file"]?.firstOrNull() // השם המקורי שנשלח מהמחשב

                val tempFilePath = files["file"]

// ✅ 1) שם שמגיע מה-URL: /upload?name=...
                val encodedNameFromUrl = session.parameters["name"]?.firstOrNull()
                val fileNameFromUrl = encodedNameFromUrl?.let {
                    try { java.net.URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { it }
                }

// ✅ 2) fallback ישן: אם משום מה אין name ב-URL
                val fileNameFallback = session.parameters["file"]?.firstOrNull()

// ✅ 3) בחירה סופית
                val fileName = fileNameFromUrl ?: fileNameFallback ?: "file_${System.currentTimeMillis()}"


                if (tempFilePath != null && fileName != null) {
                    val src = File(tempFilePath)
                    val sharedDir = File(context.filesDir, "shared")
                    if (!sharedDir.exists()) sharedDir.mkdirs()
                    val dst = File(sharedDir, fileName)

                    src.copyTo(dst, overwrite = true)

                    // ✅ שמירה להיסטוריה גם אם הצ'אט לא פתוח
                    ChatHistoryStore.append(context, JSONObject().apply {
                        put("type", "FILE")
                        put("name", fileName)
                        put("path", dst.absolutePath)
                        put("size", dst.length())
                        put("mime", getMimeType(fileName))
                        put("out", false)
                        put("time", System.currentTimeMillis())
                    })

                    // שידור הודעה ל-UI שהתקבל קובץ
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


//        private fun serveFile(uri: String): Response {
//            return try {
//                // Security check: prevents accessing files outside allowed directories implicitly
//                val filePath = uri.removePrefix("/files")
//                // ✅ התיקון הקריטי: חיפוש בתיקיית האפליקציה (shared) ולא ב-Root
//                // אם הנתיב מתחיל ב-/, File מתייחס אליו כנתיב אבסולוטי. אנחנו רוצים יחסי.
//                val safePath = filePath.trimStart('/')
//                val file = File(context.filesDir, safePath)
//
//                if (!file.exists() || !file.isFile) {
//                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
//                }
//
//                val mimeType = getMimeType(file.name)
//                val fis = FileInputStream(file)
//                return newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
//            } catch (e: Exception) {
//                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
//            }
//        }

//        private fun serveFileList(): Response {
//            try {
//                val fileList = getStorageFiles()
//                val json = buildFileListJson(fileList)
//                return newFixedLengthResponse(Response.Status.OK, "application/json", json)
//            } catch (e: Exception) {
//                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
//            }
//        }

//        private fun getStorageFiles(): List<File> {
//            val files = mutableListOf<File>()
//
//            // FIX: Scan real public directories instead of app-private storage
//            val dirsToScan = listOf(
//                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
//                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
//                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
//            )
//
//            dirsToScan.forEach { dir ->
//                if (dir.exists()) {
//                    // Only go 2 levels deep to avoid timeouts on large storage
//                    dir.walk().maxDepth(2).forEach { file ->
//                        if (file.isFile && !file.isHidden) {
//                            files.add(file)
//                        }
//                    }
//                }
//            }
//            return files
//        }

//        private fun buildFileListJson(files: List<File>): String {
//            // Check if empty to avoid JSON errors
//            if (files.isEmpty()) return "[]"
//            val jsonArray = files.joinToString(",") { file ->
//                // Escape backslashes for JSON safety
//                val safePath = file.absolutePath.replace("\\", "\\\\")
//                """
//                {
//                    "name": "${file.name}",
//                    "path": "$safePath",
//                    "size": ${file.length()},
//                    "type": "${getMimeType(file.name)}"
//                }
//                """.trimIndent()
//            }
//            return "[$jsonArray]"
//        }

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

    // ================= WEBSOCKET SERVER (צ'אט) =================
    class CommandWebSocketServer(port: Int, private val context: Context) : WebSocketServer(InetSocketAddress(port)) {

        companion object {
            private const val TAG = "ServerManager"
        }
        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            val json = JSONObject().apply {
                put("type", "HANDSHAKE")
                put("name", android.os.Build.MODEL)
            }
            Log.d(TAG, "New connection from ${conn.remoteSocketAddress}")
            conn.send(json.toString())
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            // Clear the stored name and tell the UI the PC disconnected
            connectedPcName = null
            // Also wipe SharedPreferences so a future Activity open never reads a
            // stale name from a previous session and shows "Connected" incorrectly.
            context.getSharedPreferences("conn", Context.MODE_PRIVATE)
                .edit().remove("pc_name").apply()
            val disconnectIntent = Intent("STREAMBRIDGE_CHAT_EVENT").apply {
                putExtra("message", JSONObject().apply {
                    put("type", "PC_DISCONNECTED")
                }.toString())
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(disconnectIntent)
        }

        override fun onError(conn: WebSocket?, ex: Exception) {}

        override fun onStart() {}

        // קבלת הודעה מהמחשב ושידור ל-Activity
//        override fun onMessage(conn: WebSocket, message: String) {
//            Log.d(TAG, "Received: $message")
//            if (message == "CAPTURE") conn.send("CAPTURED")
//            if (message == "PING") conn.send("PONG")
//
//            // שולח Broadcast ל-FileBrowserActivity
//            val intent = Intent("STREAMBRIDGE_CHAT_EVENT")
//            intent.putExtra("message", message)
//            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
//        }

        override fun onMessage(conn: WebSocket, message: String) {
            Log.d(TAG, "Received: $message")

            if (message == "CAPTURE") { conn.send("CAPTURED"); return }
            if (message == "PING") { conn.send("PONG"); return }

            // ✅ If this is a HANDSHAKE, persist the PC name so FileBrowserActivity
            //    can show it even if it wasn't open when the connection was established.
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

            // ✅ 1) שמירה להיסטוריה תמיד
            val historyObj = toHistoryJsonFromPC(message)
            if (historyObj != null) {
                ChatHistoryStore.append(context, historyObj)
            }

            // ✅ 2) Broadcast ל-UI (אם הצ'אט פתוח הוא יתעדכן מיד)
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
                        put("out", false)          // נכנס מה-PC
                        put("time", json.optLong("timestamp", now))
                    }

                    "FILE_RECEIVED" -> JSONObject().apply {
                        // זה קובץ שהגיע מהמחשב לטלפון דרך /upload
                        put("type", "FILE")
                        put("name", json.optString("name", "file"))
                        put("path", json.optString("path", ""))
                        put("size", File(json.optString("path", "")).length())
                        put("mime", mimeFromName(json.optString("name", "")))
                        put("out", false)
                        put("time", json.optLong("timestamp", now))
                    }

                    // HANDSHAKE / PING / PONG / CAPTURE וכל מה שלא צריך בהיסטוריה
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
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

//
//
//import android.content.Context
//import android.util.Log
//import fi.iki.elonen.NanoHTTPD
//import org.java_websocket.WebSocket
//import org.java_websocket.handshake.ClientHandshake
//import org.java_websocket.server.WebSocketServer
//import java.io.File
//import java.io.FileInputStream
//import java.net.InetSocketAddress
//
//class ServerManager(private val context: Context) {
//    private var httpServer: FileServer? = null
//    private var webSocketServer: CommandWebSocketServer? = null
//
//    companion object {
//        private const val TAG = "ServerManager"
//        var currentCameraFrame: ByteArray? = null
//    }
//
//    fun startServer() {
//        try {
//            // Start HTTP server
//            httpServer = FileServer(8080, context)
//            httpServer?.start()
//
//            // Start WebSocket server
//            webSocketServer = CommandWebSocketServer(8081)
//            webSocketServer?.start()
//
//            Log.d(TAG, "Servers started successfully")
//        } catch (e: Exception) {
//            Log.e(TAG, "Error starting servers", e)
//        }
//    }
//
//    fun stopServer() {
//        try {
//            httpServer?.stop()
//            webSocketServer?.stop()
//            Log.d(TAG, "Servers stopped")
//        } catch (e: Exception) {
//            Log.e(TAG, "Error stopping servers", e)
//        }
//    }
//
//    fun isRunning(): Boolean {
//        return httpServer?.isAlive == true
//    }
//
//    // File Server using NanoHTTPD
//    class FileServer(port: Int, private val context: Context) : NanoHTTPD(port) {
//        override fun serve(session: IHTTPSession): Response {
//            val uri = session.uri
//
//            return when {
//                uri == "/camera" -> serveCameraStream()
//                uri.startsWith("/files/") -> serveFile(uri)
//                uri == "/file-list" -> serveFileList()
//                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
//            }
//        }
//
//        private fun serveCameraStream(): Response {
//            val frame = currentCameraFrame
//            return if (frame != null) {
//                newFixedLengthResponse(Response.Status.OK, "image/jpeg", frame.inputStream(), frame.size.toLong())
//            } else {
//                newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "No camera frame available")
//            }
//        }
//
//        private fun serveFile(uri: String): Response {
//            try {
//                val filePath = uri.removePrefix("/files")
//                val file = File(filePath)
//
//                if (!file.exists() || !file.isFile) {
//                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found")
//                }
//
//                val mimeType = getMimeType(file.name)
//                val fis = FileInputStream(file)
//                return newFixedLengthResponse(Response.Status.OK, mimeType, fis, file.length())
//            } catch (e: Exception) {
//                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
//            }
//        }
//
//        private fun serveFileList(): Response {
//            try {
//                val fileList = getStorageFiles()
//                val json = buildFileListJson(fileList)
//                return newFixedLengthResponse(Response.Status.OK, "application/json", json)
//            } catch (e: Exception) {
//                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
//            }
//        }
//
//        private fun getStorageFiles(): List<File> {
//            val files = mutableListOf<File>()
//
//            // Get files from common directories
//            val dirs = listOf(
//                context.getExternalFilesDir(null),
//                context.filesDir
//            )
//
//            dirs.forEach { dir ->
//                dir?.walkTopDown()?.forEach { file ->
//                    if (file.isFile) {
//                        files.add(file)
//                    }
//                }
//            }
//
//            return files
//        }
//
//        private fun buildFileListJson(files: List<File>): String {
//            val jsonArray = files.joinToString(",") { file ->
//                """
//                {
//                    "name": "${file.name}",
//                    "path": "${file.absolutePath}",
//                    "size": ${file.length()},
//                    "type": "${getMimeType(file.name)}"
//                }
//                """.trimIndent()
//            }
//            return "[$jsonArray]"
//        }
//
//        private fun getMimeType(fileName: String): String {
//            return when (fileName.substringAfterLast('.', "").lowercase()) {
//                "jpg", "jpeg" -> "image/jpeg"
//                "png" -> "image/png"
//                "gif" -> "image/gif"
//                "mp4" -> "video/mp4"
//                "mp3" -> "audio/mpeg"
//                "pdf" -> "application/pdf"
//                "txt" -> "text/plain"
//                else -> "application/octet-stream"
//            }
//        }
//    }
//
//    // WebSocket Server for commands
//    class CommandWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
//
//        companion object {
//            private const val TAG = "ServerManager"
//        }
//        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
//            Log.d(TAG, "New connection from ${conn.remoteSocketAddress}")
//            conn.send("Connected to Phone")
//        }
//
//        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
//            Log.d(TAG, "Connection closed: $reason")
//        }
//
//        override fun onMessage(conn: WebSocket, message: String) {
//            Log.d(TAG, "Received message: $message")
//
//            when (message) {
//                "CAPTURE" -> {
//                    conn.send("CAPTURED")
//                    // The capture will be handled in CameraActivity
//                }
//                "PING" -> {
//                    conn.send("PONG")
//                }
//            }
//        }
//
//        override fun onError(conn: WebSocket?, ex: Exception) {
//            Log.e(TAG, "WebSocket error", ex)
//        }
//
//        override fun onStart() {
//            Log.d(TAG, "WebSocket server started")
//        }
//
//        fun broadcastToAll(message: String) {
//            connections.forEach { it.send(message) }
//        }
//    }
//}

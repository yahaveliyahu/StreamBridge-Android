package dev.yahaveliyahu.streambridge

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject

/**
 * Transparent Activity that receives ACTION_SEND / ACTION_SEND_MULTIPLE intents
 * from any app (Samsung Notes, Contacts, My Files, Gallery, Music, …).
 *
 * It immediately copies the shared content to the app's private folder and
 * asks StreamBridgeService to send it to the PC over the existing connection.
 *
 * The Activity has no layout – it finishes as soon as the work is queued so the
 * user is returned to the originating app instantly.
 */

class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            handleShareIntent(intent)
        } catch (e: Exception) {
            Log.e("StreamBridge", "ShareReceiver crash", e)
            Toast.makeText(this, "Failed to import shared file: ${e.message}", Toast.LENGTH_LONG).show()
        } finally {
            finish() // חשוב: לא להשאיר Activity פתוחה
        }
    }

    // ─────────── Intent handling ───────────

    private fun handleShareIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return

        // ── Plain-text share (Samsung Notes, clipboard, etc.) ──
        if (intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!text.isNullOrBlank()) {
                sendTextToPC(text)
                Toast.makeText(this, "StreamBridge: text sent", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // ── File / URI share ──
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> {
                val u = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

                if (u != null) listOf(u) else emptyList()
            }
            else -> {
                val list = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else
                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)

                list ?: emptyList()
            }
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "StreamBridge: nothing to share", Toast.LENGTH_SHORT).show()
            return
        }
//        if (uris.isEmpty()) {
//            // לפעמים Notes שולח טקסט
//            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
//            if (!text.isNullOrBlank()) {
//                // פה אתה יכול לשלוח טקסט למחשב
//                // ServerManager / WebSocket וכו'
//                return
//            }
//            return
//        }

        // ✅ שמירת קבצים לתיקיית shared שלך כדי שתמיד יהיה גישה
        val sharedDir = File(filesDir, "shared").apply { if (!exists()) mkdirs() }

        for (uri in uris) {
            try {
                val rawName = getFileNameSafe(uri) ?: "shared_${System.currentTimeMillis()}"
                val mime    = contentResolver.getType(uri) ?: "application/octet-stream"
                val ext     = MimeUtils.getMimeTypeFromName(rawName)
                    ?.let { "" } ?: extensionFromMime(mime)
                val name = if (rawName.contains('.')) rawName else "$rawName.$ext"
                val dest = uniqueFileInDir(sharedDir, name)

//                val name = getFileNameSafe(uri) ?: "shared_${System.currentTimeMillis()}"

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Can't open stream for uri: $uri")

            // מבקשים מה-Service לשדר את הקובץ למחשב (אם יש חיבור)
                // Ask the service to notify the PC (it handles WebSocket + history)
                val svc = Intent(this, StreamBridgeService::class.java).apply {
                action = StreamBridgeService.ACTION_SEND_LOCAL_FILE
                putExtra(StreamBridgeService.EXTRA_LOCAL_PATH, dest.absolutePath)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
                Toast.makeText(this, "StreamBridge: sent ${dest.name}", Toast.LENGTH_SHORT).show()
                Log.d("ShareReceiver", "Queued: ${dest.absolutePath}")

            } catch (e: Exception) {
                Log.e("ShareReceiver", "Failed for $uri", e)
                Toast.makeText(this, "StreamBridge error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ─────────── Text → PC ───────────

    private fun sendTextToPC(text: String) {
        val now  = System.currentTimeMillis()
        val json = JSONObject().apply {
            put("type",      "TEXT")
            put("text",      text)
            put("timestamp", now)
        }
        ServerManager.sendToPC(json.toString())

        ChatHistoryStore.append(this, JSONObject().apply {
            put("type", "TEXT"); put("text", text); put("out", true); put("time", now)
        })

        val bi = Intent("STREAMBRIDGE_CHAT_EVENT").apply { putExtra("message", json.toString()) }
        LocalBroadcastManager.getInstance(this).sendBroadcast(bi)
    }

    // ─────────── Utilities ───────────

    private fun getFileNameSafe(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun extensionFromMime(mime: String): String = when {
        mime.startsWith("image/jpeg")   -> "jpg"
        mime.startsWith("image/png")    -> "png"
        mime.startsWith("image/gif")    -> "gif"
        mime.startsWith("video/mp4")    -> "mp4"
        mime.startsWith("audio/mpeg")   -> "mp3"
        mime.startsWith("audio/mp4")    -> "m4a"
        mime.startsWith("audio/")       -> "aac"
        mime == "application/pdf"       -> "pdf"
        mime == "text/plain"            -> "txt"
        mime == "text/x-vcard"          -> "vcf"
        mime == "text/vcard"            -> "vcf"
        else                            -> "bin"
    }

    private fun uniqueFileInDir(dir: File, fileName: String): File {
        var f = File(dir, fileName)
        if (!f.exists()) return f
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = if (fileName.contains(".")) ".${fileName.substringAfterLast('.')}" else ""
        var i = 1
        while (f.exists()) {
            f = File(dir, "${base}($i)$ext")
            i++
        }
        return f
    }
}

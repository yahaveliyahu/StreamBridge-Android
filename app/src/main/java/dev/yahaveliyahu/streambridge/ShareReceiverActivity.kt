//package dev.yahaveliyahu.streambridge
//
//import android.content.Intent
//import android.net.Uri
//import android.os.Build
//import android.os.Bundle
//import android.provider.OpenableColumns
//import android.util.Log
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import java.io.File
//import java.io.FileOutputStream
//
//
//class ShareReceiverActivity : AppCompatActivity() {
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        try {
//            handleShareIntent(intent)
//        } catch (e: Exception) {
//            Log.e("StreamBridge", "ShareReceiver crash", e)
//            Toast.makeText(this, "Failed to import shared file: ${e.message}", Toast.LENGTH_LONG).show()
//        } finally {
//            finish() // חשוב: לא להשאיר Activity פתוחה
//        }
//    }
//
//    private fun handleShareIntent(intent: Intent) {
//        if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return
//
//        val uris: List<Uri> = when (intent.action) {
//            Intent.ACTION_SEND -> {
//                val u = if (Build.VERSION.SDK_INT >= 33)
//                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
//                else
//                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
//
//                if (u != null) listOf(u) else emptyList()
//            }
//            else -> {
//                val list = if (Build.VERSION.SDK_INT >= 33)
//                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
//                else
//                    @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
//
//                list ?: emptyList()
//            }
//        }
//
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
//
//        // ✅ שמירת קבצים לתיקיית shared שלך כדי שתמיד יהיה גישה
//        val sharedDir = File(filesDir, "shared").apply { if (!exists()) mkdirs() }
//
//        for (uri in uris) {
//            val name = getFileNameSafe(uri) ?: "shared_${System.currentTimeMillis()}"
//            val dest = uniqueFileInDir(sharedDir, name)
//
//            contentResolver.openInputStream(uri)?.use { input ->
//                FileOutputStream(dest).use { output -> input.copyTo(output) }
//            } ?: throw IllegalStateException("Can't open stream for uri: $uri")
//
//            // מבקשים מה-Service לשדר את הקובץ למחשב (אם יש חיבור)
//            val i = Intent(this, StreamBridgeService::class.java).apply {
//                action = StreamBridgeService.ACTION_SEND_LOCAL_FILE
//                putExtra(StreamBridgeService.EXTRA_LOCAL_PATH, dest.absolutePath)
//            }
//            startService(i)
//            Toast.makeText(this, "Imported: ${dest.name}", Toast.LENGTH_SHORT).show()
//        }
//
//    }
//
//    private fun getFileNameSafe(uri: Uri): String? {
//        contentResolver.query(uri, null, null, null, null)?.use { c ->
//            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
//            if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
//        }
//        return uri.lastPathSegment
//    }
//
//    private fun uniqueFileInDir(dir: File, fileName: String): File {
//        var f = File(dir, fileName)
//        if (!f.exists()) return f
//        val base = fileName.substringBeforeLast('.', fileName)
//        val ext = if (fileName.contains(".")) ".${fileName.substringAfterLast('.')}" else ""
//        var i = 1
//        while (f.exists()) {
//            f = File(dir, "${base}($i)$ext")
//            i++
//        }
//        return f
//    }
//}

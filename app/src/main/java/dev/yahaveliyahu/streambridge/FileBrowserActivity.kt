package dev.yahaveliyahu.streambridge

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.io.FileOutputStream
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.provider.ContactsContract
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import org.json.JSONObject
import androidx.core.content.edit

import androidx.core.content.FileProvider

import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*


class FileBrowserActivity : AppCompatActivity() {

    private lateinit var backButton: Button
    private lateinit var plusButton: ImageButton
    private lateinit var sendButton: ImageButton
    private lateinit var messageEditText: EditText
    private lateinit var statusText: TextView
    private lateinit var chatRecycler: RecyclerView

    private val messages = mutableListOf<ChatItem>()
    private lateinit var adapter: ChatBubblesAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private var latestTmpUri: Uri? = null
    private var latestTmpFile: File? = null // כדי שנוכל לוודא שהצילום באמת נשמר ולא יצא קובץ ריק

    companion object { private const val HISTORY_TTL_DAYS = 30 }

    private fun historyCutoffMillis(): Long =
        System.currentTimeMillis() - HISTORY_TTL_DAYS * 24L * 60L * 60L * 1000L


    private val historyPrefs by lazy { getSharedPreferences("chat_history", MODE_PRIVATE) }

    // ✅ המקלט שמאזין להודעות מהמחשב
    private val chatMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonStr = intent?.getStringExtra("message") ?: return
            try {
                val json = JSONObject(jsonStr)
                // אם זו הודעה שהתקבלה מהמחשב
                if (json.has("type")) {
                    when (json.getString("type")) {
                        "HANDSHAKE" -> {
                            // ✅ פתרון לבעיה 6: קבלת שם המחשב
                            val pcName = json.optString("name", "PC")
                            statusText.text = getString(R.string.connected_to, pcName)
                        }
                        "FILE_RECEIVED" -> {

//                    val type = json.getString("type")
//                    if (type == "FILE_RECEIVED") {
                        // קובץ שהועלה מהמחשב לטלפון
                            val fileName = json.getString("name")
                            val path = json.getString("path")
                            val f = File(path)
                            val mime = mimeFromName(fileName)
                            addMessage(ChatItem.FileItem(fileName, path, f.length(), mime, false, System.currentTimeMillis()))

                        }
                        "TEXT" -> {
                            val text = json.optString("text")
                            addMessage(ChatItem.Text(text, false, System.currentTimeMillis()))
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

//                        addMessage(ChatItem.FileItem(
//                            name = fileName,
//                            localPath = path,
//                            sizeBytes = f.length(),
//                            mimeType = "application/octet-stream",
//                            isOutgoing = false // זה נכנס!
//                        ))
//                    } else {
//                        // הודעת טקסט רגילה מהמחשב
//                        val text = json.optString("text")
//                        addMessage(ChatItem.Text(text = text, isOutgoing = false)) // false = נכנס (אפור)
//                    }
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//    }

    // ✅ הרשאת אנשי קשר (מהקוד החדש)
    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "צריך הרשאת אנשי קשר", Toast.LENGTH_SHORT).show()
            else pickContactLauncher.launch(null)
        }

    // ================== Pickers ==================

    // ✅ Image picker
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent())
    { uri: Uri? -> uri?.let { handlePickedUri(it) }}

    // ✅ Video picker
    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent())
    { uri: Uri? -> uri?.let { handlePickedUri(it) }}

    // ✅ Files picker (documents)
    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument())
    { uri: Uri? -> uri?.let { handlePickedUri(it) }}

    // ✅ Audio
    private val pickAudioLauncher = registerForActivityResult(ActivityResultContracts.GetContent())
    { uri: Uri? -> uri?.let { handlePickedUri(it) }}

    // Contact
    private val pickContactLauncher = registerForActivityResult(ActivityResultContracts.PickContact())
    { uri: Uri? -> uri?.let { exportContactAsVcf(it) }}

    // ✅ המצלמה החדשה - משתמשת במצלמה המובנית של המכשיר
    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (!success || latestTmpUri == null) return@registerForActivityResult

            // ✅ הגנה: לפעמים success=true אבל הקובץ ריק/לא נשמר
            val f = latestTmpFile
            if (f == null || !f.exists() || f.length() <= 0L) {
                Toast.makeText(this, "הצילום לא נשמר / הקובץ ריק", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            // ✅ מטפל בדיוק כמו גלריה
            handlePickedUri(latestTmpUri!!)
        }


    // ✅ פיצ'ר חדש: מצלמה (Custom Activity)
//    private val cameraLauncher =
//        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
//            val filePath = result.data?.getStringExtra("captured_path")
//            if (!filePath.isNullOrBlank()) {
//                val f = File(filePath)
//                // הופך את הקובץ מהמצלמה ל-ChatItem
//                val item = ChatItem.FileItem(
//                    name = f.name,
//                    localPath = f.absolutePath,
//                    sizeBytes = f.length(),
//                    mimeType = "image/jpeg",
//                    isOutgoing = true
//                )
//                addMessage(item)
//            }
//        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_browser)

        backButton = findViewById(R.id.backButton)
        plusButton = findViewById(R.id.plusButton)
        sendButton = findViewById(R.id.sendButton)
        messageEditText = findViewById(R.id.messageEditText)
        statusText = findViewById(R.id.statusText)
        chatRecycler = findViewById(R.id.chatRecycler)

        // ✅ Back תמיד חוזר ל-MainActivity (activity_main.xml)
        backButton.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            finish()
        }

        // ✅ סטטוס מחובר עם שם מחשב (מה-Intent או SharedPreferences)
        val pcName = intent.getStringExtra("pc_name")
            ?: getSharedPreferences("conn", MODE_PRIVATE).getString("pc_name", "DESKTOP-XXXXX")
            ?: "DESKTOP-XXXXX"

        statusText.text = getString(R.string.status_connected, pcName)

        // ✅ RecyclerView (צ'אט) + גלילה לתחתית
        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = ChatBubblesAdapter(messages, this)
        chatRecycler.layoutManager = layoutManager
        chatRecycler.adapter = adapter

        sendButton.setOnClickListener { sendMessage() }
        plusButton.setOnClickListener { showAttachSheet()}

        loadHistory()

        // ✅ פיצ'ר חדש: טיפול בשיתוף חיצוני (אם נכנסנו לאפליקציה דרך Share)
        handleIncomingShare(intent)
    }

    // ✅ הרשמה לקבלת הודעות
    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            chatMessageReceiver, IntentFilter("STREAMBRIDGE_CHAT_EVENT")
        )
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(chatMessageReceiver)
        saveHistory()
    }

    // ✅ פיצ'ר חדש: תמיכה ב-SingleTop כדי לקבל שיתופים גם כשהאפליקציה פתוחה
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShare(intent)
    }

    private fun sendMessage() {
        val text = messageEditText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "כתוב הודעה קודם 🙂", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = System.currentTimeMillis()
        addMessage(ChatItem.Text(text, true, timestamp))

        messageEditText.setText("")

        val json = JSONObject().apply {
            put("text", text)
            put("type", "TEXT")
            put("timestamp", timestamp)
        }
        ServerManager.sendToPC(json.toString())
    }

//        messageEditText.setText("")
//        // 1. הוספה למסך שלי (כחול)
//        messages.add(ChatItem.Text(text = text, isOutgoing = true))
//        // 2. ✅ שליחה למחשב (JSON)
//        val json = JSONObject().apply {
//            put("text", text)
//            put("type", "TEXT")
//            put("timestamp", System.currentTimeMillis())
//        }
//        ServerManager.sendToPC(json.toString())
//    }

    private fun addMessage(item: ChatItem) {
        val lastMsg = messages.lastOrNull()
        val itemDate = Date(item.timestamp)
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

        // ✅ פתרון לבעיה 8: הוספת ימים
        if (lastMsg == null || (lastMsg !is ChatItem.DateHeader && dateFormat.format(Date(lastMsg.timestamp)) != dateFormat.format(itemDate))) {
            val headerText = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(itemDate)
            messages.add(ChatItem.DateHeader(headerText, item.timestamp))
            adapter.notifyItemInserted(messages.size - 1)
        }
        // 1. הוספה לזיכרון
        messages.add(item)
        // 2. עדכון התצוגה (השורה ששאלת עליה)
        adapter.notifyItemInserted(messages.size - 1)
        // 3. גלילה למטה (השורה ששאלת עליה)
        chatRecycler.scrollToPosition(messages.size - 1)
    }

    private fun handlePickedUri(uri: Uri) {
        try {
            // 1) שם קובץ בטוח (כולל מקרים שאין DISPLAY_NAME)
            var fileName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
//            val fileSize = destFile.length()
            // 2) MIME בטוח (אודיו/תמונה/כל דבר)
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"

            fileName = ensureExtension(fileName, mime)

            // אם לשם הקובץ אין סיומת, נוסיף אותה לפי ה-MimeType
            if (!fileName.contains(".")) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                fileName = "$fileName.$ext"
            }

            // 3) תיקיית shared מקומית (גישה תמידית גם לאודיו חיצוני)
            // ✅ שומר עותק מקומי כדי שתמיד יהיה גישה + thumbnail לתמונות
            val sharedDir = File(filesDir, "shared")
            if (!sharedDir.exists()) sharedDir.mkdirs()

            // אם קיים כבר באותו שם - אפשר למנוע דריסה עם שם ייחודי
            val destFile = uniqueFileInDir(sharedDir, fileName)
            val finalName = destFile.name

            // 4) העתקה בטוחה מה-URI לקובץ מקומי
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Toast.makeText(this, "Cannot open file stream", Toast.LENGTH_SHORT).show()
                return
            }

            // 5) גודל אמין אחרי ההעתקה (ולא לסמוך על size מה-URI)
            val fileSize = destFile.length()

            // 6) הוספה לצ’אט בטלפון (בועה יוצאת)
            addMessage(
                ChatItem.FileItem(
                    name = finalName,
                    localPath = destFile.absolutePath,
                    sizeBytes = fileSize,
                    mimeType = mime,
                    isOutgoing = true,
                    timestamp = System.currentTimeMillis()
                )
            )

            // 7) שליחת פקודה ל-PC להוריד מהטלפון דרך HTTP
            val json = JSONObject().apply {
                put("type", "FILE_TRANSFER")
                put("fileName", finalName)
//                put("fileName", fileName)
                put("fileSize", fileSize)
                put("mimeType", mime)
                put("downloadPath", "/files/shared/$finalName")
//                put("downloadPath", "/files/shared/$fileName")
                put("timestamp", System.currentTimeMillis())
            }
            ServerManager.sendToPC(json.toString())

            Toast.makeText(this, "נשלח: $fileName", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

//            Toast.makeText(this, "נבחר: $fileName", Toast.LENGTH_SHORT).show()

//            // ✅ 3. שליחת פקודה למחשב להוריד את הקובץ
//            val json = JSONObject().apply {
//                put("type", "FILE_TRANSFER") // סוג הודעה מיוחד
//                put("fileName", fileName)
//                put("fileSize", fileSize)
//                put("mimeType", mime)
//                // הנתיב להורדה בשרת ה-HTTP של הטלפון
//                put("downloadPath", "/files/shared/$fileName")
//                put("timestamp", System.currentTimeMillis())
//            }
//            ServerManager.sendToPC(json.toString())

            // שליחה למחשב
//            ServerManager.sendFileToPC(destFile, mime)
//
//            Toast.makeText(this, "Sent file request to PC", Toast.LENGTH_SHORT).show()
//
//        } catch (e: Exception) {
//            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
//        }
//    }

    private fun uniqueFileInDir(dir: File, originalName: String): File {
        val dotIndex = originalName.lastIndexOf('.')
        val baseName = if (dotIndex > 0) originalName.substring(0, dotIndex) else originalName
        val extension = if (dotIndex > 0) originalName.substring(dotIndex) else ""

        var candidate = File(dir, originalName)
        var counter = 1

        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($counter)$extension")
            counter++
        }

        return candidate
    }


    private fun showAttachSheet() {
        val dialog = BottomSheetDialog(this)
        val container = FrameLayout(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_attach_flow, container, false)

//        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_attach_flow, null)

        view.findViewById<View>(R.id.optGallery)?.setOnClickListener {
            dialog.dismiss(); pickImageLauncher.launch("image/*")
        }
        view.findViewById<View>(R.id.optVideo)?.setOnClickListener {
            dialog.dismiss(); pickVideoLauncher.launch("video/*")
        }
        view.findViewById<View>(R.id.optFiles)?.setOnClickListener {
            dialog.dismiss(); pickFileLauncher.launch(arrayOf("*/*"))
        }
        view.findViewById<View>(R.id.optMusic)?.setOnClickListener {
            dialog.dismiss(); pickAudioLauncher.launch("audio/*")
        }

        view.findViewById<View>(R.id.optNotes)?.setOnClickListener {
            dialog.dismiss()
            openNotesAppHint()
        }

        view.findViewById<View>(R.id.optContacts)?.setOnClickListener {
            dialog.dismiss()
            requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }


        val optCamera = view.findViewById<View>(R.id.optCamera)
        optCamera?.setOnClickListener {
            dialog.dismiss()
            launchCamera()
        }


//        val optLive = view.findViewById<View>(R.id.optLive)
//        optLive?.setOnClickListener {
//            dialog.dismiss()
//            val intent = Intent(this, CameraActivity::class.java).putExtra("mode", "LIVE")
//            cameraLauncher.launch(intent)
//        }

        dialog.setContentView(view)
        dialog.show()
    }

    //  טיפול בשיתוף נכנס
    private fun handleIncomingShare(intent: Intent) {
        val action = intent.action ?: return
        val type = intent.type ?: return

        if (action == Intent.ACTION_SEND) {
            if (type == "text/plain") {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    addMessage(ChatItem.Text(text = text, isOutgoing = true, timestamp = System.currentTimeMillis()
                    ))
                }
            } else {
                val uri = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (uri != null) handlePickedUri(uri)
            }
        }
    }

    // ✅ לוגיקה חדשה ומתוקנת: ייצוא איש קשר
    private fun exportContactAsVcf(contactUri: Uri) {
        try {
            val cursor = contentResolver.query(contactUri, null, null, null, null) ?: return
            cursor.use {
                if (!it.moveToFirst()) return

                // 1. תיקון: משיגים את ה-LOOKUP_KEY (זה מה שאנדרואיד צריך בשביל VCF)
                val lookupKeyIndex = it.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

                if (lookupKeyIndex == -1) return // הגנה

                val lookupKey = it.getString(lookupKeyIndex)
                val displayName = if (nameIndex != -1) it.getString(nameIndex) else "contact"

                // 2. יצירת ה-URI הנכון להורדת ה-VCard
                val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)

                // 3. הכנת הקובץ המקומי
                val sharedDir = File(filesDir, "shared")
                if (!sharedDir.exists()) sharedDir.mkdirs()

                // משתמש בשם של איש הקשר כשם הקובץ (למשל: "Yosi Cohen.vcf")
                val safeName = displayName.replace("[^a-zA-Z0-9 א-ת]".toRegex(), "_") // מנקה תווים בעייתיים
                val fileName = "$safeName.vcf"
                val destFile = File(sharedDir, fileName)

                // 4. העתקת התוכן לקובץ
                contentResolver.openInputStream(vcardUri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }

                // 5. הוספה לצ'אט
                addMessage(
                    ChatItem.FileItem(
                        name = fileName,
                        localPath = destFile.absolutePath,
                        sizeBytes = destFile.length(),
                        mimeType = "text/x-vcard",
                        isOutgoing = true,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Contact export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    // ✅ לוגיקה חדשה: ייצוא איש קשר
//    private fun exportContactAsVcf(contactUri: Uri) {
//        try {
//            val cursor = contentResolver.query(contactUri, null, null, null, null) ?: return
//            cursor.use {
//                if (!it.moveToFirst()) return
//                val idIndex = it.getColumnIndex(ContactsContract.Contacts._ID)
//                if (idIndex == -1) return // הגנה
//                val contactId = it.getString(idIndex)
//                val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, contactId)
//
//                val sharedDir = File(filesDir, "shared")
//                if (!sharedDir.exists()) sharedDir.mkdirs()
//
//                val fileName = "contact_${contactId}.vcf"
//                val destFile = File(sharedDir, fileName)
//
//                contentResolver.openInputStream(vcardUri)?.use { input ->
//                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
//                }
//
//                addMessage(
//                    ChatItem.FileItem(
//                        name = fileName,
//                        localPath = destFile.absolutePath,
//                        sizeBytes = destFile.length(),
//                        mimeType = "text/x-vcard",
//                        isOutgoing = true
//                    )
//                )
//            }
//        } catch (e: Exception) {
//            Toast.makeText(this, "Contact export failed: ${e.message}", Toast.LENGTH_SHORT).show()
//        }
//    }

    private fun openNotesAppHint() {
        try {
            val samsungNotesPackage = "com.samsung.android.app.notes"

            // 1. נסה לפתוח ספציפית את Samsung Notes
            val launchIntent = packageManager.getLaunchIntentForPackage(samsungNotesPackage)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                // 2. אם לא מותקן (או שהמניפסט לא מעודכן), נסה לפתוח כל אפליקציית פתקים
                // (עובד באנדרואיד 14 ומעלה, או באנדרואיד ישן עם Intent גנרי)
                try {
                    val intent = Intent(Intent.ACTION_MAIN)
                    intent.addCategory("android.intent.category.APP_NOTES")
                    startActivity(intent)
                } catch (_: Exception) {
                    // 3. אם הכל נכשל
                    Toast.makeText(this, "לא נמצאה אפליקציית פתקים. פתח ידנית ושתף.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "שגיאה בפתיחת האפליקציה: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }





    // ✅ לוגיקה חדשה: Notes Hint
//    private fun openNotesAppHint() {
//        try {
//            if (Build.VERSION.SDK_INT >= 34) {
//                val intent = Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_NOTES")
//                startActivity(intent)
//            } else {
//                val intent = packageManager.getLaunchIntentForPackage("com.samsung.android.app.notes")
//                if (intent != null) startActivity(intent)
//                else Toast.makeText(this, "פתח Notes ואז Share ל-StreamBridge", Toast.LENGTH_LONG).show()
//            }
//        } catch (_: Exception) {
//            Toast.makeText(this, "לא הצלחתי לפתוח Notes. פתח ידנית ואז Share", Toast.LENGTH_LONG).show()
//        }
//    }

    private fun getFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return null
    }

    private fun ensureExtension(name: String, mime: String): String {
        // אם כבר יש נקודה בסוף – נשאיר
        if (name.contains('.') && !name.endsWith(".")) return name

        val ext = when (mime.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "audio/mpeg" -> "mp3"
            "audio/mp4" -> "m4a"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            "text/x-vcard", "text/vcard" -> "vcf"
            else -> ""
        }
        return if (ext.isNotBlank()) "$name.$ext" else name
    }

    private fun mimeFromName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "aac" -> "audio/aac"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "doc" -> "application/msword"
            else -> "application/octet-stream"
        }
    }



//    private fun saveHistory() {
//        val jsonArray = JSONArray()
//        messages.forEach { msg ->
//            if (msg !is ChatItem.DateHeader) {
//                val obj = JSONObject()
//                when (msg) {
//                    is ChatItem.Text -> {
//                        obj.put("type", "TEXT"); obj.put("text", msg.text); obj.put(
//                            "out",
//                            msg.isOutgoing
//                        ); obj.put("time", msg.timestamp)
//                    }
//
//                    is ChatItem.FileItem -> {
//                        obj.put("type", "FILE"); obj.put("name", msg.name); obj.put(
//                            "path",
//                            msg.localPath
//                        ); obj.put("size", msg.sizeBytes); obj.put(
//                            "mime",
//                            msg.mimeType
//                        ); obj.put("out", msg.isOutgoing); obj.put("time", msg.timestamp)
//                    }
//
//                    else -> {}
//                }
//                jsonArray.put(obj)
//            }
//        }
////        historyPrefs.edit().putString("history", jsonArray.toString()).apply()
//        historyPrefs.edit {
//            putString("history", jsonArray.toString())
//        }
//    }

    private fun saveHistory() {
        val cutoff = historyCutoffMillis()
        val jsonArray = JSONArray()

        messages.forEach { msg ->
//            if (msg is ChatItem.DateHeader) return@forEach
            if (msg.timestamp < cutoff) return@forEach  // ✅ מוחק בפועל היסטוריה ישנה

            val obj = JSONObject()
            when (msg) {
                is ChatItem.Text -> {
                    obj.put("type", "TEXT")
                    obj.put("text", msg.text)
                    obj.put("out", msg.isOutgoing)
                    obj.put("time", msg.timestamp)
                    jsonArray.put(obj)
                }
                is ChatItem.FileItem -> {
                    obj.put("type", "FILE")
                    obj.put("name", msg.name)
                    obj.put("path", msg.localPath)
                    obj.put("size", msg.sizeBytes)
                    obj.put("mime", msg.mimeType)
                    obj.put("out", msg.isOutgoing)
                    obj.put("time", msg.timestamp)
                    jsonArray.put(obj)
                }
                is ChatItem.DateHeader -> {
                }
            }
        }

        historyPrefs.edit { putString("history", jsonArray.toString()) }
    }



    private fun loadHistory() {
        messages.clear()
        val jsonStr = historyPrefs.getString("history", null) ?: return
        val cutoff = historyCutoffMillis()
        val jsonArray = JSONArray(jsonStr)

        // נבנה מחדש רק את מה שב-30 ימים האחרונים
        val kept = JSONArray()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val time = obj.optLong("time", 0L)
            if (time < cutoff) continue  // ✅ דילוג על ישן
            kept.put(obj)

            if (obj.getString("type") == "TEXT") {
                addMessage(ChatItem.Text(obj.getString("text"), obj.getBoolean("out"), time))
            } else {
                addMessage(ChatItem.FileItem(
                    obj.getString("name"),
                    obj.getString("path"),
                    obj.getLong("size"),
                    obj.getString("mime"),
                    obj.getBoolean("out"),
                    time))
            }
        }
        // ✅ ניקוי מיידי של ההיסטוריה הישנה מהדיסק
        historyPrefs.edit { putString("history", kept.toString()) }
    }



//    private fun getFileSize(uri: Uri): Long {
//        var size = 0L
//        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
//            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
//            if (sizeIndex >= 0 && cursor.moveToFirst()) {
//                size = cursor.getLong(sizeIndex)
//            }
//        }
//        return size
//    }

    private fun launchCamera() {
        try {
            // 1. צור קובץ זמני בתיקיית ה-Cache
            val tmpFile = File(cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            latestTmpFile = tmpFile

            // 2. צור URI מאובטח באמצעות FileProvider
            latestTmpUri = FileProvider.getUriForFile(
                this,
                "$packageName.provider", // חייב להתאים למה שכתבנו ב-Manifest
                tmpFile
            )

            // 3. הפעל את המצלמה
            takePhotoLauncher.launch(latestTmpUri)
        } catch (e: Exception) {
            Toast.makeText(this, "שגיאה בפתיחת מצלמה: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

/* =======================
   Chat models + adapter
   ======================= */

sealed class ChatItem {
    abstract val timestamp: Long

    data class Text(val text: String, val isOutgoing: Boolean,  override val timestamp: Long) : ChatItem()
    data class FileItem(
        val name: String,
        val localPath: String,
        val sizeBytes: Long,
        val mimeType: String,
        val isOutgoing: Boolean,
        override val timestamp: Long
    ) : ChatItem()

    data class DateHeader(
        val date: String,
        override val timestamp: Long
    ) : ChatItem()
}

class ChatBubblesAdapter(private val items: List<ChatItem>, private val context: Context) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_TEXT_OUT = 1
        private const val TYPE_TEXT_IN = 2
        private const val TYPE_FILE_OUT = 3
        private const val TYPE_FILE_IN = 4
        private const val TYPE_DATE = 5
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = items[position]) {
            is ChatItem.Text -> if (item.isOutgoing) TYPE_TEXT_OUT else TYPE_TEXT_IN
            is ChatItem.FileItem -> if (item.isOutgoing) TYPE_FILE_OUT else TYPE_FILE_IN
            is ChatItem.DateHeader -> TYPE_DATE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_TEXT_OUT -> TextVH(inflater.inflate(R.layout.item_chat_text_out, parent, false))
            TYPE_TEXT_IN -> TextVH(inflater.inflate(R.layout.item_chat_text_in, parent, false))
            TYPE_FILE_OUT -> FileVH(inflater.inflate(R.layout.item_chat_file_out, parent, false))
            TYPE_FILE_IN -> FileVH(inflater.inflate(R.layout.item_chat_file_in, parent, false))
            else -> DateVH(inflater.inflate(R.layout.item_chat_date, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatItem.Text -> (holder as TextVH).bind(item)
            is ChatItem.FileItem -> (holder as FileVH).bind(item, context)
            is ChatItem.DateHeader -> (holder as DateVH).bind(item)
        }
    }

    override fun getItemCount() = items.size

    class DateVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tv: TextView = view.findViewById(R.id.dateText)
        fun bind(item: ChatItem.DateHeader) { tv.text = item.date }
    }

    class TextVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tv: TextView = view.findViewById(R.id.messageText)
        private val timeTv: TextView? = view.findViewById(R.id.messageTime)

        fun bind(item: ChatItem.Text) {
            tv.text = item.text
            timeTv?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
        }
    }

//    class TextVH(view: View) : RecyclerView.ViewHolder(view) {
//        private val tv: TextView = view.findViewById(R.id.messageText)
//        fun bind(item: ChatItem.Text) { tv.text = item.text }
//    }

    class FileVH(view: View) : RecyclerView.ViewHolder(view) {
        private val nameTv: TextView = view.findViewById(R.id.fileName)
        private val metaTv: TextView = view.findViewById(R.id.fileMeta)
        private val thumb: ImageView = view.findViewById(R.id.thumbnail)
        private val timeTv: TextView? = view.findViewById(R.id.messageTime)


        fun bind(item: ChatItem.FileItem, ctx: Context) {
            nameTv.text = item.name
            timeTv?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
            metaTv.text = formatSize(item.sizeBytes)

            if (item.mimeType.startsWith("image/")) {
                thumb.setImageURI(Uri.fromFile(File(item.localPath)))
            } else {
                thumb.setImageResource(R.drawable.ic_file)
            }

//            val isImage = item.mimeType.startsWith("image/")
//            if (isImage) {
//                // Thumbnail אמיתי מהקובץ המקומי
//                thumb.clearColorFilter()
//                thumb.setImageURI(Uri.fromFile(File(item.localPath)))
//            } else {
//                // אייקון קובץ
//                thumb.setImageResource(R.drawable.ic_file)
//            }
//        }

            // ✅ לחיצה פותחת קובץ (כמו החדש)
            itemView.setOnClickListener {
                try {
                    val file = File(item.localPath)
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, item.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(ctx, "No app found to open file", Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "${bytes / 1024} KB"
                bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
                else -> "${bytes / (1024 * 1024 * 1024)} GB"
            }
        }
    }
}








//sealed class ChatItem {
//    data class Text(
//        val text: String,
//        val isOutgoing: Boolean // true = מימין, false = משמאל
//    ) : ChatItem()
//}
//
//class ChatBubblesAdapter(private val items: List<ChatItem>) :
//    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
//
//    private val TYPE_TEXT_OUT = 1
//    private val TYPE_TEXT_IN = 2
//
//    override fun getItemViewType(position: Int): Int {
//        return when (val item = items[position]) {
//            is ChatItem.Text -> if (item.isOutgoing) TYPE_TEXT_OUT else TYPE_TEXT_IN
//        }
//    }
//
//    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
//        val inflater = LayoutInflater.from(parent.context)
//        return when (viewType) {
//            TYPE_TEXT_OUT -> TextVH(inflater.inflate(R.layout.item_chat_text_out, parent, false))
//            else -> TextVH(inflater.inflate(R.layout.item_chat_text_in, parent, false))
//        }
//    }
//
//    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
//        val item = items[position] as ChatItem.Text
//        (holder as TextVH).bind(item)
//    }
//
//    override fun getItemCount() = items.size
//
//    class TextVH(view: View) : RecyclerView.ViewHolder(view) {
//        private val tv: TextView = view.findViewById(R.id.messageText)
//        fun bind(item: ChatItem.Text) {
//            tv.text = item.text
//        }
//    }
//}

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
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.res.ColorStateList
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

import android.os.Environment
import android.graphics.BitmapFactory
import android.util.Log
import android.media.MediaScannerConnection
import androidx.appcompat.app.AlertDialog


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
    private var latestTmpFile: File? = null // So we can make sure the photo was actually saved and not an empty file

    companion object { private const val HISTORY_TTL_DAYS = 30 }

    private fun historyCutoffMillis(): Long = System.currentTimeMillis() - HISTORY_TTL_DAYS * 24L * 60L * 60L * 1000L


    private val historyPrefs by lazy { getSharedPreferences("chat_history", MODE_PRIVATE) }

    // The receiver that listens for messages from the computer
    private val chatMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val jsonStr = intent?.getStringExtra("message") ?: return
            try {
                val json = JSONObject(jsonStr)
                // If this is a message received from the computer
                if (json.has("type")) {
                    when (json.getString("type")) {
                        "HANDSHAKE" -> {
                            // Getting the computer name
                            val pcName = json.optString("name", "PC")
                            statusText.text = getString(R.string.connected_to, pcName)
                        }
                        "FILE_RECEIVED" -> {

//                    val type = json.getString("type")
//                    if (type == "FILE_RECEIVED") {
                        // File uploaded from computer to phone
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
//                            isOutgoing = false
//                        ))
//                    } else {
//                        // A regular text message from your computer
//                        val text = json.optString("text")
//                        addMessage(ChatItem.Text(text = text, isOutgoing = false))
//                    }
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//            }
//        }
//    }

    // Contact permission
    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) Toast.makeText(this, "Need contacts permission", Toast.LENGTH_SHORT).show()
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

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (!success || latestTmpUri == null) return@registerForActivityResult

            // Protection: Sometimes success=true but the file is empty/not saved
            val f = latestTmpFile
            if (f == null || !f.exists() || f.length() <= 0L) {
                Toast.makeText(this, "The photo was not saved / the file is empty", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            // Handles just like a gallery
            handlePickedUri(latestTmpUri!!)
        }



//    private val cameraLauncher =
//        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
//            val filePath = result.data?.getStringExtra("captured_path")
//            if (!filePath.isNullOrBlank()) {
//                val f = File(filePath)
//                // Converts the file from the camera to a ChatItem
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

        // Always returns to activity_main.xml
        backButton.setOnClickListener {
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
            finish()
        }

        // Connected status with computer name (from Intent or SharedPreferences)
        val pcName = intent.getStringExtra("pc_name")
            ?: getSharedPreferences("conn", MODE_PRIVATE).getString("pc_name", "DESKTOP-XXXXX")
            ?: "DESKTOP-XXXXX"

        statusText.text = getString(R.string.status_connected, pcName)

        // RecyclerView (chat) + scroll to bottom
        layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        adapter = ChatBubblesAdapter(messages, this)
        chatRecycler.layoutManager = layoutManager
        chatRecycler.adapter = adapter

        sendButton.setOnClickListener { sendMessage() }
        plusButton.setOnClickListener { showAttachSheet()}

        loadHistory()

        // Handling external sharing (if we entered the app via Share)
        handleIncomingShare(intent)
    }

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

    // SingleTop support to receive shares even when the app is open
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingShare(intent)
    }

    private fun sendMessage() {
        val text = messageEditText.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "Write a message first 🙂", Toast.LENGTH_SHORT).show()
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
//        messages.add(ChatItem.Text(text = text, isOutgoing = true))
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

        // Adding days
        if (lastMsg == null || (lastMsg !is ChatItem.DateHeader && dateFormat.format(Date(lastMsg.timestamp)) != dateFormat.format(itemDate))) {
            val headerText = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.getDefault()).format(itemDate)
            messages.add(ChatItem.DateHeader(headerText, item.timestamp))
            adapter.notifyItemInserted(messages.size - 1)
        }
        // Add to memory
        messages.add(item)
        // Display update
        adapter.notifyItemInserted(messages.size - 1)
        // Scroll down
        chatRecycler.scrollToPosition(messages.size - 1)
    }

    private fun handlePickedUri(uri: Uri) {
        try {
            // Safe filename (including cases where there is no DISPLAY_NAME)
            var fileName = getFileName(uri) ?: "file_${System.currentTimeMillis()}"
//            val fileSize = destFile.length()
            // Safe MIME (audio/image/anything)
            val mime = contentResolver.getType(uri) ?: "application/octet-stream"

            fileName = ensureExtension(fileName, mime)

            // If the file name does not have an extension, we will add it according to the MimeType
            if (!fileName.contains(".")) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
                fileName = "$fileName.$ext"
            }

            // Local shared folder (permanent access to external audio as well)
            // Saves a local copy so you always have access + thumbnail of images
            val sharedDir = File(filesDir, "shared")
            if (!sharedDir.exists()) sharedDir.mkdirs()

            // If there is already an existing one with the same name - you can prevent overwriting with a unique name
            val destFile = uniqueFileInDir(sharedDir, fileName)
            val finalName = destFile.name

            // Safely copy from URI to local file
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: run {
                Toast.makeText(this, "Cannot open file stream", Toast.LENGTH_SHORT).show()
                return
            }

            // Reliable size after copying (and not relying on size from the URI)
            val fileSize = destFile.length()

            // Add to phone chat (outgoing bubble)
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

            // Sending a command to the PC to download from the phone via HTTP
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

            Toast.makeText(this, "Sent: $fileName", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

//            Toast.makeText(this, "selected: $fileName", Toast.LENGTH_SHORT).show()
//            val json = JSONObject().apply {
//                put("type", "FILE_TRANSFER")
//                put("fileName", fileName)
//                put("fileSize", fileSize)
//                put("mimeType", mime)
//                put("downloadPath", "/files/shared/$fileName")
//                put("timestamp", System.currentTimeMillis())
//            }
//            ServerManager.sendToPC(json.toString())

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

    //
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

    // Export a contact
    private fun exportContactAsVcf(contactUri: Uri) {
        try {
            val cursor = contentResolver.query(contactUri, null, null, null, null) ?: return
            cursor.use {
                if (!it.moveToFirst()) return

                // Get the LOOKUP_KEY (this is what Android needs for VCF)
                val lookupKeyIndex = it.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIndex = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)

                if (lookupKeyIndex == -1) return // protection

                val lookupKey = it.getString(lookupKeyIndex)
                val displayName = if (nameIndex != -1) it.getString(nameIndex) else "contact"

                // Creating the correct URI to download the VCard
                val vcardUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, lookupKey)

                // Preparing the local file
                val sharedDir = File(filesDir, "shared")
                if (!sharedDir.exists()) sharedDir.mkdirs()

                // Uses the contact's name as the file name (e.g.: "Yosi Cohen.vcf")
                val safeName = displayName.replace("[^a-zA-Z0-9 א-ת]".toRegex(), "_") // Cleans problematic characters
                val fileName = "$safeName.vcf"
                val destFile = File(sharedDir, fileName)

                // Copying the content to a file
                contentResolver.openInputStream(vcardUri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }

                // Add to chat
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

    private fun openNotesAppHint() {
        try {
            val samsungNotesPackage = "com.samsung.android.app.notes"

            // Try opening Samsung Notes specifically
            val launchIntent = packageManager.getLaunchIntentForPackage(samsungNotesPackage)
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                // If not installed (or the manifest is out of date), try opening any notes app
                // (Works on Android 14 and above, or on older Android with generic Intent)
                try {
                    val intent = Intent(Intent.ACTION_MAIN)
                    intent.addCategory("android.intent.category.APP_NOTES")
                    startActivity(intent)
                } catch (_: Exception) {
                    // If all else fails
                    Toast.makeText(this, "No notes app found. Open manually and share", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening the app: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

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
        // If there is already a period at the end – we will leave it
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
            if (msg.timestamp < cutoff) return@forEach  // Actually erases old history

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

        // We will only rebuild what was in the last 30 days
        val kept = JSONArray()

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val time = obj.optLong("time", 0L)
            if (time < cutoff) continue  // Skipping old
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
        // Instantly clear old history from disk
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
            // Create a temporary file in the Cache folder
            val tmpFile = File(cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            latestTmpFile = tmpFile

            // Create a secure URI using FileProvider
            latestTmpUri = FileProvider.getUriForFile(
                this,
                "$packageName.provider", // Must match what we wrote in the Manifest
                tmpFile
            )

            // Turn on the camera
            takePhotoLauncher.launch(latestTmpUri)
        } catch (e: Exception) {
            Toast.makeText(this, "Error opening camera: ${e.message}", Toast.LENGTH_SHORT).show()
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
        private val btnSaveAs: Button? = view.findViewById(R.id.btnSaveAs)


        fun bind(item: ChatItem.FileItem, ctx: Context) {
            nameTv.text = item.name
            timeTv?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
            metaTv.text = formatSize(item.sizeBytes)

            if (item.mimeType.startsWith("image/")) {
                loadImage(item.localPath)
//                thumb.setImageURI(Uri.fromFile(File(item.localPath)))
            } else {
                // Restore white tint for non-image files
                thumb.imageTintList = ColorStateList.valueOf(android.graphics.Color.WHITE)
                thumb.setImageResource(R.drawable.ic_file)
                thumb.clearColorFilter()
            }

            // Save button click handler
            btnSaveAs?.setOnClickListener {
                saveToDownloads(item, ctx)
            }

            // Click thumbnail or message to open file
            val openClickListener = View.OnClickListener {
                openFile(item, ctx)
            }
            thumb.setOnClickListener(openClickListener)
            itemView.setOnClickListener(openClickListener)
        }

//            val isImage = item.mimeType.startsWith("image/")
//            if (isImage) {
//                thumb.clearColorFilter()
//                thumb.setImageURI(Uri.fromFile(File(item.localPath)))
//            } else {
//                thumb.setImageResource(R.drawable.ic_file)
//            }
//        }


        private fun loadImage(filePath: String) {
            try {
                val file = File(filePath)
                Log.d("FileVH", "Loading image: $filePath")
                Log.d("FileVH", "File exists: ${file.exists()}, size: ${file.length()}")
                // Remove white tint before loading image
                thumb.imageTintList = null

                // Method 1: Try BitmapFactory
                if (file.exists() && file.length() > 0) {
                    val bitmap = BitmapFactory.decodeFile(filePath)
                    if (bitmap != null) {
                        thumb.setImageBitmap(bitmap)
                        thumb.clearColorFilter()
                        Log.d("FileVH", "Image loaded with BitmapFactory")
                        return
                    } else {
                        Log.w("FileVH", "BitmapFactory returned null")
                    }
                }
                // Method 2: Try with delay (file might still be writing)
                thumb.postDelayed({
                    try {
                        if (file.exists() && file.length() > 0) {
                            val bitmap = BitmapFactory.decodeFile(filePath)
                            if (bitmap != null) {
                                thumb.setImageBitmap(bitmap)
                                thumb.clearColorFilter()
                                Log.d("FileVH", "Image loaded on retry")
                                return@postDelayed
                            }
                        }
                        // Method 3: Try URI method
                        thumb.setImageURI(Uri.fromFile(file))
                        thumb.clearColorFilter()
                        Log.d("FileVH", "Image loaded with URI")
                    } catch (e: Exception) {
                        Log.e("FileVH", "All methods failed: ${e.message}")
                        showPlaceholder()
                    }
                }, 300)
            } catch (e: Exception) {
                Log.e("FileVH", "Error loading image: ${e.message}")
                showPlaceholder()
            }
        }

        private fun showPlaceholder() {
            thumb.setImageResource(R.drawable.ic_launcher_background)
            thumb.setColorFilter(android.graphics.Color.LTGRAY)
        }

        private fun saveToDownloads(item: ChatItem.FileItem, ctx: Context) {
            // Show dialog to get custom filename
            showSaveDialog(item, ctx)
        }

        // Show dialog for custom filename
        private fun showSaveDialog(item: ChatItem.FileItem, ctx: Context) {
            val dialogView = LayoutInflater.from(ctx).inflate(android.R.layout.select_dialog_item, null)
            val editText = EditText(ctx).apply {
                // Pre-fill with current filename (without extension)
                val nameWithoutExt = if (item.name.contains(".")) {
                    item.name.substringBeforeLast(".")
                } else {
                    item.name
                }
                setText(nameWithoutExt)
                hint = "Enter filename"
                setSingleLine()

                // Select all text for easy editing
                selectAll()
            }

            AlertDialog.Builder(ctx)
                .setTitle("Save File As")
                .setMessage("Enter a name for the file:")
                .setView(editText)
                .setPositiveButton("Save") { _, _ ->
                    val customName = editText.text.toString().trim()
                    if (customName.isNotEmpty()) {
                        performSave(item, customName, ctx)
                    } else {
                        Toast.makeText(ctx, "Filename cannot be empty", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }


        // ✅ NEW: Perform actual save with custom name
        private fun performSave(item: ChatItem.FileItem, customName: String, ctx: Context) {
            try {
                val sourceFile = File(item.localPath)
                // Check source exists
                if (!sourceFile.exists()) {
                    Toast.makeText(ctx, "File not found", Toast.LENGTH_SHORT).show()
                    Log.e("FileVH", "Source file doesn't exist: ${item.localPath}")
                    return
                }

                // Get the original extension
                val originalExt = if (item.name.contains(".")) {
                    item.name.substringAfterLast(".")
                } else {
                    getExtensionFromMimeType(item.mimeType)
                }

                // Check if user provided extension
                val finalName = if (customName.contains(".")) {
                    // User provided extension - use it
                    customName
                } else {
                    // No extension - add it automatically
                    "$customName.$originalExt"
                }

                // Get Downloads directory
                val downloadsDir =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                // Create StreamBridge folder
                val streamBridgeDir = File(downloadsDir, "StreamBridge")
                if (!streamBridgeDir.exists()) {
                    val created = streamBridgeDir.mkdirs()
                    Log.d("FileVH", "Created StreamBridge dir: $created")
                }
                val destFile = File(streamBridgeDir, finalName)

                // Check if file exists
                if (destFile.exists()) {
                    showOverwriteDialog(sourceFile, destFile, finalName, ctx)
                } else {
                    // Save directly
                    sourceFile.copyTo(destFile, overwrite = false)
                    showSaveSuccess(destFile, ctx)
                }

            } catch (e: Exception) {
                Log.e("FileVH", "❌ Save error: ${e.message}", e)
                Toast.makeText(ctx, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
                // Generate unique filename with timestamp
//                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
//                val originalName = item.name
//                val extension = if (originalName.contains(".")) originalName.substringAfterLast(".") else ""
//                val nameWithoutExt = if (originalName.contains(".")) originalName.substringBeforeLast(".") else originalName
//                val destFileName = if (extension.isNotEmpty()) "${nameWithoutExt}_$timestamp.$extension" else "${originalName}_$timestamp"
//                val destFile = File(streamBridgeDir, destFileName)

                // Copy file
//                sourceFile.copyTo(destFile, overwrite = true)
//                // Show success message
//                Toast.makeText(ctx, "Saved to Downloads/StreamBridge/\n$destFileName", Toast.LENGTH_LONG).show()
//                Log.d("FileVH", "Saved: ${destFile.absolutePath}")
//                // Make file visible in file manager
//                MediaScannerConnection.scanFile( ctx, arrayOf(destFile.absolutePath), null ) { path, uri ->
//                    Log.d("FileVH", "File scanned: $path")
//                }
//            } catch (e: Exception) {
//                Log.e("FileVH", "❌ Save error: ${e.message}", e)
//                Toast.makeText(ctx, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
//            }
//        }

        // Ask user if they want to overwrite existing file
        private fun showOverwriteDialog(sourceFile: File, destFile: File, filename: String, ctx: Context) {
            AlertDialog.Builder(ctx)
                .setTitle("File Already Exists")
                .setMessage("$filename already exists. Do you want to overwrite it?")
                .setPositiveButton("Overwrite") { _, _ ->
                    try {
                        sourceFile.copyTo(destFile, overwrite = true)
                        showSaveSuccess(destFile, ctx)
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Failed to overwrite: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Show success message and scan file
        private fun showSaveSuccess(destFile: File, ctx: Context) {
            Toast.makeText(
                ctx,
                "Saved to Downloads/StreamBridge/\n${destFile.name}",
                Toast.LENGTH_LONG
            ).show()

            Log.d("FileVH", "Saved: ${destFile.absolutePath}")

            MediaScannerConnection.scanFile(ctx, arrayOf(destFile.absolutePath), null, null)
        }

        // Get file extension from MIME type
        private fun getExtensionFromMimeType(mimeType: String): String {
            return when {
                mimeType.startsWith("image/jpeg") -> "jpg"
                mimeType.startsWith("image/png") -> "png"
                mimeType.startsWith("image/gif") -> "gif"
                mimeType.startsWith("image/webp") -> "webp"
                mimeType.startsWith("image/bmp") -> "bmp"
                mimeType.startsWith("image/") -> "jpg" // Default for images
                mimeType.startsWith("video/mp4") -> "mp4"
                mimeType.startsWith("video/") -> "mp4" // Default for videos
                mimeType.startsWith("audio/mpeg") -> "mp3"
                mimeType.startsWith("audio/") -> "mp3" // Default for audio
                mimeType == "application/pdf" -> "pdf"
                mimeType == "application/zip" -> "zip"
                mimeType.startsWith("text/") -> "txt"
                else -> "bin" // Unknown type
            }
        }

        private fun openFile(item: ChatItem.FileItem, ctx: Context) {
            try {
                val file = File(item.localPath)
                if (!file.exists()) {
                    Toast.makeText(ctx, "File not found", Toast.LENGTH_SHORT).show()
                    return
                }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, item.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ctx.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(ctx, "No app found to open file", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Cannot open: ${e.message}", Toast.LENGTH_SHORT).show()
                Log.e("FileVH", "Open failed: ${e.message}", e)
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
//        val isOutgoing: Boolean
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

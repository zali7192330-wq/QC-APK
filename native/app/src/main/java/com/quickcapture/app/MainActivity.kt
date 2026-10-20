package com.quickcapture.app

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var input: EditText
    private lateinit var preview: TextView
    private lateinit var chipPriority: TextView
    private lateinit var chipTopic: TextView
    private lateinit var chipCategory: TextView
    private lateinit var chipDate: TextView
    private lateinit var attachmentsRow: LinearLayout
    private lateinit var saveButton: Button
    private lateinit var recordButton: Button

    private val attachments = mutableListOf<CaptureAttachment>()
    private var suppressWatcher = false
    private var pickerShowing = false

    private val knownLists = arrayOf("Inbox", "Education", "Career", "Fitness", "Health", "Personal")
    private val knownCategories = arrayOf("Spark", "TIL", "Prompt")

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { addAttachment(it) }
        }

    private val pickPhotos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
            uris.forEach { addAttachment(it) }
        }

    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
            uris.forEach { addAttachment(it) }
        }

    // Camera results: the photo/video lands in the pending file we handed to
    // the camera app through FileProvider.
    private var pendingCameraFile: File? = null
    private var pendingCameraIsImage: Boolean = true

    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            onCameraResult(ok)
        }

    private val captureVideo =
        registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
            onCameraResult(ok)
        }

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startRecording()
            else Toast.makeText(this, getString(R.string.msg_mic_denied), Toast.LENGTH_SHORT).show()
        }

    // Voice recording state
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordStartMs: Long = 0L
    private val recordHandler = Handler(Looper.getMainLooper())
    private val recordTicker = object : Runnable {
        override fun run() {
            if (recorder != null) {
                val secs = (SystemClock.elapsedRealtime() - recordStartMs) / 1000
                recordButton.text = "⏹ %d:%02d".format(secs / 60, secs % 60)
                recordHandler.postDelayed(this, 500)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        input = findViewById(R.id.inputEditText)
        preview = findViewById(R.id.previewTextView)
        chipPriority = findViewById(R.id.chipPriority)
        chipTopic = findViewById(R.id.chipTopic)
        chipCategory = findViewById(R.id.chipCategory)
        chipDate = findViewById(R.id.chipDate)
        attachmentsRow = findViewById(R.id.attachmentsRow)
        saveButton = findViewById(R.id.saveButton)
        recordButton = findViewById(R.id.recordButton)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (suppressWatcher || s == null) return
                if (count == 1 && !pickerShowing) {
                    when (s[start]) {
                        '~' -> showPicker(getString(R.string.title_list_picker), knownLists, '~', start)
                        '!' -> showPicker(getString(R.string.title_category_picker), knownCategories, '!', start)
                    }
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (!suppressWatcher) updatePreview()
            }
        })

        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteFromClipboard() }
        findViewById<Button>(R.id.attachButton).setOnClickListener { showAttachMenu() }
        recordButton.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { clearAll() }
        saveButton.setOnClickListener { save() }

        handleShareIntent(intent)
        updatePreview()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
                suppressWatcher = true
                input.setText(shared)
                input.setSelection(shared.length)
                suppressWatcher = false
                updatePreview()
            }
        }
    }

    // ---- parsing + preview ------------------------------------------------

    private fun updatePreview() {
        val text = input.text.toString()
        val parsed = SmartParse.parse(text, knownLists, knownCategories)

        val sb = SpannableStringBuilder(text)
        for (t in parsed.tokens) {
            val color = when (t.kind) {
                TokenKind.Priority -> R.color.mod_priority
                TokenKind.List -> R.color.mod_list
                TokenKind.Tag -> R.color.mod_tag
                TokenKind.Category -> R.color.mod_category
                TokenKind.DateTime -> R.color.mod_date
            }
            val end = (t.start + t.length).coerceAtMost(sb.length)
            if (t.start < end) {
                sb.setSpan(
                    ForegroundColorSpan(ContextCompat.getColor(this, color)),
                    t.start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        preview.text = sb

        chipPriority.text = parsed.priority ?: "P–"
        val topicParts = mutableListOf<String>()
        parsed.list?.let { topicParts.add(it) }
        topicParts.addAll(parsed.tags)
        chipTopic.text = if (topicParts.isEmpty()) "—" else topicParts.joinToString(", ")
        chipCategory.text = parsed.category ?: "—"
        chipDate.text = parsed.reminder?.let { formatReminder(it) } ?: "—"
    }

    private fun formatReminder(r: java.time.ZonedDateTime): String {
        val today = LocalDate.now()
        val day = when (r.toLocalDate()) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> r.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))
        }
        return if (r.toLocalTime() == LocalTime.MIDNIGHT) day
        else "$day ${r.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}"
    }

    // ---- ~ and ! pickers ---------------------------------------------------

    private fun showPicker(title: String, items: Array<String>, trigger: Char, triggerPos: Int) {
        pickerShowing = true
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items) { _, which ->
                insertToken(trigger, triggerPos, items[which])
                pickerShowing = false
            }
            .setOnCancelListener { pickerShowing = false }
            .show()
    }

    private fun insertToken(trigger: Char, triggerPos: Int, choice: String) {
        suppressWatcher = true
        try {
            val editable = input.text
            val cursor = input.selectionStart.coerceAtLeast(triggerPos)
            val safePos = triggerPos.coerceIn(0, editable.length)
            val safeEnd = cursor.coerceIn(safePos, editable.length)
            // keep the token separated so the parser sees it: "idea!" + pick -> "idea !Spark "
            val needsSpace = safePos > 0 && !editable[safePos - 1].isWhitespace()
            val replacement = (if (needsSpace) " " else "") + "$trigger$choice "
            editable.replace(safePos, safeEnd, replacement)
            input.setSelection((safePos + replacement.length).coerceAtMost(editable.length))
        } finally {
            suppressWatcher = false
        }
        updatePreview()
    }

    // ---- attachments -------------------------------------------------------

    private fun addAttachment(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // some providers don't support persistable permissions; proceed anyway
        }

        var displayName = "file"
        var mime: String? = null
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )
            cursor?.let {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) displayName = it.getString(idx) ?: displayName
                }
            }
            mime = contentResolver.getType(uri)
        } catch (e: Exception) {
            // fall back to defaults
        } finally {
            cursor?.close()
        }

        var safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        // Clipboard images often arrive without a file extension; add one from
        // the mime type so the upload carries the right content type.
        if (!safeName.contains('.')) {
            extensionForMime(mime)?.let { ext ->
                displayName = "$displayName.$ext"
                safeName = "$safeName.$ext"
            }
        }
        val tmp = File(cacheDir, "qc_${System.currentTimeMillis()}_$safeName")
        try {
            contentResolver.openInputStream(uri)?.use { ins ->
                tmp.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: run {
                Toast.makeText(this, "Could not read $displayName", Toast.LENGTH_SHORT).show()
                return
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Could not read $displayName", Toast.LENGTH_SHORT).show()
            return
        }

        val isImage = (mime ?: guessMime(safeName)).startsWith("image/")
        registerAttachment(CaptureAttachment(displayName, tmp, isImage))
    }

    private fun registerAttachment(att: CaptureAttachment) {
        attachments.add(att)
        attachmentsRow.addView(makeAttachmentChip(att))
    }

    private fun extensionForMime(mime: String?): String? = when (mime) {
        "image/png" -> "png"
        "image/jpeg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/bmp" -> "bmp"
        "application/pdf" -> "pdf"
        else -> null
    }

    private fun guessMime(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun makeAttachmentChip(att: CaptureAttachment): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.chip_bg)
            val p = (8 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p / 2, p / 2)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        val label = TextView(this).apply {
            text = (if (att.isImage) "🖼 " else "📎 ") + att.displayName
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 13f
        }
        val remove = TextView(this).apply {
            text = "  ✕"
            setTextColor(ContextCompat.getColor(context, R.color.danger))
            textSize = 14f
            setPadding((8 * resources.displayMetrics.density).toInt(), 0, 0, 0)
            setOnClickListener {
                attachments.remove(att)
                runCatching { att.file.delete() }
                attachmentsRow.removeView(row)
            }
        }
        row.addView(label)
        row.addView(remove)
        return row
    }

    private fun clearAttachments() {
        attachments.forEach { runCatching { it.file.delete() } }
        attachments.clear()
        attachmentsRow.removeAllViews()
    }

    // ---- attach menu: camera / photos / videos / files ---------------------

    private fun showAttachMenu() {
        val items = arrayOf(
            getString(R.string.menu_camera_photo),
            getString(R.string.menu_camera_video),
            getString(R.string.menu_photos),
            getString(R.string.menu_videos),
            getString(R.string.menu_files)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_attach_menu))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchCamera(image = true)
                    1 -> launchCamera(image = false)
                    2 -> pickPhotos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                    3 -> pickVideos.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                    4 -> pickFiles.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun launchCamera(image: Boolean) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(cacheDir, "captures").apply { mkdirs() }
            val file = if (image) File(dir, "IMG_$stamp.jpg") else File(dir, "VID_$stamp.mp4")
            pendingCameraFile = file
            pendingCameraIsImage = image
            val uri = FileProvider.getUriForFile(this, "com.quickcapture.app.fileprovider", file)
            if (image) takePicture.launch(uri) else captureVideo.launch(uri)
        } catch (e: Exception) {
            pendingCameraFile = null
            Toast.makeText(this, getString(R.string.msg_camera_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun onCameraResult(ok: Boolean) {
        val file = pendingCameraFile
        pendingCameraFile = null
        if (file == null) return
        if (ok && file.exists() && file.length() > 0) {
            registerAttachment(CaptureAttachment(file.name, file, pendingCameraIsImage))
        } else {
            runCatching { file.delete() }
            if (!ok) return // user cancelled the camera — stay quiet
            Toast.makeText(this, getString(R.string.msg_camera_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ---- voice recording -----------------------------------------------------

    private fun toggleRecording() {
        if (recorder != null) {
            stopRecording()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startRecording()
        }
    }

    @Suppress("DEPRECATION") // no-arg MediaRecorder keeps minSdk 26 support
    private fun startRecording() {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val dir = File(cacheDir, "recordings").apply { mkdirs() }
            val file = File(dir, "Voice note $stamp.m4a")
            val rec = MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(128_000)
            rec.setAudioSamplingRate(44_100)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            recordingFile = file
            recordStartMs = SystemClock.elapsedRealtime()
            recordButton.text = "⏹ 0:00"
            recordHandler.postDelayed(recordTicker, 500)
        } catch (e: Exception) {
            recorder = null
            recordingFile = null
            Toast.makeText(this, getString(R.string.msg_recording_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        val rec = recorder ?: return
        val file = recordingFile
        recorder = null
        recordingFile = null
        recordHandler.removeCallbacks(recordTicker)
        recordButton.text = getString(R.string.action_record)
        val elapsedMs = SystemClock.elapsedRealtime() - recordStartMs
        try {
            rec.stop()
        } catch (e: RuntimeException) {
            // stopping almost immediately throws — treat as too short
            runCatching { file?.delete() }
            Toast.makeText(this, getString(R.string.msg_recording_too_short), Toast.LENGTH_SHORT).show()
            runCatching { rec.release() }
            return
        }
        runCatching { rec.release() }
        if (file != null && file.exists() && file.length() > 0 && elapsedMs >= 600) {
            registerAttachment(CaptureAttachment(file.name, file, isImage = false))
        } else {
            runCatching { file?.delete() }
            Toast.makeText(this, getString(R.string.msg_recording_too_short), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        // Never leave the mic hot or a recorder dangling if the app is closed.
        recordHandler.removeCallbacks(recordTicker)
        recorder?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
        super.onDestroy()
    }

    // ---- clipboard paste ---------------------------------------------------

    /**
     * Paste button: an image copied from any app (gallery, browser, chat…)
     * becomes an attachment; clipboard text is inserted at the cursor.
     */
    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, getString(R.string.msg_clipboard_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val clipHasImage = (0 until clip.description.mimeTypeCount).any {
            clip.description.getMimeType(it).startsWith("image/")
        }
        var addedImage = false
        var pastedText = false
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val uri = item.uri
            if (uri != null) {
                val type = runCatching { contentResolver.getType(uri) }.getOrNull()
                if (type?.startsWith("image/") == true || (type == null && clipHasImage)) {
                    val before = attachments.size
                    addAttachment(uri)
                    if (attachments.size > before) {
                        addedImage = true
                        continue
                    }
                }
            }
            if (!addedImage) {
                val text = runCatching { item.coerceToText(this)?.toString() }.getOrNull()
                if (!text.isNullOrEmpty()) {
                    insertTextAtCursor(text)
                    pastedText = true
                }
            }
        }
        when {
            addedImage ->
                Toast.makeText(this, getString(R.string.msg_pasted_image), Toast.LENGTH_SHORT).show()
            !pastedText ->
                Toast.makeText(this, getString(R.string.msg_clipboard_no_image), Toast.LENGTH_SHORT).show()
        }
    }

    private fun insertTextAtCursor(text: String) {
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        input.text.replace(start, end, text)
    }

    // ---- save --------------------------------------------------------------

    private fun save() {
        val text = input.text.toString()
        val parsed = SmartParse.parse(text, knownLists, knownCategories)
        val clean = parsed.cleanText
        if (clean.isBlank() && attachments.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_nothing), Toast.LENGTH_SHORT).show()
            return
        }
        saveButton.isEnabled = false
        val snapshot = attachments.toList()
        thread {
            val topicParts = mutableListOf<String>()
            parsed.list?.let { topicParts.add(it) }
            topicParts.addAll(parsed.tags)
            val topic = topicParts.joinToString(", ").ifEmpty { null }
            val (ok, msg) = NotionClient.createCapture(
                clean, snapshot, parsed.reminder, topic, parsed.priority, parsed.category
            )
            runOnUiThread {
                saveButton.isEnabled = true
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                if (ok) clearAll()
            }
        }
    }

    private fun clearAll() {
        suppressWatcher = true
        input.text.clear()
        suppressWatcher = false
        clearAttachments()
        updatePreview()
    }
}

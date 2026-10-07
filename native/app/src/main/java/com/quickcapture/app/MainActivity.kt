package com.quickcapture.app

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
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

    private val attachments = mutableListOf<CaptureAttachment>()
    private var suppressWatcher = false
    private var pickerShowing = false

    private val knownLists = arrayOf("Inbox", "Education", "Career", "Fitness", "Health", "Personal")
    private val knownCategories = arrayOf("Spark", "TIL", "Prompt")

    private val pickFiles =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            uris.forEach { addAttachment(it) }
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

        findViewById<Button>(R.id.attachButton).setOnClickListener {
            pickFiles.launch(arrayOf("*/*"))
        }
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

        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
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
        val att = CaptureAttachment(displayName, tmp, isImage)
        attachments.add(att)
        attachmentsRow.addView(makeAttachmentChip(att))
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

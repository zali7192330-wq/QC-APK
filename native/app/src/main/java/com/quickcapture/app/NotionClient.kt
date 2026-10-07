package com.quickcapture.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Kotlin port of the Windows QuickCapture NotionClient.cs — identical API flow:
 * file uploads go through file_uploads create -> send, then a single
 * POST /v1/pages creates the capture with properties + children blocks.
 */
data class CaptureAttachment(
    val displayName: String,
    val file: File,          // local temp copy, ready to upload
    val isImage: Boolean
)

object NotionClient {
    private const val API_BASE = "https://api.notion.com/v1"
    private const val NOTION_VERSION = "2022-06-28"
    private const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024 // Notion single-part limit

    private val token: String get() = BuildConfig.NOTION_TOKEN
    private val databaseId: String get() = BuildConfig.NOTION_DATABASE_ID

    private fun open(path: String): HttpURLConnection {
        val c = URL("$API_BASE$path").openConnection() as HttpURLConnection
        c.connectTimeout = 60_000
        c.readTimeout = 60_000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Notion-Version", NOTION_VERSION)
        return c
    }

    fun createCapture(
        text: String,
        attachments: List<CaptureAttachment>,
        reminder: ZonedDateTime? = null,
        topic: String? = null,
        priority: String? = null,
        category: String? = null
    ): Pair<Boolean, String> {
        return try {
            val now = ZonedDateTime.now()
            val title = if (text.isBlank()) {
                "Capture ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}"
            } else firstLine(text, 80)

            val children = JSONArray()
            val skipped = mutableListOf<String>()

            // Attachments first, note text after. Images before other files.
            for (a in attachments.sortedByDescending { it.isImage }) {
                if (!a.file.exists()) {
                    skipped.add("${a.displayName} (file missing)")
                    continue
                }
                if (a.file.length() > MAX_UPLOAD_BYTES) {
                    skipped.add("${a.displayName} (over 20 MB, kept locally only)")
                    continue
                }
                val uploadId = uploadFile(a)
                if (uploadId == null) {
                    skipped.add("${a.displayName} (upload failed, kept locally)")
                    continue
                }
                val block = JSONObject()
                    .put("object", "block")
                    .put("type", if (a.isImage) "image" else "file")
                val payload = JSONObject()
                    .put("type", "file_upload")
                    .put("file_upload", JSONObject().put("id", uploadId))
                block.put(if (a.isImage) "image" else "file", payload)
                children.put(block)
            }

            for (chunk in chunk(text, 2000)) {
                children.put(
                    JSONObject()
                        .put("object", "block")
                        .put("type", "paragraph")
                        .put(
                            "paragraph", JSONObject().put(
                                "rich_text", JSONArray().put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put("text", JSONObject().put("content", chunk))
                                )
                            )
                        )
                )
            }

            val props = JSONObject()
            props.put("Name", JSONObject().put("title", JSONArray().put(
                JSONObject().put("text", JSONObject().put("content", title)))))
            props.put("Captured", JSONObject().put("date", JSONObject()
                .put("start", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))))
            props.put("Source", JSONObject().put("select", JSONObject().put("name", "Quick Capture")))
            props.put("Note", JSONObject().put("rich_text", JSONArray().put(
                JSONObject().put("type", "text")
                    .put("text", JSONObject().put("content", firstLine(text, 2000))))))
            if (reminder != null) {
                props.put("Date", JSONObject().put("date", JSONObject()
                    .put("start", reminder.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))))
            }
            if (!topic.isNullOrBlank()) {
                val t = if (topic.length > 2000) topic.substring(0, 2000) else topic
                props.put("Topic", JSONObject().put("rich_text", JSONArray().put(
                    JSONObject().put("type", "text").put("text", JSONObject().put("content", t)))))
            }
            if (!priority.isNullOrBlank()) {
                props.put("Priority", JSONObject().put("select", JSONObject().put("name", priority)))
            }
            if (!category.isNullOrBlank()) {
                props.put("Category", JSONObject().put("select", JSONObject().put("name", category)))
            }

            val payload = JSONObject()
                .put("parent", JSONObject().put("database_id", databaseId))
                .put("properties", props)
                .put("children", children)

            val conn = open("/pages")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = readStream(conn).ifBlank { "HTTP $code" }
                return Pair(false, "Notion error $code: ${trim(err)}")
            }
            Pair(
                true,
                if (skipped.isEmpty()) "Saved to Notion"
                else "Saved to Notion (${skipped.joinToString("; ")})"
            )
        } catch (e: Exception) {
            Pair(false, "Notion failed: ${e.message}")
        }
    }

    /** Mirrors NotionClient.UploadFileAsync: create -> send (single_part). */
    private fun uploadFile(a: CaptureAttachment): String? {
        return try {
            val contentType = contentTypeFor(a.file.extension)
            val createJson = JSONObject()
                .put("filename", a.displayName)
                .put("content_type", contentType)
                .put("mode", "single_part")

            val createConn = open("/file_uploads")
            createConn.requestMethod = "POST"
            createConn.doOutput = true
            createConn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            createConn.outputStream.use { it.write(createJson.toString().toByteArray(Charsets.UTF_8)) }
            if (createConn.responseCode !in 200..299) return null
            val id = JSONObject(readStream(createConn)).optString("id", "")
            if (id.isEmpty()) return null

            // multipart send
            val boundary = "----QC${UUID.randomUUID().toString().replace("-", "")}"
            val sendConn = open("/file_uploads/$id/send")
            sendConn.requestMethod = "POST"
            sendConn.doOutput = true
            sendConn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val out = ByteArrayOutputStream()
            val w = out.writer(Charsets.UTF_8)
            w.write("--$boundary\r\n")
            // escape quotes in filename for the header
            val safeName = a.displayName.replace("\"", "_")
            w.write("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n")
            w.write("Content-Type: $contentType\r\n\r\n")
            w.flush()
            out.write(a.file.readBytes())
            w.write("\r\n--$boundary--\r\n")
            w.flush()
            sendConn.outputStream.use { it.write(out.toByteArray()) }
            if (sendConn.responseCode in 200..299) id else null
        } catch (e: Exception) {
            null
        }
    }

    private fun readStream(conn: HttpURLConnection): String {
        return try {
            val s = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            s?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun contentTypeFor(ext: String): String = when (ext.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "zip" -> "application/zip"
        "mp4" -> "video/mp4"
        "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    private fun firstLine(s: String, max: Int): String {
        val line = s.split('\n')[0].trim()
        return if (line.length <= max) line else line.substring(0, max)
    }

    private fun chunk(s: String, size: Int): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            out.add(s.substring(i, minOf(i + size, s.length)))
            i += size
        }
        return out
    }

    private fun trim(s: String): String = if (s.length > 180) s.substring(0, 180) + "…" else s
}

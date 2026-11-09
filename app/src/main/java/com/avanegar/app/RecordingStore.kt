package com.avanegar.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** متن و فایل صوتی فقط در حافظه خصوصی این برنامه ذخیره می‌شوند. */
class RecordingStore(private val context: Context) {
    data class Entry(
        val id: String,
        val createdAt: Long,
        val text: String,
        val audioName: String
    )

    private val prefs = context.getSharedPreferences("avanegar_archive", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "recordings").apply { mkdirs() }

    @Synchronized fun list(): List<Entry> {
        val raw = prefs.getString("entries", "[]").orEmpty()
        val arr = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val v = arr.getJSONObject(i)
                Entry(v.getString("id"), v.getLong("time"),
                    v.optString("text"), v.optString("audio"))
            } catch (_: Exception) { null }
        }
    }

    @Synchronized fun save(text: String, pcm: File?): Entry {
        val id = UUID.randomUUID().toString()
        val audioName = if (pcm != null && pcm.isFile && pcm.length() > 0) {
            val target = File(folder, id + ".wav")
            writeWav(pcm, target)
            id + ".wav"
        } else ""
        pcm?.delete()
        val entry = Entry(id, System.currentTimeMillis(), text.trim(), audioName)
        val entries = mutableListOf(entry).also { it.addAll(list()) }
        // تعداد ضبط‌ها محدود می‌شود، فایل‌های قدیمی نیز پاک می‌شوند.
        val kept = entries.take(50)
        entries.drop(50).forEach { audioOf(it)?.delete() }
        saveList(kept)
        return entry
    }

    @Synchronized fun delete(entry: Entry) {
        audioOf(entry)?.delete()
        saveList(list().filterNot { it.id == entry.id })
    }

    fun audioOf(entry: Entry): File? {
        if (!Regex("[a-fA-F0-9-]{36}\\.wav").matches(entry.audioName)) return null
        return File(folder, entry.audioName).takeIf { it.isFile }
    }

    fun label(entry: Entry): String {
        val date = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale("fa", "IR"))
            .format(Date(entry.createdAt))
        val preview = entry.text.replace('\n', ' ').trim().take(54)
        return date + "\n" + (if (preview.isBlank()) "ضبط صوتی" else preview)
    }

    private fun saveList(entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("time", e.createdAt)
                .put("text", e.text).put("audio", e.audioName))
        }
        prefs.edit().putString("entries", arr.toString()).apply()
    }

    private fun littleInt(out: DataOutputStream, value: Long) {
        val v = value.toInt()
        out.writeByte(v and 0xff)
        out.writeByte((v ushr 8) and 0xff)
        out.writeByte((v ushr 16) and 0xff)
        out.writeByte((v ushr 24) and 0xff)
    }
    private fun littleShort(out: DataOutputStream, value: Int) {
        out.writeByte(value and 0xff)
        out.writeByte((value ushr 8) and 0xff)
    }

    private fun writeWav(source: File, target: File) {
        val length = source.length()
        require(length in 1..0xfffffff0L) { "ضبط صوتی بسیار بزرگ است" }
        try {
            DataOutputStream(BufferedOutputStream(FileOutputStream(target))).use { out ->
                out.writeBytes("RIFF")
                littleInt(out, length + 36)
                out.writeBytes("WAVE")
                out.writeBytes("fmt ")
                littleInt(out, 16)
                littleShort(out, 1)
                littleShort(out, 1)
                littleInt(out, 24000)
                littleInt(out, 48000)
                littleShort(out, 2)
                littleShort(out, 16)
                out.writeBytes("data")
                littleInt(out, length)
                FileInputStream(source).use { input -> input.copyTo(out, 32 * 1024) }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }
}

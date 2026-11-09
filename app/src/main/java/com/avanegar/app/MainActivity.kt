package com.avanegar.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

/**
 * آوانگار، نسخه ۰.۳.۱ - یک کادر متن + یک دکمه ضبط.
 * The user's API key is kept only in Android Keystore-encrypted local storage.
 * Realtime transcription uses the API's transcription session (no server VAD).
 */
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_RECORD = 301
        private const val REQUEST_FILE = 302
        private const val REQUEST_EXPORT = 303
        private const val SAMPLE_RATE = 24_000
        private const val FRAME_BYTES = 4_800 // 100ms, mono PCM16
        private const val COMMIT_EVERY_MS = 3_000 // periodic flush even for quiet speech
        private const val HISTORY_KEY = "text_history"
        private const val CURRENT_KEY = "current_text"
    }

    private val ui = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences("avanegar_data", MODE_PRIVATE) }
    private val secrets by lazy { SecureApiKeyStore(this) }

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var recordButton: Button

    private var socket: WebSocket? = null
    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    private var connecting = false
    @Volatile private var recording = false
    private var stopping = false
    private var uploading = false
    private var micFinished = false
    @Volatile private var generation = 0
    private val pending = AtomicInteger(0)
    private val paragraphs = StringBuilder()
    private val partials = LinkedHashMap<String, String>()
    private val finalizedIds = HashSet<String>()
    private var recordingBase = ""
    private var lastAutosave = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 16, 34)
        window.navigationBarColor = Color.rgb(13, 16, 34)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        transcriptScroll = findViewById(R.id.transcriptScroll)
        recordButton = findViewById(R.id.recordButton)
        transcript.text = prefs.getString(CURRENT_KEY, "").orEmpty()
        transcript.setOnClickListener { copyText() }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener { showSettings() }
        recordButton.setOnClickListener {
            if (recording || connecting) stopRecording() else startRecordingWithPermission()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun busy() = recording || connecting || stopping || uploading
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun currentText() = transcript.text.toString().trim()

    private fun copyText() {
        val all = currentText()
        if (all.isBlank()) return toast("هنوز متنی برای کپی وجود ندارد")
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
            ClipData.newPlainText("متن فارسی آوانگار", all)
        )
        toast("همه متن کپی شد ✓")
    }

    private fun persistText() = prefs.edit().putString(CURRENT_KEY, transcript.text.toString()).apply()

    private fun renderLive() {
        val combined = buildString {
            append(recordingBase)
            append(paragraphs)
            for (part in partials.values) {
                if (part.isNotBlank()) append(part)
            }
        }.trimEnd()
        transcript.text = combined
        if (System.currentTimeMillis() - lastAutosave >= 1800) {
            lastAutosave = System.currentTimeMillis()
            persistText()
        }
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // A deliberately small settings menu. No secondary controls clutter the home screen.
    private fun showSettings() {
        val scroller = ScrollView(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(12))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        scroller.addView(panel)
        val keyLabel = TextView(this).apply {
            text = "کلید OpenAI API"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(12), 0, dp(7))
        }
        panel.addView(keyLabel)
        val keyInput = EditText(this).apply {
            hint = "کلید را اینجا پیست کنید (sk-...)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }
        panel.addView(keyInput)
        val keyInfo = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(190, 190, 215))
            text = keyStatus()
            setPadding(0, dp(6), 0, dp(8))
        }
        panel.addView(keyInfo)

        fun button(title: String, action: () -> Unit) {
            val btn = Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 14f
                setOnClickListener { action() }
            }
            panel.addView(btn, LinearLayout.LayoutParams(-1, dp(51)))
        }
        button("ذخیره کلید API") {
            if (saveKey(keyInput.text.toString())) {
                keyInput.text.clear()
                keyInfo.text = keyStatus()
            }
        }
        button("تست اتصال OpenAI") {
            val entered = keyInput.text.toString().trim()
            if (entered.isNotBlank()) {
                if (!saveKey(entered)) return@button
                keyInput.text.clear()
            }
            val key = savedKey() ?: return@button
            keyInfo.text = "در حال آزمایش کلید…"
            testConnection(key, keyInfo)
        }
        button("حذف کلید API") {
            AlertDialog.Builder(this).setMessage("کلید ذخیره‌شده از این گوشی حذف شود؟")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("حذف") { _, _ ->
                    secrets.clear()
                    keyInput.text.clear()
                    keyInfo.text = keyStatus()
                    toast("کلید حذف شد")
                }.show()
        }

        panel.addView(TextView(this).apply {
            text = "ابزارهای متن"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(0, dp(20), 0, dp(6))
        })
        // Secondary tasks live exclusively behind the gear icon.
        lateinit var dialog: AlertDialog
        button("انتخاب و تبدیل فایل صوتی") {
            if (busy()) return@button toast("ابتدا عملیات فعلی را تمام کنید")
            if (savedKey() == null) return@button
            dialog.dismiss()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }, REQUEST_FILE)
        }
        button("ذخیره متن در تاریخچه") { saveHistory() }
        button("نمایش تاریخچه") { dialog.dismiss(); showHistory() }
        button("اشتراک‌گذاری متن") { shareText() }
        button("خروجی فایل TXT") {
            if (currentText().isBlank()) return@button toast("متنی برای خروجی نیست")
            dialog.dismiss()
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "avanegar-${System.currentTimeMillis()}.txt")
            }, REQUEST_EXPORT)
        }
        button("پاک‌کردن متن و شروع جدید") {
            if (busy()) return@button toast("ابتدا ضبط را متوقف کنید")
            AlertDialog.Builder(this).setMessage("متن داخل کادر پاک شود؟")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("پاک کن") { _, _ ->
                    transcript.text = ""
                    persistText()
                    status.text = "برای شروع، دکمه ضبط را بزنید"
                    toast("متن پاک شد")
                    dialog.dismiss()
                }.show()
        }
        panel.addView(TextView(this).apply {
            text = "فقط فارسی · اتصال مستقیم به OpenAI · برای استفاده شخصی\nکلید در حافظه رمزنگاری‌شده گوشی نگهداری می‌شود."
            gravity = Gravity.CENTER
            textSize = 12f
            setPadding(0, dp(16), 0, 0)
        })
        dialog = AlertDialog.Builder(this)
            .setTitle("تنظیمات آوانگار ⚙")
            .setView(scroller)
            .setNegativeButton("بستن", null)
            .create()
        dialog.show()
    }

    private fun keyStatus(): String = try {
        if (secrets.load().isNullOrBlank()) "کلیدی ذخیره نشده است" else "✓ کلید به‌صورت رمزگذاری‌شده ذخیره شده است"
    } catch (_: Exception) { "کلید ذخیره‌شده قابل خواندن نیست؛ آن را حذف و دوباره وارد کنید" }

    private fun saveKey(input: String): Boolean {
        val key = input.trim()
        if (!key.startsWith("sk-") || key.length < 15) {
            toast("کلید معتبر نیست؛ باید با sk- شروع شود")
            return false
        }
        return try {
            secrets.save(key)
            toast("کلید ذخیره شد ✓")
            true
        } catch (_: Exception) {
            toast("ذخیره کلید ممکن نشد")
            false
        }
    }

    private fun savedKey(): String? {
        val key = try { secrets.load() } catch (_: Exception) { null }
        if (key.isNullOrBlank()) {
            toast("ابتدا کلید OpenAI را از تنظیمات ذخیره کنید")
            return null
        }
        return key
    }

    private fun testConnection(key: String, label: TextView) {
        val request = Request.Builder()
            .url("https://api.openai.com/v1/models/gpt-live-transcribe")
            .header("Authorization", "Bearer $key")
            .get().build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                ui.post { label.text = "خطای شبکه: ${e.localizedMessage.orEmpty()}" }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val error = if (it.isSuccessful) "" else apiError(it.body?.string().orEmpty(), it.code)
                    ui.post {
                        label.text = if (error.isEmpty()) {
                            "✓ کلید به مدل رونویسی دسترسی دارد (اعتبار مصرفی هنگام ضبط بررسی می‌شود)"
                        } else "خطا: $error"
                    }
                }
            }
        })
    }

    private fun startRecordingWithPermission() {
        if (busy() || savedKey() == null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD)
        } else connectLive()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == REQUEST_RECORD) {
            if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) connectLive()
            else toast("برای ضبط اجازه دسترسی به میکروفون لازم است")
        }
    }

    private fun connectLive() {
        if (busy()) return
        val key = savedKey() ?: return
        connecting = true
        stopping = false
        micFinished = false
        pending.set(0)
        paragraphs.clear()
        partials.clear()
        finalizedIds.clear()
        recordingBase = currentText().let { if (it.isNotBlank()) "$it\n" else "" }
        status.text = "در حال اتصال به OpenAI…"
        recordButton.text = "لغو اتصال"
        val id = ++generation
        // GA transcription endpoint: no ?model= parameter, no server-side VAD.
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $key")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val input = JSONObject()
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE))
                    .put("transcription", JSONObject()
                        .put("model", "gpt-live-transcribe")
                        .put("languages", JSONArray().put("fa"))
                        .put("delay", "low")
                        .put("prompt", "گفتار فارسی؛ پاسخ را با خط فارسی بنویس."))
                    .put("turn_detection", JSONObject.NULL)
                val update = JSONObject().put("type", "session.update")
                    .put("session", JSONObject().put("type", "transcription")
                        .put("audio", JSONObject().put("input", input)))
                webSocket.send(update.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = try { JSONObject(text) } catch (_: Exception) { return }
                ui.post { if (id == generation) onLiveEvent(event, id) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val description = try {
                    response?.body?.string()?.let { apiError(it, response.code) }
                } catch (_: Exception) { null }
                val reason = description ?: t.localizedMessage ?: "اتصال اینترنت قطع شد"
                ui.post { if (id == generation) endSession("اتصال قطع شد: $reason") }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ui.post { if (id == generation) endSession("اتصال بسته شد ($code)") }
            }
        })
    }

    private fun onLiveEvent(event: JSONObject, id: Int) {
        when (event.optString("type")) {
            "session.updated" -> {
                if (connecting) {
                    connecting = false
                    beginMicrophone(id)
                }
            }
            "conversation.item.input_audio_transcription.delta" -> {
                val itemId = event.optString("item_id", "unknown")
                if (!finalizedIds.contains(itemId)) {
                    partials[itemId] = partials.getOrDefault(itemId, "") + event.optString("delta")
                    renderLive()
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val itemId = event.optString("item_id", "unknown")
                if (finalizedIds.add(itemId)) {
                    val text = event.optString("transcript", partials[itemId].orEmpty()).trim()
                    partials.remove(itemId)
                    if (text.isNotBlank()) paragraphs.append(text).append('\n')
                    renderLive()
                    pending.updateAndGet { max(0, it - 1) }
                }
                finishIfComplete()
            }
            "conversation.item.input_audio_transcription.failed" -> {
                partials.remove(event.optString("item_id"))
                pending.updateAndGet { max(0, it - 1) }
                status.text = "یک بخش از گفتار قابل تشخیص نبود"
                renderLive()
                finishIfComplete()
            }
            "error" -> {
                val message = event.optJSONObject("error")?.optString("message")
                    ?: "خطای نامشخص"
                endSession("خطای OpenAI: $message")
            }
        }
    }

    private fun beginMicrophone(id: Int) {
        val minSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minSize <= 0) return endSession("میکروفون از نمونه‌برداری ۲۴ کیلوهرتز پشتیبانی نمی‌کند")
        val device = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minSize, FRAME_BYTES * 4))
        } catch (_: Exception) {
            return endSession("امکان دسترسی به میکروفون وجود ندارد")
        }
        if (device.state != AudioRecord.STATE_INITIALIZED) {
            device.release()
            return endSession("میکروفون قابل استفاده نیست")
        }
        try { device.startRecording() } catch (_: Exception) {
            device.release()
            return endSession("ضبط شروع نشد؛ مجوز میکروفون را بررسی کنید")
        }
        mic = device
        recording = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        recordButton.text = "■  توقف ضبط"
        status.text = "● در حال شنیدن و نوشتن فارسی…"

        micThread = Thread {
            val chunk = ByteArray(FRAME_BYTES)
            var segmentMs = 0
            try {
                while (recording && id == generation) {
                    val count = device.read(chunk, 0, chunk.size)
                    if (count < 0) throw IOException("خطای میکروفون ($count)")
                    if (count == 0) continue
                    val audio = Base64.encodeToString(chunk, 0, count, Base64.NO_WRAP)
                    if (socket?.send(JSONObject().put("type", "input_audio_buffer.append")
                            .put("audio", audio).toString()) != true) {
                        throw IOException("اتصال انتقال صدا برقرار نیست")
                    }
                    if ((socket?.queueSize() ?: 0) > 3_000_000) throw IOException("اینترنت برای ارسال صدا کند است")
                    segmentMs += count * 1000 / (SAMPLE_RATE * 2)
                    // Do not require a high microphone volume threshold. The prior version
                    // could record silently forever without committing any audio segment.
                    if (segmentMs >= COMMIT_EVERY_MS) {
                        commitAudio()
                        segmentMs = 0
                    }
                }
                if (id == generation && segmentMs >= 300) commitAudio()
            } catch (e: Exception) {
                ui.post { if (id == generation) endSession("ضبط قطع شد: ${e.localizedMessage.orEmpty()}") }
            } finally {
                try { device.stop() } catch (_: Exception) { }
                device.release()
                ui.post {
                    if (mic === device) mic = null
                    if (id == generation) {
                        micFinished = true
                        finishIfComplete()
                        ui.postDelayed({
                            if (id == generation && stopping) {
                                endSession("پایان ضبط (مهلت دریافت پاسخ تمام شد)")
                            }
                        }, 18_000)
                    }
                }
            }
        }.apply { name = "Avanegar-Live-Audio"; start() }
    }

    private fun commitAudio() {
        // Called from the microphone thread, FIFO in the same OkHttp WebSocket queue.
        if (socket?.send("{\"type\":\"input_audio_buffer.commit\"}") == true) {
            pending.incrementAndGet()
        }
    }

    private fun stopRecording() {
        if (connecting) return endSession("اتصال لغو شد")
        if (!recording) return
        stopping = true
        recording = false
        recordButton.isEnabled = false
        status.text = "در حال تکمیل آخرین جمله…"
    }

    private fun finishIfComplete() {
        if (stopping && micFinished && pending.get() == 0) {
            endSession("✓ متن آماده است؛ برای کپی روی کادر بزنید")
        }
    }

    private fun endSession(message: String) {
        if (connecting || recording || stopping) renderLive()
        recording = false
        connecting = false
        stopping = false
        ++generation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        socket?.close(1000, "finished")
        socket = null
        recordButton.text = "🎙  شروع ضبط آنلاین"
        recordButton.isEnabled = !uploading
        status.text = message
        persistText()
        paragraphs.clear()
        partials.clear()
        finalizedIds.clear()
    }

    private fun apiError(body: String, code: Int): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) { "" }
        return "HTTP $code${if (detail.isNotBlank()) " - $detail" else ""}"
    }

    @Deprecated("Android system document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_FILE -> data?.data?.let { uploadAudio(it) }
            REQUEST_EXPORT -> data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(currentText())
                    } ?: throw IOException("فایل برای نوشتن باز نشد")
                    toast("فایل متنی ذخیره شد")
                } catch (e: Exception) { toast("خطا: ${e.localizedMessage.orEmpty()}") }
            }
        }
    }

    private fun uploadAudio(uri: Uri) {
        val key = savedKey() ?: return
        if (busy()) return
        uploading = true
        recordButton.isEnabled = false
        status.text = "در حال تبدیل فایل صوتی…"
        Thread {
            var temp: File? = null
            try {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext !in setOf("mp3", "mp4", "mpeg", "mpga", "m4a", "wav", "webm", "ogg", "flac")) {
                    throw IOException("فایل MP3، WAV یا M4A انتخاب کنید")
                }
                val file = File.createTempFile("avanegar_", ".$ext", cacheDir)
                temp = file
                contentResolver.openInputStream(uri)?.use { source ->
                    file.outputStream().use { destination ->
                        val buffer = ByteArray(16_384)
                        var bytes = 0L
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            bytes += n
                            if (bytes > 24L * 1024 * 1024) throw IOException("حجم فایل بیش از ۲۴ مگابایت است")
                            destination.write(buffer, 0, n)
                        }
                    }
                } ?: throw IOException("فایل باز نشد")
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", "gpt-transcribe")
                    .addFormDataPart("languages[]", "fa")
                    .addFormDataPart("prompt", "گفتار فارسی به خط فارسی.")
                    .addFormDataPart("file", "audio.$ext", file.asRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val request = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                    .header("Authorization", "Bearer $key")
                    .post(form).build()
                http.newBuilder().callTimeout(180, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS)
                    .build().newCall(request).execute().use { result ->
                        val body = result.body?.string().orEmpty()
                        if (!result.isSuccessful) throw IOException(apiError(body, result.code))
                        val text = JSONObject(body).optString("text")
                        if (text.isBlank()) throw IOException("سرویس متن خالی بازگرداند")
                        ui.post {
                            val before = currentText()
                            transcript.text = (if (before.isBlank()) text else "$before\n$text")
                            transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
                            persistText()
                            status.text = "✓ فایل تبدیل شد؛ کادر را لمس کنید تا کپی شود"
                        }
                    }
            } catch (e: Exception) {
                ui.post { status.text = "خطای تبدیل فایل: ${e.localizedMessage.orEmpty()}" }
            } finally {
                temp?.delete()
                ui.post { uploading = false; recordButton.isEnabled = true }
            }
        }.apply { name = "Avanegar-File-Upload"; start() }
    }

    private fun saveHistory() {
        val text = currentText()
        if (text.isBlank()) return toast("متنی برای ذخیره نیست")
        val entries = try { JSONArray(prefs.getString(HISTORY_KEY, "[]")) } catch (_: Exception) { JSONArray() }
        val stamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale("fa")).format(Date())
        val updated = JSONArray().put(JSONObject().put("date", stamp).put("text", text))
        for (i in 0 until min(49, entries.length())) updated.put(entries.getJSONObject(i))
        prefs.edit().putString(HISTORY_KEY, updated.toString()).apply()
        toast("در تاریخچه ذخیره شد ✓")
    }

    private fun showHistory() {
        if (busy()) return toast("ابتدا عملیات فعلی را تمام کنید")
        val entries = try { JSONArray(prefs.getString(HISTORY_KEY, "[]")) } catch (_: Exception) { JSONArray() }
        if (entries.length() == 0) return toast("تاریخچه خالی است")
        val names = Array(entries.length()) { i ->
            entries.getJSONObject(i).let { item ->
                "${item.optString("date")} · ${item.optString("text").replace('\n', ' ').take(50)}"
            }
        }
        AlertDialog.Builder(this).setTitle("تاریخچه گفتارهای فارسی")
            .setItems(names) { _, which ->
                transcript.text = entries.getJSONObject(which).optString("text")
                persistText()
                toast("متن بازیابی شد؛ روی کادر بزنید تا کپی شود")
            }
            .setNeutralButton("حذف تاریخچه") { _, _ ->
                AlertDialog.Builder(this).setMessage("تمام متن‌های ذخیره‌شده حذف شوند؟")
                    .setNegativeButton("انصراف", null)
                    .setPositiveButton("حذف") { _, _ ->
                        prefs.edit().remove(HISTORY_KEY).apply()
                        toast("تاریخچه پاک شد")
                    }.show()
            }
            .setNegativeButton("بستن", null).show()
    }

    private fun shareText() {
        val text = currentText()
        if (text.isBlank()) return toast("متنی برای اشتراک نیست")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }, "اشتراک متن فارسی"))
    }

    override fun onStop() {
        super.onStop()
        persistText()
        if (recording || connecting) stopRecording()
    }

    override fun onDestroy() {
        recording = false
        ++generation
        socket?.close(1000, "activity destroyed")
        socket = null
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}

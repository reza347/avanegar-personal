package com.avanegar.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * آوانگار نسخه ۰.۴: ظاهر خلوت فارسی، رونویسی زنده، مکث/ادامه، ذخیره متن و WAV.
 * بدون سرور واسطه؛ کلید فقط در Keystore دستگاه نگهداری می‌شود.
 */
class MainActivity : Activity(), LiveSession.Listener {
    companion object {
        private const val PERMISSION_MIC = 401
        private const val PICK_AUDIO = 402
        private const val EXPORT_TEXT = 403
        private const val DRAFT_KEY = "draft_text"
    }
    private enum class Stage { IDLE, CONNECTING, RECORDING, PAUSED, SAVING }
    private data class Piece(var partial: String = "", var completed: String = "", var edited: String? = null)

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("avanegar_prefs_v4", MODE_PRIVATE) }
    private val secrets by lazy { SecureApiKeyStore(this) }
    private val archive by lazy { RecordingStore(this) }
    private val http = OkHttpClient.Builder().connectTimeout(18, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).build()

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var scroll: ScrollView
    private lateinit var action: TextView
    private lateinit var clock: TextView
    private lateinit var glyph: ImageView
    private lateinit var recordControl: LinearLayout
    private lateinit var saveButton: ImageButton

    private var stage = Stage.IDLE
    private var live: LiveSession? = null
    private var editor: PersianEditor? = null
    private var rawAudio: File? = null
    private val pieces = LinkedHashMap<String, Piece>()
    private var baseText = ""
    private var generation = 0
    private var correctionsPending = 0
    private var waitingSave = false
    private var liveFinished = false
    private var fileBusy = false
    private var savedJustNow = false
    private var player: MediaPlayer? = null
    private var startedAt = 0L
    private var accumulated = 0L
    private var lastDraftSave = 0L
    private val clockTicker = object : Runnable {
        override fun run() {
            updateClock()
            ui.postDelayed(this, 400)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = Color.rgb(21, 21, 23)
        window.navigationBarColor = Color.rgb(21, 21, 23)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        transcript = findViewById(R.id.transcript)
        scroll = findViewById(R.id.transcriptScroll)
        action = findViewById(R.id.recordLabel)
        clock = findViewById(R.id.timer)
        glyph = findViewById(R.id.recordGlyph)
        recordControl = findViewById(R.id.recordControl)
        saveButton = findViewById(R.id.saveButton)
        baseText = prefs.getString(DRAFT_KEY, "").orEmpty()

        transcript.setOnClickListener { copyAll() }
        findViewById<ImageButton>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<ImageButton>(R.id.deleteButton).setOnClickListener { confirmClear() }
        saveButton.setOnClickListener { requestSave() }
        recordControl.setOnClickListener { onRecordTapped() }
        findViewById<TextView>(R.id.historyTab).setOnClickListener { showHistory() }

        render()
        updateControls()
        ui.post(clockTicker)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    private fun key(): String? = try { secrets.load() } catch (_: Exception) { null }
    private fun persianDigits(value: String): String = buildString {
        value.forEach { c ->
            append(if (c in '0'..'9') ('۰'.code + c.code - '0'.code).toChar() else c)
        }
    }

    private fun updateClock() {
        val millis = accumulated +
            (if (stage == Stage.RECORDING) SystemClock.elapsedRealtime() - startedAt else 0L)
        val seconds = (millis / 1000).coerceAtLeast(0)
        clock.text = persianDigits(String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60))
    }

    private fun stopClock() {
        if (stage == Stage.RECORDING) {
            accumulated += (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0)
        }
    }

    private fun updateControls() {
        val busy = stage == Stage.SAVING || fileBusy
        when (stage) {
            Stage.IDLE -> {
                action.text = "شروع ضبط"
                glyph.setImageResource(R.drawable.ic_mic)
            }
            Stage.CONNECTING -> {
                action.text = "در حال اتصال"
                glyph.setImageResource(R.drawable.ic_mic)
            }
            Stage.RECORDING -> {
                action.text = "مکث"
                glyph.setImageResource(R.drawable.ic_pause_round)
            }
            Stage.PAUSED -> {
                action.text = "ادامه ضبط"
                glyph.setImageResource(R.drawable.ic_play_round)
            }
            Stage.SAVING -> {
                action.text = "در حال ذخیره"
                glyph.setImageResource(R.drawable.ic_checkmark)
            }
        }
        recordControl.alpha = if (busy) 0.55f else 1f
        saveButton.alpha = if (busy) 0.55f else 1f
        recordControl.isEnabled = !busy
        saveButton.isEnabled = !busy
        window.run {
            if (stage == Stage.RECORDING || stage == Stage.CONNECTING || stage == Stage.SAVING) {
                addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        updateClock()
    }

    private fun allText(): String = buildString {
        if (baseText.isNotBlank()) append(baseText.trim())
        for (part in pieces.values) {
            val text = (part.edited ?: part.completed.ifBlank { part.partial }).trim()
            if (text.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append(text)
            }
        }
    }.trim()

    private fun render() {
        val full = allText()
        transcript.text = if (full.isBlank()) "گفتار فارسی اینجا نوشته می‌شود…" else full
        transcript.setTextColor(if (full.isBlank()) Color.rgb(143, 147, 157) else Color.rgb(244, 244, 246))
        if (System.currentTimeMillis() - lastDraftSave > 1200) {
            lastDraftSave = System.currentTimeMillis()
            saveDraft()
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun saveDraft() = prefs.edit().putString(DRAFT_KEY, allText()).apply()

    private fun copyAll() {
        val text = allText()
        if (text.isBlank()) return toast("متنی برای کپی وجود ندارد")
        val manager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("متن آوانگار", text))
        toast("تمام متن کپی شد ✓")
    }

    private fun onRecordTapped() {
        if (fileBusy) return toast("تبدیل فایل در حال انجام است")
        when (stage) {
            Stage.IDLE -> {
                if (rawAudio?.exists() == true) {
                    AlertDialog.Builder(this)
                        .setMessage("صدای ضبط قبلی هنوز ذخیره نشده است. ضبط جدید جایگزین آن شود؟")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("ضبط جدید") { _, _ ->
                            rawAudio?.delete()
                            rawAudio = null
                            startWithPermission()
                        }.show()
                } else startWithPermission()
            }
            Stage.CONNECTING -> {
                live?.cancel()
                live = null
                stage = Stage.IDLE
                status.text = "اتصال لغو شد"
                updateControls()
            }
            Stage.RECORDING -> {
                stopClock()
                stage = Stage.PAUSED
                live?.pause()
                status.text = "ضبط مکث شد؛ برای ادامه دکمه وسط را بزنید"
                updateControls()
            }
            Stage.PAUSED -> {
                stage = Stage.CONNECTING
                status.text = "در حال ادامه ضبط…"
                live?.resume()
                updateControls()
            }
            Stage.SAVING -> Unit
        }
    }

    private fun startWithPermission() {
        if (key().isNullOrBlank()) return toast("ابتدا کلید OpenAI را در تنظیمات وارد کنید")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_MIC)
        } else startLive()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == PERMISSION_MIC) {
            if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) startLive()
            else toast("اجازه دسترسی به میکروفون لازم است")
        }
    }

    private fun startLive() {
        val secret = key() ?: return
        if (stage != Stage.IDLE) return
        if (savedJustNow) {
            baseText = ""
            pieces.clear()
            savedJustNow = false
        } else baseText = allText()
        pieces.clear()
        accumulated = 0
        startedAt = 0
        generation++
        correctionsPending = 0
        waitingSave = false
        liveFinished = false
        editor = PersianEditor(secret)
        stage = Stage.CONNECTING
        status.text = "در حال اتصال به رونویسی زنده…"
        render()
        updateControls()
        try {
            live = LiveSession(this, secret, this).also { it.open() }
        } catch (_: Exception) {
            stage = Stage.IDLE
            status.text = "ساخت فایل ضبط ممکن نشد؛ حافظه گوشی را بررسی کنید"
            updateControls()
        }
    }

    override fun onReady() {
        if (stage == Stage.CONNECTING) status.text = "در حال راه‌اندازی میکروفون…"
    }

    override fun onMicrophoneStarted() {
        if (stage != Stage.CONNECTING && stage != Stage.PAUSED) return
        startedAt = SystemClock.elapsedRealtime()
        stage = Stage.RECORDING
        status.text = "● در حال شنیدن و نوشتن فارسی"
        updateControls()
    }

    override fun onMicrophonePaused() {
        if (stage == Stage.PAUSED) status.text = "مکث شد؛ برای ادامه دکمه وسط را بزنید"
    }

    override fun onCommitted(itemId: String) {
        if (stage != Stage.IDLE) pieces.getOrPut(itemId) { Piece() }
    }

    override fun onDelta(itemId: String, value: String) {
        if (stage == Stage.IDLE) return
        val part = pieces.getOrPut(itemId) { Piece() }
        if (part.completed.isBlank()) {
            part.partial += value
            render()
        }
    }

    override fun onFinal(itemId: String, value: String) {
        if (stage == Stage.IDLE) return
        val part = pieces.getOrPut(itemId) { Piece() }
        val normalized = editor?.normalize(value).orEmpty().ifBlank {
            editor?.normalize(part.partial).orEmpty()
        }
        part.completed = normalized
        part.partial = ""
        render()
        if (normalized.length > 5 && prefs.getBoolean("correction", true)) {
            val revision = generation
            correctionsPending++
            editor?.correct(normalized) { corrected ->
                if (revision != generation) return@correct
                if (part.completed == normalized) part.edited = corrected
                correctionsPending = (correctionsPending - 1).coerceAtLeast(0)
                render()
                maybeSave()
            }
        }
        maybeSave()
    }

    override fun onProblem(text: String) {
        status.text = text
    }

    override fun onFinished(pcmFile: File, problem: String?) {
        rawAudio = pcmFile
        live = null
        liveFinished = true
        if (waitingSave) {
            status.text = "در حال تکمیل متن و اصلاح املا…"
            maybeSave()
        } else {
            stopClock()
            stage = Stage.IDLE
            status.text = problem ?: "ضبط پایان یافت؛ تیک را برای ذخیره بزنید"
            updateControls()
            saveDraft()
        }
    }

    private fun requestSave() {
        if (stage == Stage.SAVING || fileBusy) return
        if (allText().isBlank() && rawAudio == null && live == null) {
            return toast("متن یا صدایی برای ذخیره وجود ندارد")
        }
        stopClock()
        waitingSave = true
        stage = Stage.SAVING
        status.text = "در حال نهایی‌کردن ضبط و متن…"
        updateControls()
        if (live != null) {
            liveFinished = false
            live?.finish()
        } else liveFinished = true
        maybeSave()
        val revision = generation
        ui.postDelayed({
            if (revision == generation && waitingSave && liveFinished) persistRecording()
        }, 9000)
    }

    private fun maybeSave() {
        if (waitingSave && liveFinished && correctionsPending == 0) persistRecording()
    }

    private fun persistRecording() {
        if (!waitingSave || !liveFinished) return
        waitingSave = false
        generation++ // پاسخ اصلاح املایی دیررس دیگر متن ذخیره‌شده را تغییر نمی‌دهد.
        val content = allText()
        val pcm = rawAudio
        rawAudio = null
        status.text = "در حال ذخیره متن و صدا روی گوشی…"
        Thread {
            try {
                archive.save(content, pcm)
                ui.post {
                    savedJustNow = true
                    stage = Stage.IDLE
                    status.text = "✓ متن و صدای ضبط‌شده ذخیره شدند"
                    saveDraft()
                    updateControls()
                    toast("با موفقیت ذخیره شد ✓")
                }
            } catch (_: Exception) {
                ui.post {
                    rawAudio = pcm?.takeIf { it.exists() }
                    stage = Stage.IDLE
                    status.text = "ذخیره انجام نشد؛ فضای خالی گوشی را بررسی کنید"
                    updateControls()
                }
            }
        }.apply { name = "Avanegar-Save"; start() }
    }

    private fun confirmClear() {
        if (fileBusy || stage == Stage.SAVING) return toast("ابتدا عملیات فعلی تمام شود")
        AlertDialog.Builder(this)
            .setTitle("حذف متن")
            .setMessage("تمام نوشته‌های داخل کادر و ضبط ذخیره‌نشده پاک شوند؟ تاریخچه محفوظ می‌ماند.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("حذف") { _, _ ->
                generation++
                live?.cancel()
                live = null
                rawAudio?.delete()
                rawAudio = null
                baseText = ""
                pieces.clear()
                correctionsPending = 0
                waitingSave = false
                liveFinished = false
                savedJustNow = false
                stage = Stage.IDLE
                accumulated = 0
                render()
                saveDraft()
                updateControls()
                status.text = "کادر پاک شد"
            }.show()
    }

    private fun showHistory() {
        if (stage != Stage.IDLE || fileBusy) return toast("ابتدا ضبط را تمام کنید")
        val entries = archive.list()
        if (entries.isEmpty()) return toast("هنوز متنی ذخیره نشده است")
        AlertDialog.Builder(this).setTitle("تاریخچه ضبط‌ها")
            .setItems(entries.map { archive.label(it) }.toTypedArray()) { _, index ->
                val chosen = entries[index]
                AlertDialog.Builder(this).setTitle("ضبط ذخیره‌شده")
                    .setItems(arrayOf("بازکردن متن", "پخش فایل صوتی", "اشتراک متن", "حذف از تاریخچه")) { _, item ->
                        when (item) {
                            0 -> {
                                baseText = chosen.text
                                pieces.clear()
                                rawAudio?.delete()
                                rawAudio = null
                                savedJustNow = false
                                render()
                                saveDraft()
                                status.text = "متن بازیابی شد؛ با لمس کادر کپی می‌شود"
                            }
                            1 -> playAudio(chosen)
                            2 -> shareText(chosen.text)
                            3 -> AlertDialog.Builder(this)
                                .setMessage("این ضبط و فایل صوتی آن از تاریخچه حذف شوند؟")
                                .setNegativeButton("انصراف", null)
                                .setPositiveButton("حذف") { _, _ ->
                                    archive.delete(chosen)
                                    toast("ضبط از تاریخچه حذف شد")
                                }.show()
                        }
                    }.show()
            }.setNegativeButton("بستن", null).show()
    }

    private fun playAudio(item: RecordingStore.Entry) {
        val file = archive.audioOf(item)
        if (file == null) return toast("برای این متن فایل صوتی ذخیره نشده است")
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    it.release()
                    if (player === it) player = null
                }
                start()
            }
            toast("در حال پخش فایل صوتی")
        } catch (_: Exception) {
            toast("پخش صدا ممکن نشد")
        }
    }

    private fun shareText(value: String = allText()) {
        if (value.isBlank()) return toast("متنی برای اشتراک وجود ندارد")
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, value)
        }, "اشتراک متن فارسی"))
    }

    private fun showSettings() {
        if (stage == Stage.SAVING) return toast("در حال ذخیره است")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17), dp(8), dp(17), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroller = ScrollView(this)
        scroller.addView(container)
        val title = TextView(this).apply {
            text = "کلید OpenAI"
            textSize = 17f
            setPadding(0, dp(6), 0, dp(8))
        }
        container.addView(title)
        val input = EditText(this).apply {
            hint = "کلید را پیست کنید (sk-…)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }
        container.addView(input)
        val info = TextView(this).apply {
            text = if (key().isNullOrBlank()) "هنوز کلیدی ذخیره نشده" else "✓ کلید به‌صورت رمزگذاری‌شده ذخیره شده است"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        }
        container.addView(info)
        fun button(label: String, callback: () -> Unit) {
            container.addView(Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 14f
                setOnClickListener { callback() }
            }, LinearLayout.LayoutParams(-1, dp(49)))
        }
        fun saveEntered(): Boolean {
            val candidate = input.text.toString().trim()
            if (!candidate.startsWith("sk-") || candidate.length < 15) {
                toast("کلید باید با sk- شروع شود")
                return false
            }
            return try {
                secrets.save(candidate)
                input.text.clear()
                info.text = "✓ کلید رمزگذاری و ذخیره شد"
                toast("کلید ذخیره شد")
                true
            } catch (_: Exception) {
                toast("کلید ذخیره نشد")
                false
            }
        }
        button("ذخیره کلید API") { saveEntered() }
        button("آزمایش اتصال") {
            if (input.text.isNotBlank() && !saveEntered()) return@button
            val secret = key()
            if (secret.isNullOrBlank()) toast("ابتدا کلید را ذخیره کنید")
            else {
                info.text = "در حال آزمایش اتصال…"
                testConnection(secret, info)
            }
        }
        val correction = Switch(this).apply {
            text = "اصلاح خودکار املا و نشانه‌گذاری"
            isChecked = prefs.getBoolean("correction", true)
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("correction", checked).apply()
            }
        }
        container.addView(correction)
        container.addView(TextView(this).apply {
            text = "اصلاح متن در پس‌زمینه انجام می‌شود تا نمایش گفتار کند نشود. این قابلیت مصرف جداگانه اعتبار OpenAI دارد."
            textSize = 12f
            setPadding(0, 0, 0, dp(12))
        })
        lateinit var dialog: AlertDialog
        button("تبدیل فایل صوتی به متن") {
            if (stage != Stage.IDLE || fileBusy) return@button toast("ابتدا ضبط را تمام کنید")
            if (key().isNullOrBlank()) return@button toast("اول کلید OpenAI را ذخیره کنید")
            dialog.dismiss()
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }, PICK_AUDIO)
        }
        button("تاریخچه ضبط‌ها") { dialog.dismiss(); showHistory() }
        button("اشتراک متن داخل کادر") { shareText() }
        button("ذخیره متن به فایل TXT") {
            if (allText().isBlank()) return@button toast("هنوز متنی وجود ندارد")
            dialog.dismiss()
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "avanegar-" + System.currentTimeMillis() + ".txt")
            }, EXPORT_TEXT)
        }
        button("حذف کلید ذخیره‌شده") {
            AlertDialog.Builder(this)
                .setMessage("کلید API از حافظه امن این گوشی پاک شود؟")
                .setNegativeButton("انصراف", null)
                .setPositiveButton("حذف") { _, _ ->
                    secrets.clear()
                    info.text = "کلید حذف شد"
                    toast("کلید پاک شد")
                }.show()
        }
        container.addView(TextView(this).apply {
            text = "فقط فارسی • مخصوص استفاده شخصی\nهیچ کلیدی در GitHub ذخیره نمی‌شود."
            gravity = Gravity.CENTER
            textSize = 12f
            setPadding(0, dp(15), 0, dp(9))
        })
        dialog = AlertDialog.Builder(this).setTitle("تنظیمات آوانگار")
            .setView(scroller).setNegativeButton("بستن", null).create()
        dialog.show()
    }

    private fun testConnection(secret: String, result: TextView) {
        val req = Request.Builder().url("https://api.openai.com/v1/models/gpt-live-transcribe")
            .header("Authorization", "Bearer " + secret).get().build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                ui.post { result.text = "اتصال اینترنت برقرار نشد" }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    ui.post {
                        result.text = when (it.code) {
                            200 -> "✓ کلید شناسایی شد؛ دسترسی ضبط هنگام شروع بررسی می‌شود"
                            401 -> "کلید نامعتبر است"
                            403 -> "حساب به این مدل دسترسی ندارد"
                            429 -> "محدودیت درخواست یا اعتبار فعال است"
                            else -> "ارتباط برقرار نشد (کد " + persianDigits(it.code.toString()) + ")"
                        }
                    }
                }
            }
        })
    }

    @Deprecated("انتخاب فایل توسط سیستم اندروید")
    override fun onActivityResult(code: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(code, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (code) {
            PICK_AUDIO -> data?.data?.let { transcribeFile(it) }
            EXPORT_TEXT -> data?.data?.let { uri ->
                try {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                        it.write(allText())
                    }
                    toast("فایل متنی ذخیره شد")
                } catch (_: Exception) { toast("ذخیره فایل متنی ممکن نشد") }
            }
        }
    }

    private fun transcribeFile(uri: Uri) {
        if (stage != Stage.IDLE || fileBusy) return
        val secret = key() ?: return toast("کلید OpenAI را ذخیره کنید")
        fileBusy = true
        updateControls()
        status.text = "در حال تبدیل فایل صوتی…"
        Thread {
            var temp: File? = null
            try {
                val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                    null, null, null)
                val name = cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext !in setOf("mp3", "mp4", "mpeg", "mpga", "m4a", "wav", "webm")) {
                    throw IOException("فرمت فایل پشتیبانی نمی‌شود")
                }
                val destination = File.createTempFile("voice_", "." + ext, cacheDir)
                temp = destination
                contentResolver.openInputStream(uri)?.use { source ->
                    destination.outputStream().use { output ->
                        val buffer = ByteArray(16384)
                        var total = 0L
                        while (true) {
                            val n = source.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > 24L * 1024 * 1024) throw IOException("فایل بیش از ۲۴ مگابایت است")
                            output.write(buffer, 0, n)
                        }
                    }
                } ?: throw IOException("فایل باز نشد")
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", "gpt-transcribe")
                    .addFormDataPart("languages[]", "fa")
                    .addFormDataPart("prompt", "گفتار فارسی را با خط فارسی و نشانه‌گذاری مناسب بنویس.")
                    .addFormDataPart("file", "audio." + ext,
                        destination.asRequestBody("application/octet-stream".toMediaType()))
                    .build()
                val req = Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                    .header("Authorization", "Bearer " + secret).post(form).build()
                http.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("کد " + response.code)
                    val value = JSONObject(response.body?.string().orEmpty()).optString("text")
                    if (value.isBlank()) throw IOException("متنی تشخیص داده نشد")
                    ui.post {
                        baseText = listOf(allText(), value).filter { it.isNotBlank() }.joinToString("\n")
                        pieces.clear()
                        render()
                        saveDraft()
                        status.text = "✓ فایل تبدیل شد؛ برای کپی روی کادر بزنید"
                    }
                }
            } catch (_: Exception) {
                ui.post { status.text = "تبدیل فایل انجام نشد؛ فرمت، اینترنت و اعتبار را بررسی کنید" }
            } finally {
                temp?.delete()
                ui.post { fileBusy = false; updateControls() }
            }
        }.apply { name = "Avanegar-File-Transcription"; start() }
    }

    override fun onStop() {
        super.onStop()
        saveDraft()
        if (stage == Stage.RECORDING) {
            stopClock()
            stage = Stage.PAUSED
            live?.pause()
            status.text = "ضبط هنگام خروج از برنامه مکث شد"
            updateControls()
        } else if (stage == Stage.CONNECTING) {
            live?.cancel()
            live = null
            stage = Stage.IDLE
            status.text = "اتصال هنگام خروج از برنامه لغو شد"
            updateControls()
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(clockTicker)
        saveDraft()
        live?.cancel()
        player?.release()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }
}

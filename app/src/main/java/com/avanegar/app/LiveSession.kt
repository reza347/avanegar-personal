package com.avanegar.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * اتصال رونویسی زنده. نمونه‌های PCM16 تک‌کاناله، ۲۴ کیلوهرتز.
 * دریافت دلتاها مستقل از اصلاح املاست تا هیچ درخواست متنی، ضبط را کند نکند.
 */
class LiveSession(
    context: Context,
    private val apiKey: String,
    private val listener: Listener
) {
    interface Listener {
        fun onReady()
        fun onMicrophoneStarted()
        fun onMicrophonePaused()
        fun onCommitted(itemId: String)
        fun onDelta(itemId: String, value: String)
        fun onFinal(itemId: String, value: String)
        fun onProblem(text: String)
        fun onFinished(pcmFile: File, problem: String?)
    }

    companion object {
        private const val RATE = 24000
        private const val CHUNK = 4800
        // در مدل زنده دلتا قبل از commit می‌رسد؛ commit فقط جمله را نهایی می‌کند.
        private const val COMMIT_MS = 4000
    }
    private val ui = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    val rawFile: File = File.createTempFile("avanegar_", ".pcm", context.cacheDir)
    private val output = FileOutputStream(rawFile, true)
    private val pending = AtomicInteger(0)
    private val completed = HashSet<String>()
    private var ws: WebSocket? = null
    private var micThread: Thread? = null
    @Volatile private var capturing = false
    @Volatile private var cancelFlag = false
    @Volatile private var finishFlag = false
    @Volatile private var requestedResume = false
    private var sessionReady = false
    private var delivered = false
    private var stoppedByError: String? = null

    fun open() {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer " + apiKey)
            .build()
        ws = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val transcription = JSONObject()
                    .put("model", "gpt-live-transcribe")
                    .put("languages", JSONArray().put("fa"))
                    .put("delay", "minimal")
                    .put("prompt", "گفتار فارسی را دقیق با خط فارسی، نیم‌فاصله‌های مناسب و علائم نگارشی بنویس. اسم اشخاص و مکان‌ها را بدون افزودن کلمه حفظ کن.")
                val input = JSONObject()
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", RATE))
                    .put("transcription", transcription)
                    .put("turn_detection", JSONObject.NULL)
                val update = JSONObject().put("type", "session.update")
                    .put("session", JSONObject().put("type", "transcription")
                        .put("audio", JSONObject().put("input", input)))
                if (!webSocket.send(update.toString())) {
                    ui.post { fail("امکان ارسال تنظیمات اتصال وجود ندارد.") }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val e = try { JSONObject(text) } catch (_: Exception) { return }
                ui.post {
                    if (!cancelFlag && !delivered) handleEvent(e)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ui.post {
                    if (!cancelFlag && !delivered) {
                        val code = response?.code
                        val message = when (code) {
                            401 -> "کلید OpenAI نامعتبر یا منقضی شده است."
                            403 -> "حساب OpenAI اجازه استفاده از این سرویس را ندارد."
                            429 -> "محدودیت اعتبار یا تعداد درخواست‌های OpenAI فعال شده است."
                            else -> "ارتباط زنده قطع شد. اینترنت یا دسترسی OpenAI را بررسی کنید."
                        }
                        fail(message)
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ui.post { if (!cancelFlag && !delivered && !finishFlag) fail("اتصال رونویسی بسته شد.") }
            }
        })
    }

    private fun handleEvent(e: JSONObject) {
        when (e.optString("type")) {
            "session.updated" -> {
                if (!sessionReady && !finishFlag) {
                    sessionReady = true
                    listener.onReady()
                    startMic()
                }
            }
            "input_audio_buffer.committed" -> {
                val id = e.optString("item_id")
                if (id.isNotBlank()) listener.onCommitted(id)
            }
            "conversation.item.input_audio_transcription.delta" -> {
                val id = e.optString("item_id")
                if (id.isNotBlank()) listener.onDelta(id, e.optString("delta"))
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val id = e.optString("item_id")
                if (completed.add(id)) {
                    listener.onFinal(id, e.optString("transcript"))
                    pending.updateAndGet { max(0, it - 1) }
                    maybeFinish()
                }
            }
            "conversation.item.input_audio_transcription.failed" -> {
                val id = e.optString("item_id")
                if (completed.add(id)) {
                    listener.onProblem("بخشی از صدا واضح نبود؛ متن همان قسمت ممکن است ناقص باشد.")
                    pending.updateAndGet { max(0, it - 1) }
                    maybeFinish()
                }
            }
            "error" -> {
                val error = e.optJSONObject("error")
                val code = error?.optString("code").orEmpty()
                val msg = when {
                    code.contains("rate", true) -> "تعداد درخواست‌ها زیاد شده است؛ کمی بعد دوباره امتحان کنید."
                    code.contains("quota", true) -> "اعتبار یا سهمیه OpenAI کافی نیست."
                    else -> "سرویس رونویسی خطا داد؛ اعتبار کلید و دسترسی مدل را بررسی کنید."
                }
                fail(msg)
            }
        }
    }

    private fun startMic() {
        if (cancelFlag || finishFlag || !sessionReady || delivered) return
        if (micThread?.isAlive == true) {
            requestedResume = true
            return
        }
        val minSize = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minSize <= 0) return fail("گوشی از ضبط با نرخ نمونه‌برداری لازم پشتیبانی نمی‌کند.")
        val mic = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minSize, CHUNK * 4))
        } catch (_: Exception) {
            return fail("میکروفون قابل استفاده نیست.")
        }
        if (mic.state != AudioRecord.STATE_INITIALIZED) {
            mic.release()
            return fail("میکروفون راه‌اندازی نشد.")
        }
        try { mic.startRecording() } catch (_: Exception) {
            mic.release()
            return fail("مجوز میکروفون را بررسی کنید.")
        }
        capturing = true
        requestedResume = false
        listener.onMicrophoneStarted()
        micThread = Thread {
            val chunk = ByteArray(CHUNK)
            var periodMs = 0
            try {
                while (capturing && !cancelFlag) {
                    val n = mic.read(chunk, 0, CHUNK)
                    if (n < 0) throw IOException("خطای خواندن صدا")
                    if (n == 0) continue
                    output.write(chunk, 0, n)
                    val encoded = Base64.encodeToString(chunk, 0, n, Base64.NO_WRAP)
                    val packet = JSONObject().put("type", "input_audio_buffer.append")
                        .put("audio", encoded).toString()
                    if (ws?.send(packet) != true) throw IOException("اتصال قطع شده است")
                    if ((ws?.queueSize() ?: 0L) > 4_000_000L) {
                        throw IOException("سرعت آپلود اینترنت برای گفتار زنده کافی نیست")
                    }
                    periodMs += n * 1000 / (RATE * 2)
                    if (periodMs >= COMMIT_MS) {
                        commit()
                        periodMs = 0
                    }
                    if (rawFile.length() > 250L * 1024 * 1024) {
                        throw IOException("حجم ضبط از حد مجاز عبور کرده است")
                    }
                }
                if (!cancelFlag && periodMs >= 250) commit()
            } catch (_: Exception) {
                ui.post { if (!cancelFlag && !delivered) fail("ضبط یا انتقال صدا دچار مشکل شد؛ اینترنت و میکروفون را بررسی کنید.") }
            } finally {
                try { mic.stop() } catch (_: Exception) { }
                mic.release()
                ui.post {
                    micThread = null
                    if (cancelFlag || delivered) return@post
                    if (requestedResume && !finishFlag) startMic()
                    else {
                        if (!finishFlag) listener.onMicrophonePaused()
                        maybeFinish()
                    }
                }
            }
        }.apply { name = "Avanegar-Pcm-Mic"; start() }
    }

    private fun commit() {
        if (ws?.send("{\"type\":\"input_audio_buffer.commit\"}") == true) {
            pending.incrementAndGet()
        }
    }

    fun pause() {
        if (cancelFlag || finishFlag) return
        requestedResume = false
        capturing = false
    }

    fun resume() {
        if (cancelFlag || finishFlag) return
        if (sessionReady) startMic()
    }

    fun finish() {
        if (delivered || cancelFlag) return
        finishFlag = true
        requestedResume = false
        capturing = false
        maybeFinish()
        ui.postDelayed({
            if (!delivered && !cancelFlag && finishFlag) deliver()
        }, 15000)
    }

    private fun maybeFinish() {
        if (finishFlag && micThread == null && pending.get() == 0 && !delivered) deliver()
    }

    private fun fail(message: String) {
        if (cancelFlag || delivered) return
        stoppedByError = message
        listener.onProblem(message)
        finish()
    }

    private fun deliver() {
        if (delivered || cancelFlag) return
        if (micThread?.isAlive == true) {
            ui.postDelayed({ deliver() }, 180)
            return
        }
        delivered = true
        try { output.flush(); output.close() } catch (_: Exception) { }
        try { ws?.close(1000, "done") } catch (_: Exception) { ws?.cancel() }
        http.dispatcher.executorService.shutdown()
        listener.onFinished(rawFile, stoppedByError)
    }

    fun cancel() {
        cancelFlag = true
        capturing = false
        requestedResume = false
        ws?.cancel()
        if (micThread?.isAlive == true) {
            val t = micThread
            Thread {
                try { t?.join(2500) } catch (_: InterruptedException) { }
                try { output.close() } catch (_: Exception) { }
                rawFile.delete()
            }.start()
        } else {
            try { output.close() } catch (_: Exception) { }
            rawFile.delete()
        }
        http.dispatcher.executorService.shutdown()
    }
}

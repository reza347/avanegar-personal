package com.avanegar.app

import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** اصلاح املا در درخواست غیرهم‌زمان؛ هیچ‌وقت مسیر ضبط و نمایش دلتاها را متوقف نمی‌کند. */
class PersianEditor(private val apiKey: String) {
    private val ui = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    fun normalize(text: String): String = text
        .replace('ي', 'ی')
        .replace('ك', 'ک')
        .replace(Regex("[\\t ]+"), " ")
        .replace(Regex(" +([،؛:؟.!])"), "$1")
        .trim()

    fun correct(source: String, onResult: (String) -> Unit) {
        val original = normalize(source)
        if (original.length < 6) {
            ui.post { onResult(original) }
            return
        }
        val body = JSONObject()
            .put("model", "gpt-6-luna")
            .put("store", false)
            .put("reasoning", JSONObject().put("effort", "none"))
            .put("max_output_tokens", 700)
            .put("instructions",
                "فقط متن داده‌شده را از نظر املای فارسی، نیم‌فاصله، نقطه‌گذاری و جدانویسی اصلاح کن. " +
                "کلمه جدید اضافه نکن و چیزی را حذف نکن. لحن محاوره‌ای، اعداد، اسامی خاص و معنای گفته‌شده باید حفظ شوند. " +
                "هیچ مقدمه، توضیح، عنوان یا گیومه‌ای ننویس. خروجی فقط متن اصلاح‌شده باشد.")
            .put("input", original)
        val req = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer " + apiKey)
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = finish(original)
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return finish(original)
                    val corrected = try {
                        val root = JSONObject(it.body?.string().orEmpty())
                        val out = root.optJSONArray("output")
                        val pieces = StringBuilder()
                        if (out != null) {
                            for (i in 0 until out.length()) {
                                val item = out.optJSONObject(i) ?: continue
                                if (item.optString("type") != "message") continue
                                val contents = item.optJSONArray("content") ?: continue
                                for (j in 0 until contents.length()) {
                                    val c = contents.optJSONObject(j) ?: continue
                                    if (c.optString("type") == "output_text") {
                                        pieces.append(c.optString("text"))
                                    }
                                }
                            }
                        }
                        normalize(pieces.toString())
                    } catch (_: Exception) { "" }
                    val safe = if (corrected.length in (original.length / 2)..(original.length * 2)
                        && corrected.isNotBlank()) corrected else original
                    finish(safe)
                }
            }
            private fun finish(result: String) {
                ui.post { onResult(result) }
            }
        })
    }
}

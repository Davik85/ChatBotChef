package app.web

import app.AppConfig
import app.common.Json
import app.web.dto.TgApiResp
import app.web.dto.TgApiUserMe
import app.web.dto.TgSendMessage
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

private const val TG_LIMIT = 4096
private const val TG_SAFE_CHUNK = 3500  // keep room for formatting

class TelegramApi(private val token: String) {
    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30))
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30))
        .build()

    private val mapper = Json.mapper
    private val json = "application/json; charset=utf-8".toMediaType()

    fun getMe(): Boolean {
        val url = "${AppConfig.TELEGRAM_BASE}/bot$token/getMe"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                println("GETME-HTTP-ERR: code=${resp.code} msg=${resp.message} body=$raw"); return false
            }
            val parsed: TgApiResp<TgApiUserMe> = mapper.readValue(raw)
            if (!parsed.ok) {
                println("GETME-API-ERR: code=${parsed.error_code} desc=${parsed.description}"); return false
            }
            val me = parsed.result!!
            println("GETME: ok id=${me.id} username=@${me.username ?: "unknown"}")
            return true
        }
    }

    fun sendMessage(chatId: Long, text: String): Boolean {
        var allOk = true
        for (chunk in chunks(text)) {
            val payload = mapper.writeValueAsString(TgSendMessage(chat_id = chatId, text = chunk))
            val req = Request.Builder()
                .url("${AppConfig.TELEGRAM_BASE}/bot$token/sendMessage")
                .post(payload.toRequestBody(json))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    allOk = false; println("SEND-ERR: code=${resp.code} msg=${resp.message} body=$body")
                } else {
                    println("SEND: 200 len=${chunk.length}")
                }
            }
        }
        return allOk
    }

    // Smart chunking: prefer section boundaries, keep safe size
    private fun chunks(s: String): List<String> {
        if (s.length <= TG_LIMIT) return listOf(s)
        val parts = mutableListOf<String>()
        val separators = listOf("\n---\n", "\n\n", "\n")
        var remaining = s
        while (remaining.length > TG_SAFE_CHUNK) {
            var cut = TG_SAFE_CHUNK
            // try to split on a nice boundary not far behind the limit
            for (sep in separators) {
                val idx = remaining.lastIndexOf(sep, TG_SAFE_CHUNK)
                if (idx >= 0 && idx >= TG_SAFE_CHUNK - 400) {
                    cut = idx + sep.length
                    break
                }
            }
            parts += remaining.substring(0, cut)
            remaining = remaining.substring(cut)
        }
        if (remaining.isNotEmpty()) parts += remaining
        return parts
    }
}

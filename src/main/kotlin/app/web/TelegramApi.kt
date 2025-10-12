package app.web

import app.AppConfig
import app.common.Json
import app.web.dto.*
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

private const val TG_LIMIT = 4096
private const val TG_SAFE_CHUNK = 3500

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
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                println("getMe HTTP ${resp.code}: $body")
                return false
            }
            val parsed: TgApiResp<TgApiUserMe> = mapper.readValue(body)
            return parsed.ok
        }
    }

    fun getUpdates(offset: Long?): List<TgUpdate> {
        val base = "${AppConfig.TELEGRAM_BASE}/bot$token/getUpdates"
        val allowed = URLEncoder.encode("""["message","edited_message","callback_query"]""", StandardCharsets.UTF_8)
        val url = buildString {
            append("$base?timeout=25&allowed_updates=$allowed")
            if (offset != null) append("&offset=$offset")
        }
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                println("getUpdates HTTP ${resp.code}: $body")
                return emptyList()
            }
            val parsed: TgApiResp<List<TgUpdate>> = mapper.readValue(body)
            if (!parsed.ok) {
                println("getUpdates API ${parsed.error_code}: ${parsed.description}")
                return emptyList()
            }
            return parsed.result ?: emptyList()
        }
    }

    fun sendMessage(
        chatId: Long,
        text: String,
        parseMode: String? = null,
        replyMarkup: ReplyMarkup? = null
    ): Boolean {
        var ok = true
        for (chunk in chunks(text)) {
            val payload = mapper.writeValueAsString(
                TgSendMessage(chat_id = chatId, text = chunk, parse_mode = parseMode, reply_markup = replyMarkup)
            )
            val req = Request.Builder()
                .url("${AppConfig.TELEGRAM_BASE}/bot$token/sendMessage")
                .post(payload.toRequestBody(json))
                .build()
            client.newCall(req).execute().use { resp ->
                val b = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    ok = false
                    println("sendMessage HTTP ${resp.code}: $b")
                }
            }
        }
        return ok
    }

    /** Фото локальным файлом (multipart/form-data). */
    fun sendPhotoFile(
        chatId: Long,
        filePath: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null
    ): Boolean {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            println("sendPhotoFile ERR: file not found: $filePath")
            return false
        }
        val media = "application/octet-stream".toMediaType()
        val fileBody: RequestBody = file.asRequestBody(media)

        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("photo", file.name, fileBody)

        if (!caption.isNullOrBlank()) builder.addFormDataPart("caption", caption)
        if (replyMarkup != null) builder.addFormDataPart("reply_markup", mapper.writeValueAsString(replyMarkup))

        val req = Request.Builder()
            .url("${AppConfig.TELEGRAM_BASE}/bot$token/sendPhoto")
            .post(builder.build())
            .build()

        client.newCall(req).execute().use { resp ->
            val b = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                println("sendPhotoFile HTTP ${resp.code}: $b")
                return false
            }
        }
        return true
    }

    fun sendPhotoResource(
        chatId: Long,
        resourcePath: String,
        caption: String? = null,
        replyMarkup: ReplyMarkup? = null
    ): Boolean {
        val loader = Thread.currentThread().contextClassLoader
        val url = loader.getResource(resourcePath)
        return if (url != null) {
            val tmp = File.createTempFile("tg_photo_", "_" + File(resourcePath).name)
            tmp.outputStream().use { out -> url.openStream().use { it.copyTo(out) } }
            tmp.deleteOnExit()
            sendPhotoFile(chatId, tmp.absolutePath, caption, replyMarkup)
        } else {
            sendPhotoFile(chatId, resourcePath, caption, replyMarkup)
        }
    }

    fun answerCallback(callbackId: String, text: String? = null) {
        val body = buildMap<String, Any?> {
            put("callback_query_id", callbackId)
            if (!text.isNullOrBlank()) put("text", text)
        }
        val req = Request.Builder()
            .url("${AppConfig.TELEGRAM_BASE}/bot$token/answerCallbackQuery")
            .post(Json.mapper.writeValueAsString(body).toRequestBody(json))
            .build()
        client.newCall(req).execute().use { }
    }

    /** Удалить сообщение (для очистки стартового баннера). */
    fun deleteMessage(chatId: Long, messageId: Int): Boolean {
        val body = mapOf("chat_id" to chatId, "message_id" to messageId)
        val req = Request.Builder()
            .url("${AppConfig.TELEGRAM_BASE}/bot$token/deleteMessage")
            .post(Json.mapper.writeValueAsString(body).toRequestBody(json))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                println("deleteMessage HTTP ${resp.code}: $raw")
                return false
            }
// deleteMessage может вернуть {"ok":true,"result":true} — result это Boolean.
// Парсим как дерево и смотрим только на ok.
            val node = try {
                mapper.readTree(raw)
            } catch (_: Throwable) {
                null
            }
            val okFlag = node?.get("ok")?.asBoolean() ?: true // некоторые обёртки возвращают пусто; считаем 200 как ок
            if (!okFlag) {
                println("deleteMessage API not ok: $raw")
            }
            return okFlag
        }
    }

    /** Фолбэк: убрать только клавиатуру у сообщения. */
    fun deleteInlineKeyboard(chatId: Long, messageId: Int) {
        val body = mapOf(
            "chat_id" to chatId,
            "message_id" to messageId,
            "reply_markup" to mapOf("inline_keyboard" to emptyList<List<Any>>())
        )
        val req = Request.Builder()
            .url("${AppConfig.TELEGRAM_BASE}/bot$token/editMessageReplyMarkup")
            .post(Json.mapper.writeValueAsString(body).toRequestBody(json))
            .build()
        client.newCall(req).execute().use { }
    }

    private fun chunks(s: String): List<String> {
        if (s.length <= TG_LIMIT) return listOf(s)
        val parts = mutableListOf<String>()
        val seps = listOf("\n---\n", "\n\n", "\n")
        var rest = s
        while (rest.length > TG_SAFE_CHUNK) {
            var cut = TG_SAFE_CHUNK
            for (sep in seps) {
                val idx = rest.lastIndexOf(sep, TG_SAFE_CHUNK)
                if (idx >= 0 && idx >= TG_SAFE_CHUNK - 400) {
                    cut = idx + sep.length; break
                }
            }
            parts += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }
}

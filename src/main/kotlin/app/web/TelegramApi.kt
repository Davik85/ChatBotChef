package app.web

import app.AppConfig
import app.web.dto.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Duration

data class TelegramSendResult(
    val ok: Boolean,
    val messageId: Int? = null,
    val errorCode: Int? = null,
    val description: String? = null,
    val retryAfterSeconds: Int? = null,
)

class TelegramPollingConflictException(
    val statusCode: Int,
    val body: String
) : RuntimeException("Telegram getUpdates conflict: HTTP $statusCode")

class TelegramApi(private val token: String) {
    private val mapper = jacksonObjectMapper()
    private val client = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30))
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30))
        .build()

    private fun url(method: String) = "${AppConfig.TELEGRAM_BASE}/bot$token/$method"
    private val json = "application/json; charset=utf-8".toMediaType()
    private val debugPaymentsLogging =
        (System.getenv("DEBUG") ?: System.getProperty("DEBUG"))?.equals("true", ignoreCase = true) == true

    private fun sanitizeBody(body: String, limit: Int = 400): String =
        body.replace("\n", " ").replace("\r", " ").take(limit)

    fun getMe(): Boolean {
        val req = Request.Builder().url(url("getMe")).get().build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                println("TG-HTTP-ERR getMe: code=${r.code} body=${sanitizeBody(raw)}")
                return false
            }
            return try {
                val parsed: TgApiResp<Map<String, Any?>> = mapper.readValue(raw)
                parsed.ok
            } catch (e: Exception) {
                println("TG-JSON-ERR getMe: ${e.message} body=${sanitizeBody(raw)}")
                false
            }
        }
    }

    fun getUpdates(offset: Long?): List<TgUpdate> {
        val args = mutableMapOf<String, Any>(
            "timeout" to 25,
            "allowed_updates" to listOf(
                "message",
                "edited_message",
                "callback_query",
                "pre_checkout_query",
                "my_chat_member",
                "chat_member"
            )
        )
        if (offset != null) args["offset"] = offset
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("getUpdates")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val sanitized = sanitizeBody(raw)
                if (r.code == 409) {
                    throw TelegramPollingConflictException(r.code, sanitized)
                }
                println("TG-HTTP-ERR getUpdates: code=${r.code} body=$sanitized")
                return emptyList()
            }
            return try {
                val parsed: TgApiResp<List<TgUpdate>> = mapper.readValue(raw)
                parsed.result ?: emptyList()
            } catch (e: Exception) {
                println("TG-JSON-ERR getUpdates: ${e.message} body=${sanitizeBody(raw)}")
                emptyList()
            }
        }
    }

    fun sendMessage(
        chatId: Long,
        text: String,
        replyMarkup: InlineKeyboardMarkup? = null,
        parseMode: String? = "Markdown",
        maxChars: Int = AppConfig.MAX_REPLY_CHARS,
        entities: List<TgMessageEntity>? = null,
    ): Int? = sendMessageDetailed(chatId, text, replyMarkup, parseMode, maxChars, entities).messageId

    fun sendMessageDetailed(
        chatId: Long,
        text: String,
        replyMarkup: InlineKeyboardMarkup? = null,
        parseMode: String? = "Markdown",
        maxChars: Int = AppConfig.MAX_REPLY_CHARS,
        entities: List<TgMessageEntity>? = null,
    ): TelegramSendResult {
        val limitedText = if (maxChars > 0 && text.length > maxChars) {
            text.substring(0, maxChars)
        } else {
            text
        }
        val args = mutableMapOf<String, Any>("chat_id" to chatId, "text" to limitedText)
        if (entities != null) {
            args["entities"] = entities
        } else if (parseMode != null) {
            args["parse_mode"] = parseMode
        }
        if (replyMarkup != null) args["reply_markup"] = replyMarkup
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("sendMessage")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            return try {
                val parsed: TgApiResp<TgMessage> = mapper.readValue(raw)
                val success = parsed.ok && r.isSuccessful
                TelegramSendResult(
                    ok = success,
                    messageId = parsed.result?.message_id,
                    errorCode = if (success) null else parsed.error_code ?: r.code,
                    description = parsed.description ?: if (r.isSuccessful) null else "HTTP ${r.code}",
                    retryAfterSeconds = parsed.parameters?.retry_after,
                )
            } catch (e: Exception) {
                TelegramSendResult(
                    ok = r.isSuccessful,
                    messageId = null,
                    errorCode = if (r.isSuccessful) null else r.code,
                    description = if (r.isSuccessful) "json_parse_error: ${e.message}" else "HTTP ${r.code}",
                    retryAfterSeconds = null,
                )
            }
        }
    }

    fun copyMessage(
        chatId: Long,
        fromChatId: Long,
        messageId: Int,
        disableNotification: Boolean? = null,
    ): TelegramSendResult {
        val args = mutableMapOf<String, Any>(
            "chat_id" to chatId,
            "from_chat_id" to fromChatId,
            "message_id" to messageId,
        )
        if (disableNotification == true) {
            args["disable_notification"] = true
        }
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("copyMessage")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            return try {
                val parsed: TgApiResp<TgMessageId> = mapper.readValue(raw)
                val success = parsed.ok && r.isSuccessful
                TelegramSendResult(
                    ok = success,
                    messageId = parsed.result?.message_id,
                    errorCode = if (success) null else parsed.error_code ?: r.code,
                    description = parsed.description ?: if (r.isSuccessful) null else "HTTP ${r.code}",
                    retryAfterSeconds = parsed.parameters?.retry_after,
                )
            } catch (e: Exception) {
                TelegramSendResult(
                    ok = r.isSuccessful,
                    messageId = null,
                    errorCode = if (r.isSuccessful) null else r.code,
                    description = if (r.isSuccessful) "json_parse_error: ${e.message}" else "HTTP ${r.code}",
                    retryAfterSeconds = null,
                )
            }
        }
    }

    fun deleteMessage(chatId: Long, messageId: Int): Boolean {
        val args = mapOf("chat_id" to chatId, "message_id" to messageId)
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("deleteMessage")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val parsed: TgApiResp<Boolean> = mapper.readValue(raw)
            return parsed.ok && (parsed.result == true)
        }
    }

    fun deleteInlineKeyboard(chatId: Long, messageId: Int): Boolean {
        val args = mapOf("chat_id" to chatId, "message_id" to messageId, "reply_markup" to InlineKeyboardMarkup(emptyList()))
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("editMessageReplyMarkup")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val parsed: TgApiResp<Map<String, Any?>> = mapper.readValue(raw)
            return parsed.ok
        }
    }

    fun sendPhotoResource(
        chatId: Long,
        resourcePath: String,
        caption: String? = null,
        replyMarkup: InlineKeyboardMarkup? = null
    ): Int? {
        val stream = Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
            ?: return sendMessage(chatId, caption ?: "")
        val tmp = File.createTempFile("tg-photo-", ".jpg").apply { deleteOnExit() }
        tmp.outputStream().use { out -> stream.copyTo(out) }

        val part = tmp.asRequestBody("image/jpeg".toMediaType())
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .addFormDataPart("photo", tmp.name, part)
        if (!caption.isNullOrBlank()) form.addFormDataPart("caption", caption)
        if (replyMarkup != null) form.addFormDataPart("reply_markup", mapper.writeValueAsString(replyMarkup))

        val req = Request.Builder().url(url("sendPhoto")).post(form.build()).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val parsed: TgApiResp<TgMessage> = mapper.readValue(raw)
            return parsed.result?.message_id
        }
    }

    fun answerCallback(callbackId: String): Boolean {
        val args = mapOf("callback_query_id" to callbackId)
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("answerCallbackQuery")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val parsed: TgApiResp<Boolean> = mapper.readValue(raw)
            return parsed.ok && (parsed.result == true)
        }
    }

    // ===== Payments (с 54-ФЗ) =====

    fun sendInvoice(
        chatId: Long,
        title: String,
        description: String,
        payload: String,
        providerToken: String?,
        currency: String,
        prices: List<TgLabeledPrice>,
        needEmail: Boolean = false,
        needPhone: Boolean = false,
        sendEmailToProvider: Boolean = false,
        sendPhoneToProvider: Boolean = false,
        providerData: Map<String, Any>? = null,
    ): Boolean {
        if (providerToken.isNullOrBlank()) {
            println("PAYMENT-ERR: providerToken is empty")
            return false
        }

        val args = mutableMapOf<String, Any>(
            "chat_id" to chatId,
            "title" to title.take(32),
            "description" to description.take(255),
            "payload" to payload.take(128),
            "provider_token" to providerToken,
            "currency" to currency,
            "prices" to prices
        )
        if (needEmail) args["need_email"] = true
        if (needPhone) args["need_phone_number"] = true
        if (sendEmailToProvider) args["send_email_to_provider"] = true
        if (sendPhoneToProvider) args["send_phone_number_to_provider"] = true
        if (providerData != null) {
            args["provider_data"] = mapper.writeValueAsString(providerData)
        }

        val reqJson = mapper.writeValueAsString(args)
        val reqJsonSafe = reqJson.replace(
            Regex("(?i)\"provider_token\"\\s*:\\s*\"[^\"]+\""),
            "\"provider_token\":\"***\""
        )
        if (debugPaymentsLogging) {
            println("PAYMENT-WARN: sendInvoice debug request=$reqJsonSafe")
        }
        val body = reqJson.toRequestBody(json)
        val req = Request.Builder().url(url("sendInvoice")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val rawSafe = sanitizeBody(raw)
            if (debugPaymentsLogging) {
                println("PAYMENT-WARN: sendInvoice debug response_code=${r.code} body=$rawSafe")
            }
            if (!r.isSuccessful) {
                println("TG-HTTP-ERR sendInvoice: code=${r.code} body=$rawSafe req=$reqJsonSafe")
                return false
            }
            return try {
                val parsed: TgApiResp<TgMessage> = mapper.readValue(raw)
                if (!parsed.ok) {
                    val errorCode = parsed.error_code?.toString() ?: "unknown"
                    val description = parsed.description ?: "unknown"
                    println("TG-API-ERR sendInvoice: error_code=$errorCode description=$description raw=$rawSafe req=$reqJsonSafe")
                }
                parsed.ok
            } catch (t: Throwable) {
                println("TG-JSON-ERR sendInvoice: ${t.message} raw=$rawSafe req=$reqJsonSafe")
                false
            }
        }
    }

    fun answerPreCheckoutQuery(id: String, ok: Boolean, errorMessage: String? = null): Boolean {
        val args = mutableMapOf<String, Any>("pre_checkout_query_id" to id, "ok" to ok)
        if (!ok && !errorMessage.isNullOrBlank()) args["error_message"] = errorMessage
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("answerPreCheckoutQuery")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val parsed: TgApiResp<Boolean> = mapper.readValue(raw)
            return parsed.ok && (parsed.result == true)
        }
    }
}

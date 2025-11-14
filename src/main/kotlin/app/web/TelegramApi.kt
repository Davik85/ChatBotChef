package app.web

import app.AppConfig
import app.db.UsersRepo
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

    private fun sanitizeReason(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return sanitizeBody(raw, limit = 200).trim().takeIf { it.isNotEmpty() }
    }

    private fun isBlockingError(code: Int?, description: String?): Boolean {
        if (code == 403) return true
        val normalized = description?.lowercase()
        if (normalized.isNullOrBlank()) return false
        if (normalized.contains("bot was blocked by the user")) return true
        if (normalized.contains("user is deactivated")) return true
        if (normalized.contains("chat not found") || normalized.contains("chat_not_found")) return code == 400 || code == 403
        if (normalized.contains("user not found")) return true
        if (normalized.contains("deleted account")) return true
        return false
    }

    private fun markUserBlockedFromSend(chatId: Long, method: String, description: String?) {
        if (chatId <= 0) return
        val reason = sanitizeReason(description) ?: "unknown"
        runCatching { UsersRepo.markBlocked(chatId, blocked = true) }
            .onSuccess { result ->
                if (result.changed && result.currentBlocked) {
                    println("USER-BLOCKED: user_id=$chatId source=$method reason=$reason")
                }
            }
            .onFailure {
                println("USER-BLOCKED-ERR: user_id=$chatId source=$method reason=${it.message}")
            }
    }

    private fun markUserUnblockedFromSend(chatId: Long, method: String) {
        if (chatId <= 0) return
        runCatching { UsersRepo.markBlocked(chatId, blocked = false) }
            .onSuccess { result ->
                if (result.changed && result.previousBlocked && !result.currentBlocked) {
                    println("USER-UNBLOCKED: user_id=$chatId source=$method")
                }
            }
            .onFailure {
                println("USER-UNBLOCKED-ERR: user_id=$chatId source=$method reason=${it.message}")
            }
    }

    private fun postProcessSendResult(
        method: String,
        chatId: Long,
        result: TelegramSendResult
    ): TelegramSendResult {
        if (result.ok) {
            if (chatId > 0) {
                markUserUnblockedFromSend(chatId, method)
            }
        } else {
            val codeLabel = result.errorCode?.toString() ?: "unknown"
            val description = sanitizeReason(result.description) ?: "unknown"
            println("TG-HTTP-ERR $method: user_id=$chatId code=$codeLabel description=$description")
            if (chatId > 0 && isBlockingError(result.errorCode, result.description)) {
                markUserBlockedFromSend(chatId, method, result.description)
            }
        }
        return result
    }

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
            val result = try {
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
            return postProcessSendResult("sendMessage", chatId, result)
        }
    }

    fun sendPhotoByFileId(
        chatId: Long,
        fileId: String,
        caption: String? = null,
        captionEntities: List<TgMessageEntity>? = null,
        disableNotification: Boolean? = null,
    ): TelegramSendResult {
        val args = mutableMapOf<String, Any>(
            "chat_id" to chatId,
            "photo" to fileId,
        )
        if (!caption.isNullOrEmpty()) {
            args["caption"] = caption
        }
        if (captionEntities != null) {
            args["caption_entities"] = captionEntities
        }
        if (disableNotification == true) {
            args["disable_notification"] = true
        }
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("sendPhoto")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val result = try {
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
            return postProcessSendResult("sendPhoto", chatId, result)
        }
    }

    fun sendVideoByFileId(
        chatId: Long,
        fileId: String,
        caption: String? = null,
        captionEntities: List<TgMessageEntity>? = null,
        disableNotification: Boolean? = null,
    ): TelegramSendResult {
        val args = mutableMapOf<String, Any>(
            "chat_id" to chatId,
            "video" to fileId,
        )
        if (!caption.isNullOrEmpty()) {
            args["caption"] = caption
        }
        if (captionEntities != null) {
            args["caption_entities"] = captionEntities
        }
        if (disableNotification == true) {
            args["disable_notification"] = true
        }
        val body = mapper.writeValueAsString(args).toRequestBody(json)
        val req = Request.Builder().url(url("sendVideo")).post(body).build()
        client.newCall(req).execute().use { r ->
            val raw = r.body?.string().orEmpty()
            val result = try {
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
            return postProcessSendResult("sendVideo", chatId, result)
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
            val result = try {
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
            return postProcessSendResult("copyMessage", chatId, result)
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
            val result = try {
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
            return postProcessSendResult("sendPhoto", chatId, result).messageId
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
                if (isBlockingError(r.code, raw)) {
                    markUserBlockedFromSend(chatId, "sendInvoice", raw)
                }
                return false
            }
            return try {
                val parsed: TgApiResp<TgMessage> = mapper.readValue(raw)
                if (!parsed.ok) {
                    val errorCode = parsed.error_code?.toString() ?: "unknown"
                    val description = parsed.description ?: "unknown"
                    println("TG-API-ERR sendInvoice: error_code=$errorCode description=$description raw=$rawSafe req=$reqJsonSafe")
                    if (isBlockingError(parsed.error_code, parsed.description)) {
                        markUserBlockedFromSend(chatId, "sendInvoice", parsed.description)
                    }
                    false
                } else {
                    markUserUnblockedFromSend(chatId, "sendInvoice")
                    true
                }
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

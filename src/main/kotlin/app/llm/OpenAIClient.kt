package app.llm

import app.AppConfig
import app.llm.dto.ChatMessage
import app.llm.dto.ChatResponse
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

class OpenAIClient(
    private val apiKey: String,
    private val model: String = "gpt-5.1",
    // Use project-wide constant for long recipes
    private val maxCompletionTokens: Int = AppConfig.OPENAI_MAX_TOKENS,
    /** Для моделей, которые поддерживают. Для 4.1/omni/o4 не отправляем вовсе. */
    private val temperature: Double? = null
) {
    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }
    private val json = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(60))
        .callTimeout(Duration.ofSeconds(90))
        .build()

    private fun sanitizeBody(body: String, maxLen: Int = 400): String =
        body.replace("\n", " ").replace("\r", " ").take(maxLen)

    private fun isUnsupportedRegion(body: String): Boolean =
        body.contains("unsupported_country_region_territory", ignoreCase = true)

    /** Общие заголовки с учётом project/org. */
    private fun headers(): okhttp3.Headers {
        val b = okhttp3.Headers.Builder()
            .add("Authorization", "Bearer $apiKey")
            .add("Content-Type", "application/json")
        AppConfig.openAiOrg?.takeIf { it.isNotBlank() }?.let { b.add("OpenAI-Organization", it) }
        AppConfig.openAiProject?.takeIf { it.isNotBlank() }?.let { b.add("OpenAI-Project", it) }
        return b.build()
    }

    /**
     * Простой GET /v1/models. Если 200 — считаем доступ валидным для текущего ключа/проекта.
     * Любые исключения — лог и false (без падения процесса).
     */
    fun healthCheck(): Boolean {
        return runCatching {
            val req = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .headers(headers())
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val safeBody = sanitizeBody(body)
                    val unsupported = resp.code == 403 && isUnsupportedRegion(body)
                    val reason = if (unsupported) "unsupported_region" else "http_error"
                    println("OPENAI-HEALTH-ERR: code=${resp.code} reason=$reason body=$safeBody")
                    return false
                }
                true
            }
        }.onFailure { e ->
            println("OPENAI-HEALTH-ERR: exception=${e.message}")
        }.getOrDefault(false)
    }

    /**
     * Chat Completions (/v1/chat/completions) c model=gpt-5.1.
     * Отправляем max_tokens. temperature — только если поддерживается.
     */
    fun complete(messages: List<ChatMessage>): String {
        val payload: MutableMap<String, Any> = mutableMapOf(
            "model" to model,
            "messages" to messages,
        )
        if (usesCompletionTokens(model)) {
            payload["max_completion_tokens"] = maxCompletionTokens
        } else {
            payload["max_tokens"] = maxCompletionTokens
        }
        if (supportsTemperature(model) && temperature != null) {
            payload["temperature"] = temperature
        }

        val requestBody = mapper.writeValueAsString(payload).toRequestBody(json)
        val request = Request.Builder()
            .url(AppConfig.OPENAI_URL)
            .headers(headers())
            .post(requestBody)
            .build()

        val maxAttempts = 3
        for (attempt in 1..maxAttempts) {
            try {
                var shouldRetry = false
                http.newCall(request).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val safe = sanitizeBody(raw)
                        val unsupported = resp.code == 403 && isUnsupportedRegion(raw)
                        val reason = when {
                            unsupported -> "unsupported_region"
                            resp.code == 429 -> "rate_limited"
                            resp.code >= 500 -> "server_error"
                            else -> "http_error"
                        }
                        println("OPENAI-HTTP-ERR: attempt=$attempt code=${resp.code} reason=$reason body=$safe")
                        if (unsupported) {
                            return AppConfig.FALLBACK_REPLY
                        }
                        shouldRetry = attempt < maxAttempts && (resp.code == 429 || resp.code >= 500)
                        if (!shouldRetry) {
                            return AppConfig.FALLBACK_REPLY
                        }
                        return@use
                    }
                    val parsed: ChatResponse = mapper.readValue(raw)
                    val reply = parsed.choices.firstOrNull()?.message?.content?.trim()
                        .takeUnless { it.isNullOrBlank() }
                        ?: AppConfig.FALLBACK_REPLY
                    return reply
                }
                if (shouldRetry) {
                    Thread.sleep(300L * attempt * attempt)
                    continue
                }
            } catch (e: Exception) {
                println("OPENAI-COMPLETE-ERR: attempt=$attempt reason=${e.message}")
                if (attempt < maxAttempts) {
                    Thread.sleep(300L * attempt * attempt)
                    continue
                }
            }
        }
        return AppConfig.FALLBACK_REPLY
    }

    private fun supportsTemperature(model: String): Boolean {
        val m = model.lowercase()
        // семейства, где temperature сейчас НЕ поддерживается в /chat/completions
        val noTemp = m.startsWith("gpt-4.1") ||
            m.startsWith("o4") ||
            m.contains("omni") ||
            m.startsWith("gpt-5-")
        return !noTemp
    }

    private fun usesCompletionTokens(model: String): Boolean {
        val normalized = model.lowercase()
        if (normalized.contains("reasoning")) return true
        return normalized.startsWith("gpt-5") ||
            normalized.startsWith("gpt-4.1") ||
            normalized.startsWith("o3") ||
            normalized.startsWith("o4")
    }
}

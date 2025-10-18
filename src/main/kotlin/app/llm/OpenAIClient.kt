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
    private val model: String = "gpt-4.1",
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
                    println("OPENAI-HEALTH: HTTP ${resp.code} ${resp.message} body=$body")
                    return false
                }
                true
            }
        }.onFailure { e ->
            println("OPENAI-HEALTH-ERR: ${e.message}")
        }.getOrDefault(false)
    }

    /**
     * Chat Completions (/v1/chat/completions) c model=gpt-4.1.
     * Отправляем max_tokens. temperature — только если поддерживается.
     */
    fun complete(messages: List<ChatMessage>): String {
        return runCatching {
            val body: MutableMap<String, Any> = mutableMapOf(
                "model" to model,
                "messages" to messages,           // ChatMessage должен иметь поля role/content
                "max_tokens" to maxCompletionTokens
            )
            if (supportsTemperature(model) && temperature != null) {
                body["temperature"] = temperature
            }

            val req = Request.Builder()
                .url(AppConfig.OPENAI_URL)        // https://api.openai.com/v1/chat/completions
                .headers(headers())
                .post(mapper.writeValueAsString(body).toRequestBody(json))
                .build()

            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    println("OPENAI-HTTP-ERR: code=${resp.code} msg=${resp.message} body=$raw")
                    return AppConfig.FALLBACK_REPLY
                }
                val parsed: ChatResponse = mapper.readValue(raw)
                parsed.choices.firstOrNull()?.message?.content?.trim()
                    .takeUnless { it.isNullOrBlank() }
                    ?: AppConfig.FALLBACK_REPLY
            }
        }.onFailure { e ->
            println("OPENAI-COMPLETE-ERR: ${e.message}")
        }.getOrDefault(AppConfig.FALLBACK_REPLY)
    }

    private fun supportsTemperature(model: String): Boolean {
        val m = model.lowercase()
        // семейства, где temperature сейчас НЕ поддерживается в /chat/completions
        val noTemp = m.startsWith("gpt-4.1") || m.startsWith("o4") || m.contains("omni")
        return !noTemp
    }
}

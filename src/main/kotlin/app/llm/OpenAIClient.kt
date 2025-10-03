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

    fun healthCheck(): Boolean {
        val req = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .apply {
                AppConfig.openAiOrg?.let { addHeader("OpenAI-Organization", it) }
                AppConfig.openAiProject?.let { addHeader("OpenAI-Project", it) }
            }
            .get()
            .build()
        return http.newCall(req).execute().use { resp -> resp.isSuccessful }
    }

    fun complete(messages: List<ChatMessage>): String {
        // For /v1/chat/completions the correct field is "max_tokens"
        val body: MutableMap<String, Any> = mutableMapOf(
            "model" to model,
            "messages" to messages,
            "max_tokens" to maxCompletionTokens
        )
        // temperature — только если уместно для модели
        if (supportsTemperature(model) && temperature != null) {
            body["temperature"] = temperature
        }

        val req = Request.Builder()
            .url(AppConfig.OPENAI_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .apply {
                AppConfig.openAiOrg?.let { addHeader("OpenAI-Organization", it) }
                AppConfig.openAiProject?.let { addHeader("OpenAI-Project", it) }
            }
            .post(mapper.writeValueAsString(body).toRequestBody(json))
            .build()

        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                println("OPENAI-HTTP-ERR: code=${resp.code} msg=${resp.message} body=$raw")
                return AppConfig.FALLBACK_REPLY
            }
            val parsed: ChatResponse = mapper.readValue(raw)
            return parsed.choices.firstOrNull()?.message?.content?.trim()
                .takeUnless { it.isNullOrBlank() }
                ?: AppConfig.FALLBACK_REPLY
        }
    }

    private fun supportsTemperature(model: String): Boolean {
        val m = model.lowercase()
        // семейства, где temperature сейчас НЕ поддерживается в /chat/completions
        val noTemp = m.startsWith("gpt-4.1") || m.startsWith("o4") || m.contains("omni")
        return !noTemp
    }
}

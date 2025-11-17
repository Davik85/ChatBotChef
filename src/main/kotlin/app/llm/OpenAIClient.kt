package app.llm

import app.AppConfig
import app.llm.dto.ChatMessage
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Duration

internal data class OpenAIRequestPayload(
    val url: String,
    val body: Map<String, Any>
)

class OpenAIClient(
    private val apiKey: String,
    private val model: String = AppConfig.openAiModel,
    // Use project-wide constant for long recipes
    private val maxCompletionTokens: Int = AppConfig.OPENAI_MAX_TOKENS,
    /** Для моделей, которые поддерживают. Для 4.1/omni/o4 не отправляем вовсе. */
    private val temperature: Double? = null,
    private val reasoningEffort: String? = AppConfig.openAiReasoningEffort
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
     * Chat Completions или Responses API — в зависимости от модели.
     * Публичный контракт (возвращаемая строка) не меняется.
     */
    fun complete(messages: List<ChatMessage>): String {
        val requestSpec = buildRequestPayload(messages)
        val requestBody = mapper.writeValueAsString(requestSpec.body).toRequestBody(json)
        val request = Request.Builder()
            .url(requestSpec.url)
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
                    val reply = parseAssistantReply(raw) ?: AppConfig.FALLBACK_REPLY
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

    internal fun buildRequestPayload(messages: List<ChatMessage>): OpenAIRequestPayload {
        val useResponsesApi = usesResponsesApi(model)
        val payload: MutableMap<String, Any> = mutableMapOf(
            "model" to model
        )
        val url: String
        if (useResponsesApi) {
            url = AppConfig.OPENAI_RESPONSES_URL
            payload["input"] = messages.map { message ->
                mapOf(
                    "role" to message.role,
                    "content" to listOf(
                        mapOf(
                            "type" to "text",
                            "text" to message.content
                        )
                    )
                )
            }
            payload["modalities"] = listOf("text")
            payload["max_output_tokens"] = maxCompletionTokens
            reasoningEffort?.let { payload["reasoning"] = mapOf("effort" to it) }
            if (supportsTemperature(model) && temperature != null) {
                payload["temperature"] = temperature
            }
        } else {
            url = AppConfig.OPENAI_URL
            payload["messages"] = messages
            if (usesCompletionTokens(model)) {
                payload["max_completion_tokens"] = maxCompletionTokens
            } else {
                payload["max_tokens"] = maxCompletionTokens
            }
            if (supportsTemperature(model) && temperature != null) {
                payload["temperature"] = temperature
            }
        }
        return OpenAIRequestPayload(url = url, body = payload)
    }

    internal fun parseAssistantReply(raw: String): String? {
        return try {
            val node = mapper.readTree(raw)
            ResponseParser.extract(node)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            println("OPENAI-PARSE-ERR: ${e.message}")
            null
        }
    }

    private fun supportsTemperature(model: String): Boolean {
        val canonical = canonicalModel(model)
        // семейства, где temperature сейчас НЕ поддерживается в /chat/completions
        val noTemp = canonical.startsWith("gpt-4.1") ||
            canonical.startsWith("o4") ||
            canonical.contains("omni") ||
            canonical.startsWith("gpt-5")
        return !noTemp
    }

    private fun usesResponsesApi(model: String): Boolean {
        val canonical = canonicalModel(model)
        return canonical.startsWith("gpt-5") || canonical.contains("reasoning")
    }

    private fun usesCompletionTokens(model: String): Boolean {
        val canonical = canonicalModel(model)
        if (canonical.contains("reasoning")) return true
        return canonical.startsWith("gpt-5") ||
            canonical.startsWith("gpt-4.1") ||
            canonical.startsWith("o3") ||
            canonical.startsWith("o4")
    }

    private fun canonicalModel(model: String): String {
        val trimmed = model.trim().lowercase()
        val withoutSlash = trimmed.substringAfterLast('/')
        val withoutPrefix = withoutSlash.substringAfterLast(':')
        return withoutPrefix.substringBefore('@')
    }

    private object ResponseParser {
        fun extract(root: JsonNode): String? {
            extractArrayText(root["output_text"])?.let { return it }
            extractFromOutput(root["output"])?.let { return it }
            extractFromChoices(root["choices"])?.let { return it }
            return null
        }

        private fun extractArrayText(node: JsonNode?): String? {
            if (node == null || !node.isArray) return null
            val text = node.mapNotNull { part ->
                val value = part.asText().trim()
                value.takeIf { it.isNotEmpty() }
            }.joinToString(separator = "\n").trim()
            return text.takeIf { it.isNotEmpty() }
        }

        private fun extractFromOutput(node: JsonNode?): String? {
            if (node == null || !node.isArray) return null
            val pieces = node.mapNotNull { extractFromContent(it["content"]) }
            val text = pieces.joinToString(separator = "\n").trim()
            return text.takeIf { it.isNotEmpty() }
        }

        private fun extractFromChoices(node: JsonNode?): String? {
            if (node == null || !node.isArray) return null
            node.forEach { choice ->
                extractFromContent(choice["message"]?.get("content"))?.let { return it }
                extractFromContent(choice["delta"]?.get("content"))?.let { return it }
            }
            return null
        }

        private fun extractFromContent(contentNode: JsonNode?): String? {
            if (contentNode == null || contentNode.isNull) return null
            return when {
                contentNode.isTextual -> contentNode.asText().trim().takeIf { it.isNotEmpty() }
                contentNode.isArray -> {
                    val parts = contentNode.mapNotNull { part -> extractFromContent(part) }
                    val text = parts.joinToString(separator = "\n").trim()
                    text.takeIf { it.isNotEmpty() }
                }
                contentNode.isObject -> {
                    contentNode["text"]?.let { extractFromContent(it) }
                        ?: contentNode["content"]?.let { extractFromContent(it) }
                }
                else -> null
            }
        }
    }
}

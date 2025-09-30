package app.llm.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatMessage(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.6,
    val max_tokens: Int? = 300
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatChoice(
    val index: Int,
    val message: ChatMessage
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatResponse(
    val choices: List<ChatChoice>
)

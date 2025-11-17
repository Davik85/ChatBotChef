package app.llm

import app.AppConfig
import app.llm.dto.ChatMessage
import app.testsupport.Test
import app.testsupport.assertEquals
import app.testsupport.assertNotNull
import app.testsupport.assertTrue

class OpenAIClientTest {

    @Test
    fun `responses payload is built for gpt-5 family`() {
        val client = OpenAIClient(apiKey = "test", model = "gpt-5-mini")
        val payload = client.buildRequestPayload(listOf(ChatMessage(role = "user", content = "Привет")))

        assertEquals(AppConfig.OPENAI_RESPONSES_URL, payload.url)
        assertEquals("gpt-5-mini", payload.body["model"])
        assertEquals(AppConfig.OPENAI_MAX_TOKENS, payload.body["max_output_tokens"])

        val input = payload.body["input"] as List<*>
        assertEquals(1, input.size)
        val first = input.first() as Map<*, *>
        assertEquals("user", first["role"])
        val content = first["content"] as List<*>
        val part = content.first() as Map<*, *>
        assertEquals("text", part["type"])
        assertEquals("Привет", part["text"])
    }

    @Test
    fun `chat completions payload kept for non reasoning models`() {
        val client = OpenAIClient(apiKey = "test", model = "gpt-4o-mini")
        val payload = client.buildRequestPayload(listOf(ChatMessage(role = "user", content = "Hello")))

        assertEquals(AppConfig.OPENAI_URL, payload.url)
        assertTrue("messages" in payload.body)
        assertEquals(AppConfig.OPENAI_MAX_TOKENS, payload.body["max_tokens"])
    }

    @Test
    fun `parser handles responses output`() {
        val client = OpenAIClient(apiKey = "test", model = "gpt-5-mini")
        val json = """
            {
              "output": [
                {
                  "content": [
                    {"type": "output_text", "text": "Первый кусок"},
                    {"type": "output_text", "text": "Второй кусок"}
                  ]
                }
              ]
            }
        """.trimIndent()
        val reply = client.parseAssistantReply(json)
        assertEquals("Первый кусок\nВторой кусок", reply)
    }

    @Test
    fun `parser falls back to chat choices`() {
        val client = OpenAIClient(apiKey = "test", model = "gpt-4o-mini")
        val json = """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": "Ответ"
                  }
                }
              ]
            }
        """.trimIndent()
        val reply = client.parseAssistantReply(json)
        assertNotNull(reply)
        assertEquals("Ответ", reply)
    }
}

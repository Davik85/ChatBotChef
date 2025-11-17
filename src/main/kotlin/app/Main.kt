package app

import app.db.DatabaseFactory
import app.llm.OpenAIClient
import app.telegram.TelegramLongPolling
import kotlinx.coroutines.runBlocking

private fun mask(s: String, head: Int = 4, tail: Int = 4): String =
    if (s.length <= head + tail) "*".repeat(s.length)
    else s.take(head) + "*".repeat(s.length - head - tail) + s.takeLast(tail)

fun main() {
    try {
        // Just for visibility in logs
        val rawTg = System.getenv("TELEGRAM_TOKEN")
        val rawAi = System.getenv("OPENAI_API_KEY")
        val rawOrg = System.getenv("OPENAI_ORG")
        val rawProj = System.getenv("OPENAI_PROJECT")
        println(
            "BOOT-ENV: TELEGRAM_TOKEN=${if (rawTg.isNullOrBlank()) "missing" else "present"}, " +
                    "OPENAI_API_KEY=${if (rawAi.isNullOrBlank()) "missing" else "present"}"
        )
        println("BOOT: long-polling mode")

        // Read validated config (will throw with a clear message if invalid)
        val tgToken = runCatching { AppConfig.telegramToken }.getOrElse {
            println("FATAL: TELEGRAM_TOKEN invalid or missing. ${it.message}")
            return
        }
        val aiKey = runCatching { AppConfig.openAiApiKey }.getOrElse {
            println("FATAL: OPENAI_API_KEY invalid or missing. ${it.message}")
            return
        }
        println("BOOT-TOKENS: tg=${mask(tgToken)} ai=${mask(aiKey)}")
        val orgDisplay = AppConfig.openAiOrg?.let { mask(it, head = 3, tail = 2) } ?: "-"
        val projectDisplay = AppConfig.openAiProject?.let { mask(it, head = 3, tail = 2) } ?: "-"
        println("BOOT-OPENAI-HEADERS: org=$orgDisplay project=$projectDisplay")

        // Optional: init DB if your project uses it
        runCatching { DatabaseFactory.init() }
            .onFailure { println("DB-INIT-ERR: ${it.message}") }

        val llm = OpenAIClient(apiKey = aiKey)

        if (!llm.healthCheck()) {
            println("FATAL: OpenAI недоступен (ключ/доступ/проект). Проверь OPENAI_API_KEY / OPENAI_ORG / OPENAI_PROJECT.")
            return
        }

        println("Starting Telegram long polling…")
        runBlocking {
            // TelegramLongPolling теперь сам создаёт TelegramApi внутри
            TelegramLongPolling(
                token = tgToken,
                llm = llm
            ).run()
        }
    } catch (t: Throwable) {
        t.printStackTrace()
    }
}

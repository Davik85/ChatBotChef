package app.web

import app.AppConfig
import app.common.Json
import app.db.PremiumRepo
import app.llm.OpenAIClient
import app.llm.dto.ChatMessage
import app.logic.MemoryService
import app.logic.RateLimiter
import app.logic.Safety
import app.web.dto.LpMessage
import app.web.dto.LpResp
import app.web.dto.LpUpdate
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration
import kotlin.math.max

private const val LONG_POLL_TIMEOUT_SEC = 25

class TelegramLongPolling(
    private val token: String,
    private val tg: TelegramApi,
    private val ai: OpenAIClient,
    private val mem: MemoryService
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(LONG_POLL_TIMEOUT_SEC + 10L))
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(LONG_POLL_TIMEOUT_SEC + 10L))
        .build()

    private val mapper = Json.mapper

    @Volatile
    private var running = true

    suspend fun start() {
        tg.getMe() // просто лог, не фейлим старт

        var offset = 0L
        var backoff = 400L
        println("POLL: start (timeout=${LONG_POLL_TIMEOUT_SEC}s)")

        while (running) {
            try {
                val url = "${AppConfig.TELEGRAM_BASE}/bot$token/getUpdates" +
                        "?timeout=$LONG_POLL_TIMEOUT_SEC&offset=$offset&allowed_updates=%5B%22message%22%5D"
                val req = Request.Builder().url(url).get().build()

                http.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) error("getUpdates HTTP ${resp.code} ${resp.message} body=$raw")

                    val data: LpResp<List<LpUpdate>> = mapper.readValue(raw)
                    if (!data.ok) {
                        val ra = data.parameters?.retry_after
                        if (data.error_code == 429 && ra != null) {
                            println("POLL: 429 Too Many Requests → sleep ${ra}s")
                            delay(ra * 1000L); return@use
                        }
                        error("getUpdates API ${data.error_code}: ${data.description}")
                    }

                    val updates = data.result.orEmpty()
                    if (updates.isEmpty()) {
                        println("POLL: 0 updates (offset=$offset)")
                    } else {
                        println("POLL: got ${updates.size} updates (offset=$offset)")
                        for (u in updates) {
                            offset = max(offset, u.update_id + 1)
                            u.message?.let { handleMessage(it) }
                        }
                    }
                    backoff = 400L
                }
            } catch (e: Exception) {
                println("POLL-ERR: ${e.message}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000)
            }
        }
    }

    fun stop() {
        running = false
    }

    private fun handleMessage(msg: LpMessage) {
        val tgUserId = msg.from?.id ?: msg.chat.id
        val text = msg.text?.trim().orEmpty()
        if (text.isEmpty()) return

        println("MSG: from=$tgUserId text='${text.take(60)}'")

        if (text == "/start") {
            tg.sendMessage(
                msg.chat.id,
                """Привет! Я твой друг - AI-повар. Напиши, какой рецепт ты хочешь получить.
                    |- Например: "Что приготовить из курицы и риса?".
                    |- Я помогу с идеями и советами по готовке!
                    |- Или скажи какие ингредиенты у тебя есть, предпочтения по кухне и диете – и я предложу рецепт.
                    |- Чтобы узнать подробности и возможности, отправь /help
                """.trimMargin()
            )
            return
        }

        // 1) Кризис — всегда приоритетнее
        if (Safety.isCrisis(text)) {
            val safe = """
                Похоже, тебе сейчас очень тяжело. Ты не один.
                В экстренной ситуации звони 112. Бесплатные линии поддержки: 8-800-2000-122, 8-800-700-06-00.
                Я рядом и готова поговорить столько, сколько нужно.
            """.trimIndent()
            tg.sendMessage(msg.chat.id, safe)
            return
        }

        // 2) Премиум/лимит
        val isPremium = PremiumRepo.isPremium(tgUserId)
        if (!isPremium) {
            val can = RateLimiter.canSend(tgUserId)
            if (!can) {
                val left = RateLimiter.remaining(tgUserId)
                println("LIMIT-HIT: user=$tgUserId remaining=$left")
                tg.sendMessage(msg.chat.id, AppConfig.PAYWALL_TEXT)
                return
            }
        }

        // 3) Контекст для LLM
        val system = ChatMessage("system", app.logic.PersonaPrompt.system())
        val memoryNote = mem.getNote(tgUserId)?.let { ChatMessage("system", "Memory: $it") }
        val history = mem.recentDialog(tgUserId).flatMap { (role, content) ->
            listOf(ChatMessage(role, content))
        }

        val messages = buildList {
            add(system)
            if (memoryNote != null) add(memoryNote)
            addAll(history)
            add(ChatMessage("user", text))
        }

        // 4) Вызов LLM
        val reply = try {
            ai.complete(messages)
        } catch (e: Exception) {
            println("AI-ERR: ${e.message}")
            AppConfig.FALLBACK_REPLY
        }

        // 5) Пишем в память и считаем лимит только ПОСЛЕ успешной отправки
        val delivered = tg.sendMessage(msg.chat.id, reply)
        if (delivered) {
            mem.append(tgUserId, "user", text, System.currentTimeMillis())
            mem.append(tgUserId, "assistant", reply, System.currentTimeMillis())
            if (!isPremium) RateLimiter.increment(tgUserId)
        } else {
            println("SEND-WARN: message wasn't delivered (see SEND-ERR above).")
        }
    }
}

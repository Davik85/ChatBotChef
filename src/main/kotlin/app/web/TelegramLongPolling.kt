package app.web

import app.AppConfig
import app.common.Json
import app.llm.OpenAIClient
import app.llm.dto.ChatMessage
import app.logic.CalorieCalculatorPrompt
import app.web.dto.*
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.time.Duration

/**
 * Long polling loop + command router.
 * /caloriecalculator now uses a dedicated system prompt from CalorieCalculatorPrompt.
 * No local math — GPT does BMR/TDEE and macros.
 */
class TelegramLongPolling(
    private val token: String,
    private val llm: OpenAIClient
) {
    private val http = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(30))
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(30))
        .build()

    private val api = TelegramApi(token)
    private val mapper = Json.mapper

    // Minimal in-memory state per chat
    private val state = mutableMapOf<Long, BotState>()

    private enum class BotState { AWAITING_CALORIE_INPUT }

    suspend fun run() {
        require(api.getMe()) { "Telegram getMe failed" }

        var offset: Long? = null
        while (true) {
            try {
                val updates = getUpdates(offset)
                if (updates.isEmpty()) {
                    delay(1200); continue
                }
                for (u in updates) {
                    offset = u.update_id + 1
                    val msg = u.message ?: u.edited_message ?: continue
                    val text = msg.text?.trim().orEmpty()
                    if (text.isBlank()) continue
                    route(msg.chat.id, text)
                }
            } catch (t: Throwable) {
                println("POLLING-ERR: ${t.message}")
                delay(1500)
            }
        }
    }

    private fun getUpdates(offset: Long?): List<TgUpdate> {
        val base = "${AppConfig.TELEGRAM_BASE}/bot$token/getUpdates?timeout=25"
        val url = if (offset != null) "$base&offset=$offset" else base
        val req = okhttp3.Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return emptyList()
            val parsed: TgApiResp<List<TgUpdate>> = mapper.readValue(raw)
            return if (parsed.ok) parsed.result ?: emptyList() else emptyList()
        }
    }

    // ----- Router -----
    private fun route(chatId: Long, text: String) {
        // If we expect the calorie input, short-circuit.
        if (state[chatId] == BotState.AWAITING_CALORIE_INPUT && !text.startsWith("/")) {
            handleCalorieInput(chatId, text)
            return
        }

        when (text.lowercase()) {
            "/start" -> {
                api.sendMessage(
                    chatId = chatId,
                    text = "Привет! Я шеф-повар-бот.\nВыбирай действие:",
                    replyMarkup = ReplyKeyboardMarkup(
                        keyboard = listOf(
                            listOf(KeyboardButton("/caloriecalculator")),
                            listOf(KeyboardButton("/productinfo"), KeyboardButton("/help"))
                        ),
                        resize_keyboard = true,
                        one_time_keyboard = false
                    )
                )
            }
            "/help" -> {
                api.sendMessage(
                    chatId,
                    "Команды:\n" +
                            "• /caloriecalculator — расчёт КБЖУ по твоим данным через ИИ\n" +
                            "• /productinfo — КБЖУ ингредиента\n" +
                            "Напиши «/caloriecalculator», и я попрошу ввести параметры одним сообщением."
                )
            }
            "/caloriecalculator" -> {
                state[chatId] = BotState.AWAITING_CALORIE_INPUT
                api.sendMessage(chatId, CALORIE_INPUT_PROMPT)
            }
            "/productinfo" -> {
                api.sendMessage(chatId, "Скоро добавим. А пока можешь писать: «КБЖУ курица филе 100 г».")
            }
            else -> {
                // Fallback to chef assistant persona
                val sys = ChatMessage(
                    "system",
                    "Ты — дружелюбный шеф-повар и нутриционист. " +
                            "Если запрос не относится к рецептам/еде/КБЖУ — вежливо подскажи команды /help."
                )
                val user = ChatMessage("user", text)
                val reply = llm.complete(listOf(sys, user))
                api.sendMessage(chatId, reply)
            }
        }
    }

    // ----- Calorie flow via GPT (uses external prompt object) -----
    private fun handleCalorieInput(chatId: Long, userText: String) {
        val system = ChatMessage("system", CalorieCalculatorPrompt.SYSTEM)
        val user = ChatMessage("user", "Данные пользователя: $userText")
        val reply = llm.complete(listOf(system, user))

        // Remove keyboard for clean reading, then clear state
        api.removeKeyboard(chatId, reply)
        state.remove(chatId)
    }

    companion object {
        private const val CALORIE_INPUT_PROMPT =
            "Пришли **в одном сообщении**: пол, возраст, рост (см), вес (кг), образ жизни (пассивный/активный), " +
                    "сколько шагов в день и сколько тренировок в неделю, цель (похудеть/набрать массу).\n\n" +
                    "Пример: «мужчина, 40 лет, 175 см, 80 кг, активный, 9000 шагов, 4 тренировки в неделю, цель похудеть»."
    }
}

package app.web

import app.AppConfig
import app.common.Json
import app.llm.OpenAIClient
import app.llm.dto.ChatMessage
import app.logic.PersonaPrompt
import app.logic.CalorieCalculatorPrompt
import app.web.dto.*
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.time.Duration

/**
 * Long polling + command router with persona switching.
 *
 * Personas:
 *  - CHEF (default): uses PersonaPrompt.system()
 *  - CALC: uses CalorieCalculatorPrompt. SYSTEM
 *
 * Switch:
 *  - /start or /recipes -> CHEF
 *  - /caloriecalculator -> CALC (asks for one-message input)
 *
 * No local math: CALC persona prompts GPT to compute BMR/TDEE/macros.
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

    // ---- Per-chat state ----
    private val mode = mutableMapOf<Long, PersonaMode>()
    private val state = mutableMapOf<Long, BotState>()

    private enum class PersonaMode { CHEF, CALC }
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
        val lower = text.lowercase()

        // If CALC is waiting for input and user sent non-command -> handle input
        if (mode[chatId] == PersonaMode.CALC &&
            state[chatId] == BotState.AWAITING_CALORIE_INPUT &&
            !lower.startsWith("/")
        ) {
            handleCalorieInput(chatId, text)
            return
        }

        when {
            lower == "/start" -> {
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                api.sendMessage(
                    chatId = chatId,
                    text = "Привет! Я шеф-повар-бот. Выбирай действие:",
                    replyMarkup = mainKeyboard()
                )
            }
            lower == "/help" -> {
                api.sendMessage(
                    chatId,
                    "Команды:\n" +
                            "• /recipes — вернуться к шеф-повару (рецепты, советы)\n" +
                            "• /caloriecalculator — расчёт КБЖУ через ИИ\n" +
                            "• /productinfo — КБЖУ ингредиента (скоро)\n\n" +
                            "Подсказка: после /caloriecalculator можешь вернуться к рецептам через /recipes или /start."
                )
            }
            lower == "/recipes" -> {
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                api.sendMessage(
                    chatId,
                    "Готов готовить! Напиши продукты/условия (время, калории, техника) — предложу рецепт.",
                    replyMarkup = mainKeyboard()
                )
            }
            lower == "/caloriecalculator" -> {
                mode[chatId] = PersonaMode.CALC
                state[chatId] = BotState.AWAITING_CALORIE_INPUT
                api.sendMessage(chatId, CALORIE_INPUT_PROMPT)
            }
            lower == "/productinfo" -> {
                api.sendMessage(chatId, "Скоро добавим. Пока можно писать: «КБЖУ курица филе 100 г».")
            }
            else -> {
                // Free text -> answer according to current persona
                when (mode[chatId] ?: PersonaMode.CHEF) {
                    PersonaMode.CHEF -> handleChef(chatId, text)
                    PersonaMode.CALC -> {
                        // If not explicitly awaiting input, still treat any free text as input for calculator
                        handleCalorieInput(chatId, text)
                    }
                }
            }
        }
    }

    // ----- Chef persona -----
    private fun handleChef(chatId: Long, userText: String) {
        val sys = ChatMessage("system", PersonaPrompt.system())
        val user = ChatMessage("user", userText)
        val reply = llm.complete(listOf(sys, user))
        api.sendMessage(chatId, reply)
    }

    // ----- Calorie persona (GPT does all math) -----
    private fun handleCalorieInput(chatId: Long, userText: String) {
        val system = ChatMessage("system", CalorieCalculatorPrompt.SYSTEM)
        val user = ChatMessage("user", "Данные пользователя: $userText")
        val reply = llm.complete(listOf(system, user))

        // Keep persona CALC until user returns via /recipes or /start,
        // but remove keyboard for cleaner reading of long answer.
        api.removeKeyboard(chatId, reply)
        state.remove(chatId)
    }

    // ----- UI helpers -----
    private fun mainKeyboard(): ReplyKeyboardMarkup =
        ReplyKeyboardMarkup(
            keyboard = listOf(
                listOf(KeyboardButton("/recipes")),
                listOf(KeyboardButton("/caloriecalculator")),
                listOf(KeyboardButton("/productinfo"), KeyboardButton("/help"))
            ),
            resize_keyboard = true,
            one_time_keyboard = false
        )

    companion object {
        private const val CALORIE_INPUT_PROMPT =
            "Пришли **в одном сообщении**: пол, возраст, рост (см), вес (кг), образ жизни (пассивный/активный), " +
                    "сколько шагов в день и сколько тренировок в неделю, цель (похудеть/набрать массу).\n\n" +
                    "Пример: «мужчина, 40 лет, 175 см, 80 кг, активный, 9000 шагов, 4 тренировки в неделю, цель похудеть»."
    }
}

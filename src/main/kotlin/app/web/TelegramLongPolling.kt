package app.web

import app.AppConfig
import app.common.Json
import app.llm.OpenAIClient
import app.llm.dto.ChatMessage
import app.logic.PersonaPrompt
import app.logic.CalorieCalculatorPrompt
import app.logic.ProductInfoPrompt
import app.web.dto.*
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.time.Duration

/**
 * Long polling + command router with persona switching.
 *
 * Personas:
 *  - CHEF (default): PersonaPrompt.system()
 *  - CALC: CalorieCalculatorPrompt. SYSTEM
 *  - PRODUCT: ProductInfoPrompt. SYSTEM
 *
 * Switch:
 *  - /start or /recipes -> CHEF
 *  - /caloriecalculator -> CALC (asks for one-message input)
 *  - /productinfo -> PRODUCT (asks for product name)
 *
 * No local math: GPT handles calculations and formatting.
 * No reply keyboards: we always remove keyboards to avoid the bottom floating menu.
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

    private enum class PersonaMode { CHEF, CALC, PRODUCT }
    private enum class BotState { AWAITING_CALORIE_INPUT, AWAITING_PRODUCT_INPUT }

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

        // Short-circuits for awaited inputs
        when (state[chatId]) {
            BotState.AWAITING_CALORIE_INPUT -> {
                if (!lower.startsWith("/")) { handleCalorieInput(chatId, text); return }
            }
            BotState.AWAITING_PRODUCT_INPUT -> {
                if (!lower.startsWith("/")) { handleProductInput(chatId, text); return }
            }
            else -> {}
        }

        when {
            lower == "/start" -> {
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                // Remove any old keyboards and send RU greeting
                api.removeKeyboard(chatId, START_GREETING_RU)
            }
            lower == "/help" -> {
                api.removeKeyboard(
                    chatId,
                    "Команды:\n" +
                            "• /recipes — вернуться к шеф-повару (рецепты, советы)\n" +
                            "• /caloriecalculator — расчёт КБЖУ через ИИ\n" +
                            "• /productinfo — КБЖУ ингредиента\n\n" +
                            "Подсказка: клавиатура скрыта, используйте меню с командами или печатайте их вручную."
                )
            }
            lower == "/recipes" -> {
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                api.removeKeyboard(
                    chatId,
                    "Готов готовить! Напиши продукты/условия (время, калории, техника) — предложу рецепт."
                )
            }
            lower == "/caloriecalculator" -> {
                mode[chatId] = PersonaMode.CALC
                state[chatId] = BotState.AWAITING_CALORIE_INPUT
                api.removeKeyboard(chatId, CALORIE_INPUT_PROMPT)
            }
            lower == "/productinfo" -> {
                mode[chatId] = PersonaMode.PRODUCT
                state[chatId] = BotState.AWAITING_PRODUCT_INPUT
                api.removeKeyboard(chatId, PRODUCT_INPUT_PROMPT)
            }
            else -> {
                // Free text -> answer according to current persona
                when (mode[chatId] ?: PersonaMode.CHEF) {
                    PersonaMode.CHEF -> handleChef(chatId, text)
                    PersonaMode.CALC -> handleCalorieInput(chatId, text)
                    PersonaMode.PRODUCT -> handleProductInput(chatId, text)
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
        api.sendMessage(chatId, reply)
        state.remove(chatId) // stay in CALC persona, but no pending input
    }

    // ----- Product info persona -----
    private fun handleProductInput(chatId: Long, userText: String) {
        val system = ChatMessage("system", ProductInfoPrompt.SYSTEM)
        val user = ChatMessage("user", "Ингредиент: $userText")
        val reply = llm.complete(listOf(system, user))
        api.sendMessage(chatId, reply)
        state.remove(chatId) // stay in PRODUCT persona, but no pending input
    }

    companion object {
        private const val START_GREETING_RU =
            "Привет! Я шеф-повар-бот.\n\n" +
                    "Что умею:\n" +
                    "• Придумываю рецепты под твои продукты, время и технику.\n" +
                    "• Считаю КБЖУ под цель через ИИ (/caloriecalculator).\n" +
                    "• Даю сводку по калорийности и БЖУ продукта (/productinfo).\n\n" +
                    "Напиши продукты или используй команды из меню.\n" +
                    "Вернуться к рецептам: /recipes"

        private const val CALORIE_INPUT_PROMPT =
            "Пришли в одном сообщении: пол, возраст, рост (см), вес (кг), " +
                    "образ жизни (пассивный/активный), сколько шагов в день и сколько тренировок в неделю, " +
                    "цель (похудеть/набрать массу).\n\n" +
                    "Пример: «мужчина, 40 лет, 175 см, 80 кг, активный, 9000 шагов, 4 тренировки в неделю, цель похудеть»."

        private const val PRODUCT_INPUT_PROMPT =
            "Пришли название ингредиента (можно с уточнением части/жирности и способа приготовления). Примеры:\n" +
                    "«свинина шея», «лосось сырой», «куриная грудка без кожи», «рис отварной», «сыр моцарелла»."
    }
}

package app.web

import app.llm.OpenAIClient
import app.llm.dto.ChatMessage
import app.logic.PersonaPrompt
import app.logic.CalorieCalculatorPrompt
import app.logic.ProductInfoPrompt
import app.web.dto.InlineKeyboardButton
import app.web.dto.InlineKeyboardMarkup
import app.web.dto.TgCallbackQuery
import app.web.dto.TgUpdate
import kotlinx.coroutines.delay

/**
 * Меню показываем только на /start. После клика меню скрываем.
 * Русские тексты НЕ меняем.
 */
class TelegramLongPolling(
    private val token: String,
    private val llm: OpenAIClient
) {
    private val api = TelegramApi(token)

    private val mode = mutableMapOf<Long, PersonaMode>()
    private val state = mutableMapOf<Long, BotState>()

    private enum class PersonaMode { CHEF, CALC, PRODUCT }
    private enum class BotState { AWAITING_CALORIE_INPUT, AWAITING_PRODUCT_INPUT }

    private companion object {
        private const val CB_RECIPES = "menu_recipes"
        private const val CB_CALC = "menu_calc"
        private const val CB_PRODUCT = "menu_product"
        private const val CB_HELP = "menu_help"

        private const val START_GREETING_RU =
            "Привет! Начнем?"

        private val CALORIE_INPUT_PROMPT = """
            Пришли в одном сообщении: пол, возраст, рост (см), вес (кг), образ жизни (пассивный/активный), сколько шагов в день и сколько тренировок в неделю, цель (похудеть/набрать массу). 
            
            Пример: «мужчина, 40 лет, 175 см, 80 кг, активный, 9000 шагов, 4 тренировки в неделю, цель похудеть».
            Или вернись к /start для меню.
        """.trimIndent()

        private val PRODUCT_INPUT_PROMPT = """
            Пришли название ингредиента (можно с уточнением части/жирности и способа приготовления). 
            Примеры:«свинина шея», «лосось сырой», «куриная грудка без кожи», «рис отварной», «сыр моцарелла».
            Или вернись к /start для меню.
        """.trimIndent()

        private val CHEF_INPUT_PROMPT = """
            Напиши продукты и условия (прием пищи, техника, диета). 
            
            Например: «обед, курица, рис, брокколи, пароварка». 
            Также можно просто спросить «что приготовить из курицы и риса на обед? я на диете, худею.».
            
            Или вернись к /start для меню.
        """.trimIndent()

        private val HELP_TEXT = """
            Помогу с идеями блюд, подсчётом калорий и КБЖУ ингредиентов.

            Доступные команды:
            • /recipes — режим «Рецепты». Напиши продукты и условия (приём пищи, техника, диета).
              Пример: «ужин, курица, рис, брокколи, запечь в духовке».
            • /caloriecalculator — рассчёт КБЖУ и калорий под цель.
              Формат: пол, возраст, рост (см), вес (кг), образ жизни (пассивный/активный), шаги/день, тренировки/неделю, цель.
              Пример: «женщина, 30 лет, 165 см, 62 кг, пассивный, 4000 шагов, 2 тренировки, цель набрать массу/похудеть».
            • /productinfo — КБЖУ конкретного ингредиента.
              Пример: «свинина шея», «лосось сырой», «рис отварной», «моцарелла».
            • /start — открыть стартовое меню.

            Подсказки:
            • Можно писать одним сообщением и без формальностей — я пойму.
            • Для рецептов укажи ограничения (например, «без молочного», «до 30 минут», «мультиварка»).
            • Вернуться в меню — команда /start.

            Сайт: добавим позже.
        """.trimIndent()

        /** Путь к файлу в ресурсах: src/main/resources/welcome/start.jpg */
        private const val START_IMAGE_RES = "welcome/start.jpg"
    }

    suspend fun run() {
        require(api.getMe()) { "Telegram getMe failed" }
        var offset: Long? = null

        while (true) {
            try {
                val updates: List<TgUpdate> = api.getUpdates(offset)
                if (updates.isEmpty()) { delay(1200); continue }

                for (u in updates) {
                    offset = u.update_id + 1

                    val cb: TgCallbackQuery? = u.callback_query
                    if (cb != null) { handleCallback(cb); continue }

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

    private fun handleCallback(cb: TgCallbackQuery) {
        val chatId = cb.message?.chat?.id ?: return
        val msgId = cb.message.message_id

        when (cb.data) {
            CB_RECIPES -> {
                api.answerCallback(cb.id)
                api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                api.sendMessage(chatId, CHEF_INPUT_PROMPT)
            }
            CB_CALC -> {
                api.answerCallback(cb.id)
                api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.CALC
                state[chatId] = BotState.AWAITING_CALORIE_INPUT
                api.sendMessage(chatId, CALORIE_INPUT_PROMPT)
            }
            CB_PRODUCT -> {
                api.answerCallback(cb.id)
                api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.PRODUCT
                state[chatId] = BotState.AWAITING_PRODUCT_INPUT
                api.sendMessage(chatId, PRODUCT_INPUT_PROMPT)
            }
            CB_HELP -> {
                api.answerCallback(cb.id)
                api.deleteInlineKeyboard(chatId, msgId)
                api.sendMessage(chatId, HELP_TEXT)
            }
        }
    }

    private fun route(chatId: Long, text: String) {
        val lower = text.lowercase()

        when (state[chatId]) {
            BotState.AWAITING_CALORIE_INPUT ->
                if (!lower.startsWith("/")) { handleCalorieInput(chatId, text); return }
            BotState.AWAITING_PRODUCT_INPUT ->
                if (!lower.startsWith("/")) { handleProductInput(chatId, text); return }
            else -> {}
        }

        when (lower) {
            "/start" -> {
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                // Вместо текста — локальная картинка + подпись + инлайн-меню
                api.sendPhotoResource(
                    chatId = chatId,
                    resourcePath = START_IMAGE_RES,
                    caption = START_GREETING_RU,
                    replyMarkup = inlineMenu()
                )
            }
            "/recipes" -> {
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                api.sendMessage(chatId, CHEF_INPUT_PROMPT)
            }
            "/caloriecalculator" -> {
                mode[chatId] = PersonaMode.CALC
                state[chatId] = BotState.AWAITING_CALORIE_INPUT
                api.sendMessage(chatId, CALORIE_INPUT_PROMPT)
            }
            "/productinfo" -> {
                mode[chatId] = PersonaMode.PRODUCT
                state[chatId] = BotState.AWAITING_PRODUCT_INPUT
                api.sendMessage(chatId, PRODUCT_INPUT_PROMPT)
            }
            "/help" -> api.sendMessage(chatId, HELP_TEXT)
            else -> when (mode[chatId] ?: PersonaMode.CHEF) {
                PersonaMode.CHEF -> handleChef(chatId, text)
                PersonaMode.CALC -> handleCalorieInput(chatId, text)
                PersonaMode.PRODUCT -> handleProductInput(chatId, text)
            }
        }
    }

    private fun handleChef(chatId: Long, userText: String) {
        val sys = ChatMessage("system", PersonaPrompt.system())
        val user = ChatMessage("user", userText)
        val reply = llm.complete(listOf(sys, user))
        api.sendMessage(chatId, reply)
    }

    private fun handleCalorieInput(chatId: Long, userText: String) {
        val sys = ChatMessage("system", CalorieCalculatorPrompt.SYSTEM)
        val user = ChatMessage("user", "Данные пользователя: $userText")
        val reply = llm.complete(listOf(sys, user))
        api.sendMessage(chatId, reply)
        state.remove(chatId)
    }

    private fun handleProductInput(chatId: Long, userText: String) {
        val sys = ChatMessage("system", ProductInfoPrompt.SYSTEM)
        val user = ChatMessage("user", "Ингредиент: $userText")
        val reply = llm.complete(listOf(sys, user))
        api.sendMessage(chatId, reply)
        state.remove(chatId)
    }

    private fun inlineMenu(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inline_keyboard = listOf(
                listOf(InlineKeyboardButton("Рецепты", CB_RECIPES)),
                listOf(InlineKeyboardButton("Калькулятор калорий", CB_CALC)),
                listOf(InlineKeyboardButton("КБЖУ ингредиента", CB_PRODUCT)),
                listOf(InlineKeyboardButton("Помощь", CB_HELP))
            )
        )
}

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
import kotlin.text.trimIndent

/**
 * Меню показываем только на /start. После клика по кнопке:
 * answerCallbackQuery + editMessageReplyMarkup (убираем меню) + переключение роли.
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
            "Привет! Я шеф-повар-бот.\n\nВыбери режим ниже: рецепты, калькулятор калорий или КБЖУ ингредиента."

        private val CALORIE_INPUT_PROMPT ="""
                Пришли в одном сообщении: пол, возраст, рост (см), вес (кг), образ жизни (пассивный/активный), сколько шагов в день и сколько тренировок в неделю, цель (похудеть/набрать массу). 
             
                Пример: «мужчина, 40 лет, 175 см, 80 кг, активный, 9000 шагов, 4 тренировки в неделю, цель похудеть».
                Или вернись к /start для меню.
                """.trimIndent()

        private  val PRODUCT_INPUT_PROMPT ="""
            Пришли название ингредиента (можно с уточнением части/жирности и способа приготовления). 
            Примеры:«свинина шея», «лосось сырой», «куриная грудка без кожи», «рис отварной», «сыр моцарелла».
            Или вернись к /start для меню.""".trimIndent()

        private val CHEF_INPUT_PROMPT = """
            Напиши продукты и условия (прием пищи, техника, диета). 
            
            Например: «обед, курица, рис, брокколи, пароварка». 
            Также можно просто спросить «что приготовить из курицы и риса на обед? я на диете, худею.».
            
            Или вернись к /start для меню.
        """.trimIndent()
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
                    if (cb != null) {
                        println("CB <<< chat=${cb.message?.chat?.id} data=${cb.data}")
                        handleCallback(cb)
                        continue
                    }

                    val msg = u.message ?: u.edited_message
                    if (msg == null) continue
                    val text = msg.text?.trim().orEmpty()
                    if (text.isBlank()) continue

                    println("MSG <<< chat=${msg.chat.id} text=$text")
                    route(msg.chat.id, text)
                }
            } catch (t: Throwable) {
                println("POLLING-ERR: ${t.message}")
                delay(1500)
            }
        }
    }

    // ----- CALLBACKS -----
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
                api.sendMessage(
                    chatId,
                    "Помощь:\n/recipes — рецепты\n/caloriecalculator — калькулятор калорий\n/productinfo — КБЖУ ингредиента\n/start — показать меню."
                )
            }
            else -> {
                api.answerCallback(cb.id)
                api.deleteInlineKeyboard(chatId, msgId)
                api.sendMessage(chatId, "Неизвестная кнопка. Напишите /start для меню.")
            }
        }
    }

    // ----- TEXT ROUTER -----
    private fun route(chatId: Long, text: String) {
        val lower = text.lowercase()

        when (state[chatId]) {
            BotState.AWAITING_CALORIE_INPUT -> {
                if (!lower.startsWith("/")) { handleCalorieInput(chatId, text); return }
            }
            BotState.AWAITING_PRODUCT_INPUT -> {
                if (!lower.startsWith("/")) { handleProductInput(chatId, text); return }
            }
            else -> {}
        }

        when (lower) {
            "/start" -> {
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                api.sendMessage(chatId, START_GREETING_RU, replyMarkup = inlineMenu()) // меню только тут
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
            "/help" -> {
                api.sendMessage(
                    chatId,
                    "Помощь:\n/recipes — рецепты\n/caloriecalculator — калькулятор калорий\n/productinfo — КБЖУ ингредиента\n/start — показать меню."
                )
            }
            else -> {
                when (mode[chatId] ?: PersonaMode.CHEF) {
                    PersonaMode.CHEF -> handleChef(chatId, text)
                    PersonaMode.CALC -> handleCalorieInput(chatId, text)
                    PersonaMode.PRODUCT -> handleProductInput(chatId, text)
                }
            }
        }
    }

    // ----- Personas -----
    private fun handleChef(chatId: Long, userText: String) {
        val sys = ChatMessage("system", PersonaPrompt.system())
        val user = ChatMessage("user", userText)
        val reply = llm.complete(listOf(sys, user))
        api.sendMessage(chatId, reply)
    }

    private fun handleCalorieInput(chatId: Long, userText: String) {
        val system = ChatMessage("system", CalorieCalculatorPrompt.SYSTEM)
        val user = ChatMessage("user", "Данные пользователя: $userText")
        val reply = llm.complete(listOf(system, user))
        api.sendMessage(chatId, reply)
        state.remove(chatId)
    }

    private fun handleProductInput(chatId: Long, userText: String) {
        val system = ChatMessage("system", ProductInfoPrompt.SYSTEM)
        val user = ChatMessage("user", "Ингредиент: $userText")
        val reply = llm.complete(listOf(system, user))
        api.sendMessage(chatId, reply)
        state.remove(chatId)
    }

    // Инлайн-меню (только на /start)
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

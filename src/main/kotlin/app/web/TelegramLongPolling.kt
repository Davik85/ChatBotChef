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
import app.web.dto.* // для TgUser/TgMessage (from, reply_to_message)
import kotlinx.coroutines.delay
import app.db.PremiumRepo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Меню показываем только на /start. После клика меню/баннер удаляем.
 * Теперь также удаляем и само командное сообщение /start.
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

        private val START_GREETING_RU = """
            Привет-привет! 👋 Меня зовут Шеф-Повар-Бот, и я готов стать вашим надежным помощником на кухне!
            Какие инструменты вам нужны сегодня?📌
            
            Режимы:
            
            ✨ Рецепты — создаем уникальные блюда из ваших любимых ингредиентов.
            🔍 Калькулятор калорий — рассчитываем вашу норму КБЖУ индивидуально.
            🥗 КБЖУ ингредиента — получаем подробную информацию о составе любого продукта.
        """.trimIndent()

        private val CALORIE_INPUT_PROMPT = """
            🧮 Чтобы рассчитать вашу индивидуальную норму питания, отправьте мне одно сообщение с такими данными:
            
            Ваш пол, возраст, рост (см), вес (кг), образ жизни (пассивный/активный), количество шагов в день и тренировок в неделю, цель (похудеть/набрать массу).
            
            Пример:«Мужчина, 40 лет, 175 см, 80 кг, активный, 9000 шагов, 4 тренировки в неделю, цель похудеть».
            
            ❤️ Вернуться к главному меню можно командой /start.

        """.trimIndent()

        private val PRODUCT_INPUT_PROMPT = """
            🍰 Напишите название интересующего вас ингредиента.Можно добавить подробности: часть, жирность, способ приготовления.
           
            Примеры:
           
            — свинина шея
           
            — лосось сырой
           
            — куриная грудка без кожи
          
            — рис отварной
           
            — сыр моцарелла
           
            ☝Хотите вернуться к главному меню? Просто введите команду /start!
        """.trimIndent()

        private val CHEF_INPUT_PROMPT = """
            Укажите продукты и дополнительные условия приготовления:
            
            Например: «Обед, курица, рис, брокколи, пароварка»
            Или спросите прямо: «Что приготовить из курицы и риса на обед? Я на диете, худею.»
           
            💬  Если хотите вернуться к основному меню, нажмите /start.
        """.trimIndent()

        private val HELP_TEXT = """
            💡 Привет! Хочешь узнать обо всём, что я умею?Здесь я расскажу, какие фишки спрятаны внутри меня и как ими пользоваться. Всё предельно просто и быстро!

            Доступные команды:
            
            ✨ /recipes — Подберу идеальный рецепт
           
            Пришли список продуктов и предпочтения (приём пищи, технику приготовления, диету).Пример: ужин, курица, рис, брокколи, запечь в духовке.
            
            ⚖️ /caloriecalculator — Рассчитаю персональную норму КБЖУ и калорий
            
            Отправь мне данные: пол, возраст, рост, вес, активность, шаги в день, тренировки в неделю и цель (похудеть/набрать массу).Пример: женщина, 30 лет, 165 см, 62 кг, пассивный, 4000 шагов, 2 тренировки, цель набрать массу.
           
            🧂 /productinfo — Узнай КБЖУ любого продукта
           
            Сообщи название ингредиента с деталями (часть, жирность, способ приготовления). Пример: свинина шея, лосось сырой, рис отварной, моцарелла.
          
            🎯 /start — Открою стартовое меню
            
            Полезные советы:
            -	Можно отправлять запросы свободно, без строгого формата — разберусь сам!
            -	Ограничения для рецепта напиши сразу («без молочного», «быстро», «мультиварка»).
            -	Главное меню доступно по команде /start.
            Приятного аппетита и удачных экспериментов! 😊
            
            Сайт проекта:
        """.trimIndent()

        /** Путь к файлу: src/main/resources/welcome/start.jpg */
        private const val START_IMAGE_RES = "welcome/start.jpg"

        /** Список админов (через запятую) из ENV: ADMIN_IDS=123,456 */
        private val ADMIN_IDS: Set<Long> =
            (System.getenv("ADMIN_IDS") ?: "")
                .split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .toSet()

        private val dtf: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }

    suspend fun run() {
        require(api.getMe()) { "Telegram getMe failed" }
        var offset: Long? = null

        while (true) {
            try {
                val updates: List<TgUpdate> = api.getUpdates(offset)
                if (updates.isEmpty()) {
                    delay(1200); continue
                }

                for (u in updates) {
                    offset = u.update_id + 1

                    val cb: TgCallbackQuery? = u.callback_query
                    if (cb != null) {
                        handleCallback(cb); continue
                    }

                    val msg = u.message ?: u.edited_message ?: continue
                    val text = msg.text?.trim().orEmpty()
                    if (text.isBlank()) continue

                    route(msg)
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
                val deleted = api.deleteMessage(chatId, msgId)
                if (!deleted) api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                api.sendMessage(chatId, CHEF_INPUT_PROMPT)
            }

            CB_CALC -> {
                api.answerCallback(cb.id)
                val deleted = api.deleteMessage(chatId, msgId)
                if (!deleted) api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.CALC
                state[chatId] = BotState.AWAITING_CALORIE_INPUT
                api.sendMessage(chatId, CALORIE_INPUT_PROMPT)
            }

            CB_PRODUCT -> {
                api.answerCallback(cb.id)
                val deleted = api.deleteMessage(chatId, msgId)
                if (!deleted) api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.PRODUCT
                state[chatId] = BotState.AWAITING_PRODUCT_INPUT
                api.sendMessage(chatId, PRODUCT_INPUT_PROMPT)
            }

            CB_HELP -> {
                api.answerCallback(cb.id)
                val deleted = api.deleteMessage(chatId, msgId)
                if (!deleted) api.deleteInlineKeyboard(chatId, msgId)
                api.sendMessage(chatId, HELP_TEXT)
            }
        }
    }

    // роутер: принимаем весь TgMessage, чтобы видеть from/reply_to_message
    private fun route(msg: TgMessage) {
        val chatId = msg.chat.id
        val msgId = msg.message_id
        val fromId = msg.from?.id
        val lower = msg.text?.lowercase().orEmpty()

        // ===== Админ-проверка статуса премиума =====
        if (lower.startsWith("/premiumstatus")) {
            if (fromId !in ADMIN_IDS) {
                api.sendMessage(chatId, "Недостаточно прав.")
                return
            }
            val parts = msg.text!!.trim().split(Regex("\\s+"))
            val targetId: Long? = when {
                // По reply: /premiumstatus
                msg.reply_to_message != null && parts.size == 1 -> msg.reply_to_message.from?.id
                // По userId: /premiumstatus <userId>
                parts.size >= 2 -> parts[1].toLongOrNull()
                else -> null
            }
            if (targetId == null) {
                api.sendMessage(chatId, "Форматы:\n— по reply: /premiumstatus\n— напрямую: /premiumstatus <userId>")
                return
            }
            val until = PremiumRepo.getUntil(targetId)
            val now = System.currentTimeMillis()
            if (until == null || until <= now) {
                api.sendMessage(chatId, "Статус пользователя $targetId: премиум не активен.")
                return
            }
            val remainingMs = until - now
            val days = remainingMs / (24 * 60 * 60 * 1000)
            val hours = (remainingMs % (24 * 60 * 60 * 1000)) / (60 * 60 * 1000)
            val untilStr = dtf.format(Instant.ofEpochMilli(until))
            api.sendMessage(chatId, "Статус пользователя $targetId: активен до $untilStr (осталось: $days дн $hours ч).")
            return
        }

        // ===== Остальные админ-команды (grant/whoami) =====
        if (lower.startsWith("/whoami")) {
            api.sendMessage(chatId, "Ваш Telegram ID: ${fromId ?: "неизвестен"}")
            return
        }

        if (lower.startsWith("/grantpremium")) {
            if (fromId !in ADMIN_IDS) {
                api.sendMessage(chatId, "Недостаточно прав.")
                return
            }
            val parts = msg.text!!.trim().split(Regex("\\s+"))
            when {
                // Вариант 1: по reply — /grantpremium <days>
                msg.reply_to_message != null && parts.size == 2 -> {
                    val days = parts[1].toIntOrNull()
                    val target = msg.reply_to_message.from?.id
                    if (days == null || days <= 0 || target == null) {
                        api.sendMessage(chatId, "Формат (по reply): /grantpremium <days>")
                        return
                    }
                    PremiumRepo.grantDays(target, days)
                    val until = PremiumRepo.getUntil(target)
                    val untilStr = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
                    api.sendMessage(chatId, "Ок. Пользователь $target получил премиум на $days дн. До: $untilStr")
                }

                // Вариант 2: напрямую — /grantpremium <userId> <days>
                parts.size >= 3 -> {
                    val target = parts[1].toLongOrNull()
                    val days = parts[2].toIntOrNull()
                    if (target == null || days == null || days <= 0) {
                        api.sendMessage(chatId, "Формат: /grantpremium <userId> <days>")
                        return
                    }
                    PremiumRepo.grantDays(target, days)
                    val until = PremiumRepo.getUntil(target)
                    val untilStr = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
                    api.sendMessage(chatId, "Ок. Пользователь $target получил премиум на $days дн. До: $untilStr")
                }

                else -> {
                    api.sendMessage(chatId, "Форматы:\n— по reply: /grantpremium <days>\n— напрямую: /grantpremium <userId> <days>")
                }
            }
            return
        }

        // ===== Ожидание данных в режимах =====
        when (state[chatId]) {
            BotState.AWAITING_CALORIE_INPUT ->
                if (!lower.startsWith("/")) {
                    handleCalorieInput(chatId, msg.text ?: ""); return
                }

            BotState.AWAITING_PRODUCT_INPUT ->
                if (!lower.startsWith("/")) {
                    handleProductInput(chatId, msg.text ?: ""); return
                }

            else -> {}
        }

        // ===== Обычные команды =====
        when (lower) {
            "/start" -> {
                api.deleteMessage(chatId, msgId)
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
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
                PersonaMode.CHEF -> handleChef(chatId, msg.text ?: "")
                PersonaMode.CALC -> handleCalorieInput(chatId, msg.text ?: "")
                PersonaMode.PRODUCT -> handleProductInput(chatId, msg.text ?: "")
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

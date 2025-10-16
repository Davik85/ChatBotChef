package app.web

import app.AppConfig
import app.llm.OpenAIClient
import app.llm.dto.ChatMessage
import app.logic.PersonaPrompt
import app.logic.CalorieCalculatorPrompt
import app.logic.ProductInfoPrompt
import app.pay.PaymentService
import app.pay.ReceiptBuilder
import app.web.dto.InlineKeyboardButton
import app.web.dto.InlineKeyboardMarkup
import app.web.dto.TgCallbackQuery
import app.web.dto.TgUpdate
import app.web.dto.*
import kotlinx.coroutines.delay
import app.db.PremiumRepo
import app.logic.RateLimiter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
        private const val CB_PAY_NOW = "menu_pay_now"

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
            ⚖️ /caloriecalculator — Рассчитаю персональную норму КБЖУ и калорий
            🧂 /productinfo — Узнай КБЖУ любого продукта
            🎯 /start — Открою стартовое меню

            Сайт проекта:
        """.trimIndent()

        private const val START_IMAGE_RES = "welcome/start.jpg"

        private val ADMIN_IDS: Set<Long> =
            (System.getenv("ADMIN_IDS") ?: "")
                .split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()

        private val dtf: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS
        private const val REMINDER_PERIOD_MS = HOUR_MS
    }

    private fun isAdmin(userId: Long): Boolean = ADMIN_IDS.contains(userId)

    private var lastReminderCheck = 0L

    suspend fun run() {
        require(api.getMe()) { "Telegram getMe failed" }
        var offset: Long? = null

        while (true) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastReminderCheck >= REMINDER_PERIOD_MS) {
                    tickRenewalReminders(now)
                    lastReminderCheck = now
                }

                val updates: List<TgUpdate> = api.getUpdates(offset)
                if (updates.isEmpty()) { delay(1200); continue }

                for (u in updates) {
                    offset = u.update_id + 1

                    val pcq = u.pre_checkout_query
                    if (pcq != null) {
                        handlePreCheckout(pcq); continue
                    }

                    val cb: TgCallbackQuery? = u.callback_query
                    if (cb != null) {
                        handleCallback(cb); continue
                    }

                    val msg = u.message ?: u.edited_message ?: continue

                    val sp = msg.successful_payment
                    if (sp != null) {
                        handleSuccessfulPayment(msg); continue
                    }

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

    // ===== Payments =====

    private fun handlePreCheckout(q: TgPreCheckoutQuery) {
        val validation = PaymentService.validatePreCheckout(q)
        if (!validation.ok) {
            api.answerPreCheckoutQuery(q.id, ok = false, errorMessage = validation.errorMessage)
            return
        }
        api.answerPreCheckoutQuery(q.id, ok = true)
    }

    private fun handleSuccessfulPayment(msg: TgMessage) {
        val chatId = msg.chat.id
        val payment = msg.successful_payment ?: return
        val recorded = PaymentService.handleSuccessfulPayment(chatId, payment)
        if (!recorded) {
            api.sendMessage(chatId, "Мы получили уведомление об оплате, но не смогли подтвердить её автоматически. Поддержка уже уведомлена.")
            return
        }
        PremiumRepo.grantDays(chatId, AppConfig.premiumDays)
        val until = PremiumRepo.getUntil(chatId)
        val untilStr = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
        api.sendMessage(chatId, "Оплата принята. Премиум активен до: $untilStr")
    }

    private fun sendTelegramInvoice(chatId: Long): Boolean {
        if (!PaymentService.paymentsAvailable) {
            api.sendMessage(chatId, PaymentService.paymentsDisabledMessage)
            return false
        }
        val providerToken = AppConfig.providerToken
        if (providerToken.isNullOrBlank()) {
            api.sendMessage(chatId, PaymentService.paymentsDisabledMessage)
            return false
        }
        val title = "Премиум-доступ на ${AppConfig.premiumDays} дней"
        val desc = "Доступ ко всем функциям бота без ограничений."
        val prices = listOf(TgLabeledPrice(label = "Подписка", amount = AppConfig.premiumPriceRub * 100))
        val payload = PaymentService.newInvoicePayload(chatId)
        PaymentService.registerInvoice(payload, chatId)
        val providerData = ReceiptBuilder.providerDataForSubscription(AppConfig.premiumPriceRub, title)
        val sent = api.sendInvoice(
            chatId = chatId,
            title = title,
            description = desc,
            payload = payload,
            providerToken = providerToken,
            currency = "RUB",
            prices = prices,
            needEmail = AppConfig.requireEmailForReceipt,
            needPhone = AppConfig.requirePhoneForReceipt,
            sendEmailToProvider = AppConfig.requireEmailForReceipt,
            sendPhoneToProvider = AppConfig.requirePhoneForReceipt,
            providerData = providerData
        )
        if (!sent) {
            PaymentService.markInvoiceFailure(payload, "send_invoice_failed")
        }
        return sent
    }

    // ===== Callback =====

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
            CB_PAY_NOW -> {
                api.answerCallback(cb.id)
                api.deleteMessage(chatId, msgId)
                val ok = sendTelegramInvoice(chatId)
                if (!ok && PaymentService.paymentsAvailable) api.sendMessage(chatId, "Не удалось отправить счёт. Попробуйте позже.")
            }
        }
    }

    // ===== Router =====

    private fun route(msg: TgMessage) {
        val chatId = msg.chat.id
        val msgId = msg.message_id
        val userId = msg.from?.id ?: chatId
        val lower = msg.text?.lowercase().orEmpty()

        // ADMIN
        if (lower.startsWith("/premiumstatus")) {
            if (!isAdmin(userId)) { api.sendMessage(chatId, "Команда доступна только админам."); return }
            val parts = lower.split(" ").filter { it.isNotBlank() }
            val target = parts.getOrNull(1)?.toLongOrNull() ?: userId
            val until = PremiumRepo.getUntil(target)
            val untilStr = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "нет подписки"
            api.sendMessage(chatId, "Premium для $target: $untilStr")
            return
        }
        if (lower.startsWith("/grantpremium")) {
            if (!isAdmin(userId)) { api.sendMessage(chatId, "Команда доступна только админам."); return }
            val parts = lower.split(" ").filter { it.isNotBlank() }
            val target = parts.getOrNull(1)?.toLongOrNull()
            val days = parts.getOrNull(2)?.toIntOrNull()
            if (target == null || days == null || days <= 0) {
                api.sendMessage(chatId, "Использование: /grantpremium <tgId> <days>"); return
            }
            PremiumRepo.grantDays(target, days)
            val until = PremiumRepo.getUntil(target)
            val untilStr = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
            api.sendMessage(chatId, "Выдан премиум пользователю $target на $days дн. До: $untilStr")
            return
        }

        // PUBLIC
        if (lower.startsWith("/whoami")) {
            api.sendMessage(chatId, "Ваш Telegram ID: $userId"); return
        }
        if (lower.startsWith("/premium")) {
            val ok = sendTelegramInvoice(chatId)
            if (!ok && PaymentService.paymentsAvailable) api.sendMessage(chatId, "Не удалось отправить счёт. Попробуйте позже.")
            return
        }

        when (state[chatId]) {
            BotState.AWAITING_CALORIE_INPUT ->
                if (!lower.startsWith("/")) { handleCalorieInput(chatId, msg.text ?: ""); return }
            BotState.AWAITING_PRODUCT_INPUT ->
                if (!lower.startsWith("/")) { handleProductInput(chatId, msg.text ?: ""); return }
            else -> {}
        }

        when (lower) {
            "/start" -> {
                api.deleteMessage(chatId, msgId)
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                api.sendPhotoResource(chatId, START_IMAGE_RES, START_GREETING_RU, inlineMenu())
                return
            }
            "/recipes" -> { mode[chatId] = PersonaMode.CHEF; state.remove(chatId); api.sendMessage(chatId, CHEF_INPUT_PROMPT); return }
            "/caloriecalculator" -> { mode[chatId] = PersonaMode.CALC; state[chatId] = BotState.AWAITING_CALORIE_INPUT; api.sendMessage(chatId, CALORIE_INPUT_PROMPT); return }
            "/productinfo" -> { mode[chatId] = PersonaMode.PRODUCT; state[chatId] = BotState.AWAITING_PRODUCT_INPUT; api.sendMessage(chatId, PRODUCT_INPUT_PROMPT); return }
            "/help" -> { api.sendMessage(chatId, HELP_TEXT); return }
        }

        when (mode[chatId] ?: PersonaMode.CHEF) {
            PersonaMode.CHEF    -> handleChef(chatId, msg.text ?: "")
            PersonaMode.CALC    -> handleCalorieInput(chatId, msg.text ?: "")
            PersonaMode.PRODUCT -> handleProductInput(chatId, msg.text ?: "")
        }
    }

    // ===== LLM =====

    private fun handleChef(chatId: Long, userText: String) {
        if (!isAdmin(chatId) && !RateLimiter.checkAndConsume(chatId)) { sendPaywall(chatId); return }
        val sys = ChatMessage("system", PersonaPrompt.system())
        val user = ChatMessage("user", userText)
        val reply = llm.complete(listOf(sys, user))
        api.sendMessage(chatId, reply)
    }

    private fun handleCalorieInput(chatId: Long, userText: String) {
        if (!isAdmin(chatId) && !RateLimiter.checkAndConsume(chatId)) { sendPaywall(chatId); state.remove(chatId); return }
        val sys = ChatMessage("system", CalorieCalculatorPrompt.SYSTEM)
        val user = ChatMessage("user", "Данные пользователя: $userText")
        val reply = llm.complete(listOf(sys, user))
        api.sendMessage(chatId, reply)
        state.remove(chatId)
    }

    private fun handleProductInput(chatId: Long, userText: String) {
        if (!isAdmin(chatId) && !RateLimiter.checkAndConsume(chatId)) { sendPaywall(chatId); state.remove(chatId); return }
        val sys = ChatMessage("system", ProductInfoPrompt.SYSTEM)
        val user = ChatMessage("user", "Ингредиент: $userText")
        val reply = llm.complete(listOf(sys, user))
        api.sendMessage(chatId, reply)
        state.remove(chatId)
    }

    private fun sendPaywall(chatId: Long) {
        if (!PaymentService.paymentsAvailable) {
            api.sendMessage(chatId, PaymentService.paymentsDisabledMessage)
            return
        }
        val kb = InlineKeyboardMarkup(
            inline_keyboard = listOf(
                listOf(InlineKeyboardButton("Оплатить в Telegram", CB_PAY_NOW))
            )
        )
        api.sendMessage(chatId, AppConfig.PAYWALL_TEXT, replyMarkup = kb)
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

    // ===== напоминания о продлении =====
    private fun tickRenewalReminders(now: Long) {
        // Здесь предполагается реализация PremiumRepo.listExpiringBetween(...) и markReminded(...)
        // Если у тебя уже сделано — будет работать. Иначе можно отключить вызов tickRenewalReminders().
        val hour = HOUR_MS
        fun window(center: Long) = center - hour to center + hour

        // 3 дня
        kotlin.run {
            val (from, to) = window(now + 3 * DAY_MS)
            PremiumRepo.listExpiringBetween(from, to, "3d").forEach { id ->
                PremiumRepo.markReminded(id, "3d")
                sendReminder(id, "Подписка истекает через 3 дня. Продлите, чтобы не потерять доступ.")
            }
        }
        // 1 день
        kotlin.run {
            val (from, to) = window(now + DAY_MS)
            PremiumRepo.listExpiringBetween(from, to, "1d").forEach { id ->
                PremiumRepo.markReminded(id, "1d")
                sendReminder(id, "Подписка истекает завтра. Продлите подписку, доступ сохранится без перерывов.")
            }
        }
        // Сегодня
        kotlin.run {
            val from = now
            val to = now + DAY_MS
            PremiumRepo.listExpiringBetween(from, to, "0d").forEach { id ->
                PremiumRepo.markReminded(id, "0d")
                sendReminder(id, "Подписка заканчивается сегодня. Нажмите, чтобы продлить в Telegram.")
            }
        }
    }

    private fun sendReminder(userId: Long, text: String) {
        if (!PaymentService.paymentsAvailable) {
            api.sendMessage(userId, PaymentService.paymentsDisabledMessage)
            return
        }
        val kb = InlineKeyboardMarkup(
            inline_keyboard = listOf(
                listOf(InlineKeyboardButton("Оплатить в Telegram", CB_PAY_NOW))
            )
        )
        api.sendMessage(userId, text, replyMarkup = kb)
    }
}

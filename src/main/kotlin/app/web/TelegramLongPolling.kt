package app.web

import app.AppConfig
import app.db.ChatHistoryRepo
import app.db.MessagesRepo
import app.db.PremiumRepo
import app.db.UsersRepo
import app.llm.OpenAIClient
import app.llm.dto.ChatMessage
import app.logic.CalorieCalculatorPrompt
import app.logic.PersonaPrompt
import app.logic.ProductInfoPrompt
import app.pay.PaymentService
import app.pay.ReceiptBuilder
import app.notify.ReminderJob
import app.web.dto.InlineKeyboardButton
import app.web.dto.InlineKeyboardMarkup
import app.web.dto.TgCallbackQuery
import app.web.dto.TgUpdate
import app.web.dto.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.logic.RateLimiter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlin.jvm.Volatile
import kotlin.math.max

class TelegramLongPolling(
    private val token: String,
    private val llm: OpenAIClient
) {
    private val api = TelegramApi(token)

    private val mode = mutableMapOf<Long, PersonaMode>()
    private val state = mutableMapOf<Long, BotState>()
    private val adminStates = mutableMapOf<Long, AdminState>()
    @Volatile
    private var broadcastJob: Job? = null
    private val broadcastMutex = Any()

    private enum class PersonaMode { CHEF, CALC, PRODUCT }
    private enum class BotState { AWAITING_CALORIE_INPUT, AWAITING_PRODUCT_INPUT }

    private sealed class AdminState {
        object AwaitingBroadcastText : AdminState()
        data class AwaitingConfirmation(val text: String) : AdminState()
        object AwaitingUserIdForStatus : AdminState()
        object AwaitingGrantParams : AdminState()
    }

    private enum class BroadcastResult { SENT, BLOCKED, FAILED }

    private companion object {
        private const val CB_RECIPES = "menu_recipes"
        private const val CB_CALC = "menu_calc"
        private const val CB_PRODUCT = "menu_product"
        private const val CB_HELP = "menu_help"
        private const val CB_ADMIN_BROADCAST = "admin_broadcast"
        private const val CB_ADMIN_STATS = "admin_stats"
        private const val CB_ADMIN_CONFIRM = "admin_broadcast_confirm"
        private const val CB_ADMIN_CANCEL = "admin_broadcast_cancel"
        private const val CB_ADMIN_USER_STATUS = "admin_user_status"
        private const val CB_ADMIN_GRANT = "admin_grant"
        private const val ADMIN_GRANT_USAGE = "Использование: <tgId> <days> (оба числа). Пример: 6859850730 30"
        private const val MAX_BROADCAST_CHARS = 2000
        private const val ACTIVITY_MAX_CHARS = 3800
        private const val BROADCAST_RATE_DELAY_MS = 40L
        private val NON_TEXT_REPLY = "Я работаю только с текстом: просто опиши, что хочешь приготовить ✍️"

        private val START_GREETING_RU = """
            Привет-привет! 👋 Я Шеф-Повар-Бот, и я готов стать вашим надежным помощником на кухне!
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
            💡 Привет! Здесь я расскажу, какие фишки спрятаны внутри меня и как ими пользоваться. Всё предельно просто и быстро!

            Доступные команды:
            ✨ /recipes — Подберу идеальный рецепт
            ⚖️ /caloriecalculator — Рассчитаю персональную норму КБЖУ и калорий
            🧂 /productinfo — Узнай КБЖУ любого продукта
            🎯 /start — Открою стартовое меню
            🔄 /reset — Очистить контекст текущего режима
            
            В случае вопросов или проблем с оплатой, пожалуйста, свяжитесь с нашей поддержкой по почте. 
            Отправьте чек, свой ID (введите команду /whoami в бот) и краткое описание проблемы.

            Сайт проекта: http://ai-chef.tilda.ws/
            Политика конфиденциальности: https://ai-chef.tilda.ws/policy
            Оферта: https://ai-chef.tilda.ws/oferta
            Почта поддержки: ai.chef@yandex.ru
        """.trimIndent()

        private const val START_IMAGE_RES = "welcome/start.jpg"

        private val ADMIN_IDS: Set<Long> =
            (System.getenv("ADMIN_IDS") ?: "")
                .split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()

        private val dtf: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

        private val CTRL_REGEX = Regex("[\\u0000-\\u001F\\u007F\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]")
    }

    private fun isAdmin(userId: Long): Boolean = ADMIN_IDS.contains(userId)

    private fun PersonaMode.modeKey(): String = when (this) {
        PersonaMode.CHEF -> "CHEF"
        PersonaMode.CALC -> "CALC"
        PersonaMode.PRODUCT -> "PRODUCT"
    }

    private fun currentMode(chatId: Long): PersonaMode = mode[chatId] ?: PersonaMode.CHEF

    private fun prepareForHistory(raw: String): String {
        if (raw.isEmpty()) return raw
        var value = raw.replace("\r", " ").replace("\n", " ")
        value = CTRL_REGEX.replace(value, "")
        if (value.length > AppConfig.HISTORY_MAX_CHARS_PER_MSG) {
            value = value.take(AppConfig.HISTORY_MAX_CHARS_PER_MSG)
        }
        return value.trim()
    }

    private fun sanitizeActivityText(raw: String): String? {
        var value = raw.replace("\r", " ").replace("\n", " ")
        value = CTRL_REGEX.replace(value, "")
        value = value.trim()
        if (value.isEmpty()) return null
        if (value.length > ACTIVITY_MAX_CHARS) {
            value = value.take(ACTIVITY_MAX_CHARS)
        }
        return value
    }

    private fun sanitizeAdminInput(raw: String, maxChars: Int = MAX_BROADCAST_CHARS): String {
        var value = raw.replace("\r", " ").replace("\n", " ")
        value = CTRL_REGEX.replace(value, "")
        if (value.length > maxChars) {
            value = value.take(maxChars)
        }
        return value.trim()
    }

    private fun buildHistoryMessages(userId: Long, personaMode: PersonaMode): List<ChatMessage> {
        val items = ChatHistoryRepo.fetchLast(userId, personaMode.modeKey(), AppConfig.HISTORY_MAX_TURNS)
        if (items.isEmpty()) return emptyList()

        val deque = ArrayDeque<Pair<ChatMessage, Int>>()
        var total = 0
        for (item in items) {
            val sanitized = prepareForHistory(item.text)
            if (sanitized.isEmpty()) continue
            val message = ChatMessage(item.role, sanitized)
            deque.addLast(message to sanitized.length)
            total += sanitized.length
            while (total > AppConfig.HISTORY_MAX_TOTAL_CHARS && deque.isNotEmpty()) {
                val removed = deque.removeFirst()
                total -= removed.second
            }
        }
        return deque.map { it.first }
    }

    private fun clearAllHistory(userId: Long) {
        PersonaMode.values().forEach { persona ->
            ChatHistoryRepo.clear(userId, persona.modeKey())
        }
    }

    suspend fun run() {
        require(api.getMe()) { "Telegram getMe failed" }
        GlobalScope.launch { ReminderJob(api).runForever() }
        var offset: Long? = null

        while (true) {
            try {
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
        trackUserActivity(q.from.id, "[pre_checkout] ${q.invoice_payload ?: ""}")
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
        val payerId = msg.from?.id ?: chatId
        val paymentId = payment.provider_payment_charge_id ?: payment.telegram_payment_charge_id ?: ""
        trackUserActivity(payerId, "[payment_success] $paymentId", role = "system")
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
        val userId = cb.from.id

        trackUserActivity(userId, "[callback] ${cb.data.orEmpty()}")

        when (cb.data) {
            CB_RECIPES -> {
                api.answerCallback(cb.id)
                val deleted = api.deleteMessage(chatId, msgId)
                if (!deleted) api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                ChatHistoryRepo.clear(userId, PersonaMode.CHEF.modeKey())
                api.sendMessage(chatId, CHEF_INPUT_PROMPT)
            }
            CB_CALC -> {
                api.answerCallback(cb.id)
                val deleted = api.deleteMessage(chatId, msgId)
                if (!deleted) api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.CALC
                state[chatId] = BotState.AWAITING_CALORIE_INPUT
                ChatHistoryRepo.clear(userId, PersonaMode.CALC.modeKey())
                api.sendMessage(chatId, CALORIE_INPUT_PROMPT)
            }
            CB_PRODUCT -> {
                api.answerCallback(cb.id)
                val deleted = api.deleteMessage(chatId, msgId)
                if (!deleted) api.deleteInlineKeyboard(chatId, msgId)
                mode[chatId] = PersonaMode.PRODUCT
                state[chatId] = BotState.AWAITING_PRODUCT_INPUT
                ChatHistoryRepo.clear(userId, PersonaMode.PRODUCT.modeKey())
                api.sendMessage(chatId, PRODUCT_INPUT_PROMPT)
            }
            CB_HELP -> {
                api.answerCallback(cb.id)
                val deleted = api.deleteMessage(chatId, msgId)
                if (!deleted) api.deleteInlineKeyboard(chatId, msgId)
                api.sendMessage(chatId, HELP_TEXT)
            }
            CB_ADMIN_BROADCAST -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                println("ADMIN: select broadcast chat=$chatId user=$userId")
                adminStates[chatId] = AdminState.AwaitingBroadcastText
                api.sendMessage(chatId, "Пришлите текст рассылки одним сообщением.", parseMode = null)
            }
            CB_ADMIN_STATS -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                println("ADMIN: select stats chat=$chatId user=$userId")
                sendAdminStats(chatId)
            }
            CB_ADMIN_USER_STATUS -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                println("ADMIN: select user_status chat=$chatId user=$userId")
                adminStates[chatId] = AdminState.AwaitingUserIdForStatus
                api.sendMessage(chatId, "Введите числовой Telegram ID пользователя.", parseMode = null)
            }
            CB_ADMIN_GRANT -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                println("ADMIN: select grant chat=$chatId user=$userId")
                adminStates[chatId] = AdminState.AwaitingGrantParams
                api.sendMessage(
                    chatId,
                    "Введите данные в формате: <tgId> <days>. Пример: 6859850730 30",
                    parseMode = null
                )
            }
            CB_ADMIN_CONFIRM -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                val prepared = adminStates[chatId]
                if (prepared is AdminState.AwaitingConfirmation) {
                    api.deleteInlineKeyboard(chatId, msgId)
                    startBroadcast(chatId, prepared.text)
                } else {
                    api.sendMessage(chatId, "Нет подготовленной рассылки.", parseMode = null)
                }
            }
            CB_ADMIN_CANCEL -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                api.deleteInlineKeyboard(chatId, msgId)
                if (adminStates.remove(chatId) != null) {
                    api.sendMessage(chatId, "Рассылка отменена.", parseMode = null)
                } else {
                    api.sendMessage(chatId, "Нет подготовленной рассылки.", parseMode = null)
                }
            }
            else -> api.answerCallback(cb.id)
        }
    }

    // ===== Router =====

    private fun route(msg: TgMessage) {
        val chatId = msg.chat.id
        val msgId = msg.message_id
        val userId = msg.from?.id ?: chatId

        val logContent = messageContentForLog(msg)
        trackUserActivity(userId, logContent)

        val hasAttachments = (msg.photo?.isNotEmpty() == true) ||
            msg.document != null ||
            msg.video != null ||
            msg.video_note != null ||
            msg.voice != null ||
            msg.audio != null ||
            msg.sticker != null ||
            msg.animation != null
        if (hasAttachments) {
            api.sendMessage(chatId, NON_TEXT_REPLY)
            return
        }

        val originalText = msg.text ?: ""
        if (originalText.isBlank()) {
            api.sendMessage(chatId, NON_TEXT_REPLY)
            return
        }
        val text = originalText.trim()
        val lower = text.lowercase()

        if (lower.startsWith("/admin")) {
            if (!isAdmin(userId)) {
                api.sendMessage(chatId, "Команда доступна только админам.")
            } else {
                println("ADMIN: command /admin chat=$chatId user=$userId")
                showAdminMenu(chatId)
            }
            return
        }

        val adminState = adminStates[chatId]
        if (isAdmin(userId)) {
            when (adminState) {
                AdminState.AwaitingBroadcastText -> {
                    when {
                        lower == "/cancel" -> {
                            adminStates.remove(chatId)
                            api.sendMessage(chatId, "Рассылка отменена.", parseMode = null)
                        }
                        else -> {
                            val prepared = validateBroadcastText(chatId, originalText)
                            if (prepared != null) {
                                adminStates[chatId] = AdminState.AwaitingConfirmation(prepared)
                                api.sendMessage(
                                    chatId,
                                    "Отправить рассылку всем пользователям?\n\n$prepared",
                                    replyMarkup = broadcastConfirmKeyboard(),
                                    parseMode = null
                                )
                            }
                        }
                    }
                    return
                }
                is AdminState.AwaitingConfirmation -> {
                    when {
                        lower == "/cancel" -> {
                            adminStates.remove(chatId)
                            api.sendMessage(chatId, "Рассылка отменена.", parseMode = null)
                        }
                        else -> {
                            val prepared = validateBroadcastText(chatId, originalText)
                            if (prepared != null) {
                                adminStates[chatId] = AdminState.AwaitingConfirmation(prepared)
                                api.sendMessage(
                                    chatId,
                                    "Отправить рассылку всем пользователям?\n\n$prepared",
                                    replyMarkup = broadcastConfirmKeyboard(),
                                    parseMode = null
                                )
                            }
                        }
                    }
                    return
                }
                AdminState.AwaitingUserIdForStatus -> {
                    if (lower == "/cancel") {
                        adminStates.remove(chatId)
                        api.sendMessage(chatId, "Операция отменена.", parseMode = null)
                    } else {
                        processAdminStatusInput(chatId, userId, originalText)
                    }
                    return
                }
                AdminState.AwaitingGrantParams -> {
                    if (lower == "/cancel") {
                        adminStates.remove(chatId)
                        api.sendMessage(chatId, "Операция отменена.", parseMode = null)
                    } else {
                        processAdminGrantInput(chatId, userId, originalText)
                    }
                    return
                }
                null -> {}
            }
        } else if (adminState != null) {
            adminStates.remove(chatId)
        }

        if (text.isBlank()) return

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
                if (!lower.startsWith("/")) { handleCalorieInput(chatId, userId, text); return }
            BotState.AWAITING_PRODUCT_INPUT ->
                if (!lower.startsWith("/")) { handleProductInput(chatId, userId, text); return }
            else -> {}
        }

        when (lower) {
            "/start" -> {
                api.deleteMessage(chatId, msgId)
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                clearAllHistory(userId)
                api.sendPhotoResource(chatId, START_IMAGE_RES, START_GREETING_RU, inlineMenu())
                return
            }
            "/recipes" -> {
                mode[chatId] = PersonaMode.CHEF
                state.remove(chatId)
                ChatHistoryRepo.clear(userId, PersonaMode.CHEF.modeKey())
                api.sendMessage(chatId, CHEF_INPUT_PROMPT)
                return
            }
            "/caloriecalculator" -> {
                mode[chatId] = PersonaMode.CALC
                state[chatId] = BotState.AWAITING_CALORIE_INPUT
                ChatHistoryRepo.clear(userId, PersonaMode.CALC.modeKey())
                api.sendMessage(chatId, CALORIE_INPUT_PROMPT)
                return
            }
            "/productinfo" -> {
                mode[chatId] = PersonaMode.PRODUCT
                state[chatId] = BotState.AWAITING_PRODUCT_INPUT
                ChatHistoryRepo.clear(userId, PersonaMode.PRODUCT.modeKey())
                api.sendMessage(chatId, PRODUCT_INPUT_PROMPT)
                return
            }
            "/help" -> { api.sendMessage(chatId, HELP_TEXT); return }
            "/reset" -> {
                val current = currentMode(chatId)
                ChatHistoryRepo.clear(userId, current.modeKey())
                api.sendMessage(chatId, "Контекст очищен")
                return
            }
        }

        when (currentMode(chatId)) {
            PersonaMode.CHEF    -> handleChef(chatId, userId, text)
            PersonaMode.CALC    -> handleCalorieInput(chatId, userId, text)
            PersonaMode.PRODUCT -> handleProductInput(chatId, userId, text)
        }
    }

    // ===== LLM =====

    private fun handleChef(chatId: Long, userId: Long, userText: String) {
        val isAdminUser = isAdmin(userId)
        if (!RateLimiter.allow(chatId, isAdminUser)) {
            api.sendMessage(chatId, AppConfig.PAYWALL_TEXT)
            return
        }
        val persona = PersonaMode.CHEF
        val history = buildHistoryMessages(userId, persona)
        val preparedUser = prepareForHistory(userText)
        val messages = mutableListOf(ChatMessage("system", PersonaPrompt.system()))
        messages.addAll(history)
        messages += ChatMessage("user", preparedUser)
        val reply = llm.complete(messages)
        api.sendMessage(chatId, reply)
        RateLimiter.consumeIfFree(chatId, isAdminUser)

        if (preparedUser.isNotEmpty()) {
            ChatHistoryRepo.append(userId, persona.modeKey(), "user", preparedUser)
        }
        val assistantPrepared = prepareForHistory(reply)
        if (assistantPrepared.isNotEmpty()) {
            ChatHistoryRepo.append(userId, persona.modeKey(), "assistant", assistantPrepared)
        }
    }

    private fun handleCalorieInput(chatId: Long, userId: Long, userText: String) {
        val isAdminUser = isAdmin(userId)
        if (!RateLimiter.allow(chatId, isAdminUser)) {
            api.sendMessage(chatId, AppConfig.PAYWALL_TEXT)
            state.remove(chatId)
            return
        }
        val persona = PersonaMode.CALC
        val history = buildHistoryMessages(userId, persona)
        val userPayload = "Данные пользователя: $userText"
        val preparedUser = prepareForHistory(userPayload)
        val messages = mutableListOf(ChatMessage("system", CalorieCalculatorPrompt.SYSTEM))
        messages.addAll(history)
        messages += ChatMessage("user", preparedUser)
        val reply = llm.complete(messages)
        api.sendMessage(chatId, reply)
        RateLimiter.consumeIfFree(chatId, isAdminUser)
        if (preparedUser.isNotEmpty()) {
            ChatHistoryRepo.append(userId, persona.modeKey(), "user", preparedUser)
        }
        val assistantPrepared = prepareForHistory(reply)
        if (assistantPrepared.isNotEmpty()) {
            ChatHistoryRepo.append(userId, persona.modeKey(), "assistant", assistantPrepared)
        }
        state.remove(chatId)
    }

    private fun handleProductInput(chatId: Long, userId: Long, userText: String) {
        val isAdminUser = isAdmin(userId)
        if (!RateLimiter.allow(chatId, isAdminUser)) {
            api.sendMessage(chatId, AppConfig.PAYWALL_TEXT)
            state.remove(chatId)
            return
        }
        val persona = PersonaMode.PRODUCT
        val history = buildHistoryMessages(userId, persona)
        val userPayload = "Ингредиент: $userText"
        val preparedUser = prepareForHistory(userPayload)
        val messages = mutableListOf(ChatMessage("system", ProductInfoPrompt.SYSTEM))
        messages.addAll(history)
        messages += ChatMessage("user", preparedUser)
        val reply = llm.complete(messages)
        api.sendMessage(chatId, reply)
        RateLimiter.consumeIfFree(chatId, isAdminUser)
        if (preparedUser.isNotEmpty()) {
            ChatHistoryRepo.append(userId, persona.modeKey(), "user", preparedUser)
        }
        val assistantPrepared = prepareForHistory(reply)
        if (assistantPrepared.isNotEmpty()) {
            ChatHistoryRepo.append(userId, persona.modeKey(), "assistant", assistantPrepared)
        }
        state.remove(chatId)
    }


    private fun adminMenuKeyboard(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inline_keyboard = listOf(
                listOf(InlineKeyboardButton("Создать рассылку", CB_ADMIN_BROADCAST)),
                listOf(InlineKeyboardButton("Статистика", CB_ADMIN_STATS)),
                listOf(InlineKeyboardButton("Проверить статус пользователя", CB_ADMIN_USER_STATUS)),
                listOf(InlineKeyboardButton("Выдать премиум", CB_ADMIN_GRANT))
            )
        )

    private fun broadcastConfirmKeyboard(): InlineKeyboardMarkup =
        InlineKeyboardMarkup(
            inline_keyboard = listOf(
                listOf(
                    InlineKeyboardButton("Да", CB_ADMIN_CONFIRM),
                    InlineKeyboardButton("Нет", CB_ADMIN_CANCEL)
                )
            )
        )

    private fun validateBroadcastText(chatId: Long, rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            api.sendMessage(chatId, "Текст рассылки не может быть пустым.", parseMode = null)
            return null
        }
        if (trimmed.length > MAX_BROADCAST_CHARS) {
            api.sendMessage(
                chatId,
                "Текст рассылки превышает ${MAX_BROADCAST_CHARS} символов. Сократите сообщение.",
                parseMode = null
            )
            return null
        }
        val sanitized = sanitizeAdminInput(trimmed, MAX_BROADCAST_CHARS)
        if (sanitized.isEmpty()) {
            api.sendMessage(chatId, "Текст рассылки не может быть пустым.", parseMode = null)
            return null
        }
        return sanitized
    }

    private fun processAdminStatusInput(chatId: Long, adminId: Long, rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            api.sendMessage(chatId, "Введите числовой Telegram ID пользователя.", parseMode = null)
            return
        }
        val targetId = trimmed.toLongOrNull()
        if (targetId == null || targetId <= 0) {
            println("ADMIN-STATUS-ERR: requester=$adminId raw=$trimmed reason=bad_id")
            api.sendMessage(chatId, "Неверный ID. Введите числовой Telegram ID.", parseMode = null)
            return
        }
        val info = runCatching { UsersRepo.find(targetId) }
            .onFailure { println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId reason=${it.message}") }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return
            }
        if (info == null) {
            println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId reason=not_found")
            api.sendMessage(chatId, "Пользователь не найден.", parseMode = null)
            return
        }
        val until = runCatching { PremiumRepo.getUntil(targetId) }
            .onFailure { println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId reason=${it.message}") }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return
            }
        val since = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        val messages7d = runCatching { MessagesRepo.countUserMessagesSince(targetId, since) }
            .onFailure { println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId reason=${it.message}") }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return
            }
        val now = System.currentTimeMillis()
        val premiumActive = until != null && until > now
        val untilDisplay = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
        val firstSeenDisplay = if (info.firstSeen > 0) dtf.format(Instant.ofEpochMilli(info.firstSeen)) else "—"
        val blockedLine = if (info.blockedTs > 0) {
            val blockedAt = dtf.format(Instant.ofEpochMilli(info.blockedTs))
            "Статус: неактивен (ошибка доставки $blockedAt)"
        } else {
            "Статус: активен"
        }
        val premiumLine = if (premiumActive) {
            "Премиум: активен до $untilDisplay"
        } else {
            "Премиум: не активен"
        }
        val statusMessage = """
            Telegram ID: $targetId
            Регистрация: $firstSeenDisplay
            $blockedLine
            $premiumLine
            Сообщений за 7 дней: $messages7d
        """.trimIndent()
        api.sendMessage(chatId, statusMessage, parseMode = null)
        adminStates.remove(chatId)
        println(
            "ADMIN-STATUS: requester=$adminId target=$targetId premium=$premiumActive until=$untilDisplay " +
                "messages7d=$messages7d blocked=${info.blockedTs}"
        )
        showAdminMenu(chatId)
    }

    private fun processAdminGrantInput(chatId: Long, adminId: Long, rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            api.sendMessage(chatId, ADMIN_GRANT_USAGE, parseMode = null)
            return
        }
        val parts = trimmed.split("\\s+".toRegex(), limit = 3)
        val targetId = parts.getOrNull(0)?.toLongOrNull()
        val days = parts.getOrNull(1)?.toIntOrNull()
        if (targetId == null || targetId <= 0 || days == null || days <= 0) {
            println("ADMIN-GRANT-ERR: requester=$adminId raw=$trimmed reason=bad_input")
            api.sendMessage(chatId, ADMIN_GRANT_USAGE, parseMode = null)
            return
        }
        val exists = runCatching { UsersRepo.exists(targetId) }
            .onFailure { println("ADMIN-GRANT-ERR: requester=$adminId target=$targetId days=$days reason=${it.message}") }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return
            }
        if (!exists) {
            println("ADMIN-GRANT-ERR: requester=$adminId target=$targetId days=$days reason=not_found")
            api.sendMessage(chatId, "Пользователь не найден.", parseMode = null)
            return
        }
        val until = runCatching {
            PremiumRepo.grantDays(targetId, days)
            PremiumRepo.getUntil(targetId)
        }.onFailure {
            println("ADMIN-GRANT-ERR: requester=$adminId target=$targetId days=$days reason=${it.message}")
        }.getOrElse {
            api.sendMessage(chatId, "Не удалось выдать премиум. Проверьте логи.", parseMode = null)
            return
        }
        val untilDisplay = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
        adminStates.remove(chatId)
        api.sendMessage(chatId, "Премиум пользователю $targetId активен до: $untilDisplay", parseMode = null)
        println("ADMIN-GRANT: requester=$adminId target=$targetId days=$days until=$untilDisplay")
        showAdminMenu(chatId)
    }

    private fun messageContentForLog(msg: TgMessage): String {
        val attachmentTag = when {
            msg.photo?.isNotEmpty() == true -> "[non-text: photo]"
            msg.document != null -> "[non-text: document]"
            msg.video != null -> "[non-text: video]"
            msg.video_note != null -> "[non-text: video_note]"
            msg.voice != null -> "[non-text: voice]"
            msg.audio != null -> "[non-text: audio]"
            msg.sticker != null -> "[non-text: sticker]"
            msg.animation != null -> "[non-text: animation]"
            else -> null
        }
        if (attachmentTag != null) {
            val caption = msg.caption?.takeIf { it.isNotBlank() }
                ?.let { sanitizeAdminInput(it, 200) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { " $it" }
                ?: ""
            return (attachmentTag + caption).trim()
        }
        return msg.text?.takeIf { it.isNotBlank() } ?: "[non-text]"
    }

    private fun trackUserActivity(userId: Long, text: String, role: String = "user") {
        val registered = runCatching { UsersRepo.touch(userId) }
            .onFailure { println("DB-ERR users.touch: ${it.message}") }
            .getOrElse { false }
        if (registered) {
            println("USERS: registered user_id=$userId")
        }
        val sanitized = sanitizeActivityText(text) ?: return
        runCatching { MessagesRepo.record(userId, sanitized, role) }
            .onFailure {
                val snippet = sanitized.take(200).replace('\n', ' ')
                println("DB-ERR messages.record: user_id=$userId role=$role text=$snippet err=${it.message}")
            }
    }

    private fun splitMessageForBroadcast(text: String): List<String> {
        val maxLen = 4096
        if (text.length <= maxLen) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxLen).coerceAtMost(text.length)
            if (end < text.length) {
                var split = text.lastIndexOf('\n', end - 1)
                if (split < start) {
                    split = text.lastIndexOf(' ', end - 1)
                }
                if (split >= start) {
                    end = split + 1
                }
            }
            if (end <= start) {
                end = (start + maxLen).coerceAtMost(text.length)
            }
            result += text.substring(start, end)
            start = end
        }
        return result
    }

    private fun startBroadcast(adminChatId: Long, text: String) {
        adminStates.remove(adminChatId)
        synchronized(broadcastMutex) {
            val current = broadcastJob
            if (current?.isActive == true) {
                api.sendMessage(adminChatId, "Рассылка уже выполняется. Дождитесь завершения текущей отправки.", parseMode = null)
                return
            }
            api.sendMessage(adminChatId, "Рассылка запущена. Это может занять немного времени…", parseMode = null)
            broadcastJob = GlobalScope.launch {
                try {
                    val recipients = runCatching { UsersRepo.getAllUserIds(includeBlocked = false) }
                        .onFailure {
                            println("ADMIN-BROADCAST-ERR: failed to load users ${it.message}")
                            api.sendMessage(adminChatId, "Не удалось получить список пользователей. Проверьте логи.", parseMode = null)
                        }
                        .getOrNull()
                        ?: return@launch
                    if (recipients.isEmpty()) {
                        api.sendMessage(adminChatId, "Активных пользователей не найдено — рассылать некому.", parseMode = null)
                        return@launch
                    }
                    val messageChunks = splitMessageForBroadcast(text)
                    println("ADMIN-BROADCAST: start recipients=${recipients.size} chunks=${messageChunks.size}")
                    var attempts = 0
                    var success = 0
                    var errors = 0
                    var blocked = 0
                    for (recipient in recipients) {
                        for (chunk in messageChunks) {
                            attempts++
                            val result = sendBroadcastChunk(recipient, chunk)
                            when (result) {
                                BroadcastResult.SENT -> success++
                                BroadcastResult.BLOCKED -> {
                                    errors++
                                    blocked++
                                }
                                BroadcastResult.FAILED -> errors++
                            }
                            delay(BROADCAST_RATE_DELAY_MS)
                            if (result == BroadcastResult.BLOCKED) break
                        }
                    }
                    val summary = buildString {
                        appendLine("Рассылка завершена.")
                        appendLine("Получателей: ${recipients.size}")
                        appendLine("Сообщений отправлено: $success из $attempts.")
                        append("Ошибок: $errors")
                        if (blocked > 0) append(" (заблокировано: $blocked)")
                    }.trimEnd()
                    api.sendMessage(adminChatId, summary, parseMode = null)
                    println("ADMIN-BROADCAST: done recipients=${recipients.size} attempts=$attempts success=$success errors=$errors blocked=$blocked")
                } finally {
                    synchronized(broadcastMutex) { broadcastJob = null }
                }
            }
        }
    }

    private suspend fun sendBroadcastChunk(recipient: Long, chunk: String): BroadcastResult {
        repeat(5) { attempt ->
            val outcome = try {
                api.sendMessageDetailed(recipient, chunk, parseMode = null, maxChars = 4096)
            } catch (t: Throwable) {
                println("ADMIN-BROADCAST-ERR: user=$recipient reason=${t.message}")
                delay(500)
                return@repeat
            }
            if (outcome.ok) {
                return BroadcastResult.SENT
            }
            when (outcome.errorCode) {
                429 -> {
                    val waitSec = (outcome.retryAfterSeconds ?: 1).coerceAtLeast(1)
                    println("ADMIN-BROADCAST-RATE-LIMIT: user=$recipient wait=${waitSec}s attempt=${attempt + 1}")
                    delay(waitSec * 1000L + 250L)
                    return@repeat
                }
                403 -> {
                    val desc = outcome.description ?: "forbidden"
                    println("ADMIN-BROADCAST-ERR: user=$recipient reason=forbidden description=$desc")
                    runCatching { UsersRepo.markBlocked(recipient, blocked = true) }
                        .onFailure { println("ADMIN-BROADCAST-ERR: mark_blocked_failed user=$recipient reason=${it.message}") }
                    return BroadcastResult.BLOCKED
                }
                else -> {
                    val code = outcome.errorCode?.toString() ?: "unknown"
                    val desc = outcome.description ?: "unknown"
                    println("ADMIN-BROADCAST-ERR: user=$recipient reason=$desc code=$code")
                    return BroadcastResult.FAILED
                }
            }
        }
        println("ADMIN-BROADCAST-ERR: user=$recipient reason=retries_exhausted")
        return BroadcastResult.FAILED
    }


    private fun sendAdminStats(chatId: Long) {
        val stats = runCatching {
            val total = UsersRepo.countUsers(includeBlocked = true)
            val activeInstalls = UsersRepo.countUsers(includeBlocked = false)
            val blocked = max(total - activeInstalls, 0L)
            val premium = PremiumRepo.countActive()
            val active7d = MessagesRepo.countActiveSince(System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L)
            listOf(total, activeInstalls, blocked, premium, active7d)
        }.getOrElse {
            println("ADMIN-STATS-ERR: ${it.message}")
            api.sendMessage(chatId, "Не удалось получить статистику. Проверьте логи.", parseMode = null)
            return
        }
        val total = stats[0]
        val activeInstalls = stats[1]
        val blocked = stats[2]
        val premium = stats[3]
        val active7d = stats[4]
        println("ADMIN-STATS: total=$total activeInstalls=$activeInstalls premium=$premium active7d=$active7d blocked=$blocked")
        val message = buildString {
            appendLine("Статистика:")
            appendLine("• Установок бота: $total")
            appendLine("• Активных установок: $activeInstalls")
            appendLine("• Премиум-пользователей: $premium")
            appendLine("• Активно за 7 дней: $active7d")
            if (blocked > 0) {
                appendLine("• Заблокировали бота: $blocked")
            }
        }.trimEnd()
        api.sendMessage(chatId, message, parseMode = null)
    }

    private fun showAdminMenu(chatId: Long) {
        adminStates.remove(chatId)
        println("ADMIN: show_menu chat=$chatId")
        api.sendMessage(chatId, "Админ-панель. Выберите действие:", replyMarkup = adminMenuKeyboard(), parseMode = null)
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

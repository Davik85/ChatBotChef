package app.web

import app.AppConfig
import app.db.AdminAuditRepo
import app.db.AudienceRepo
import app.db.ChatHistoryRepo
import app.db.MessagesRepo
import app.db.PremiumRepo
import app.db.ProcessedUpdatesRepo
import app.db.UserRegistry
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
import kotlin.random.Random
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlin.jvm.Volatile

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
        data class AwaitingConfirmation(val draft: BroadcastDraft) : AdminState()
        object AwaitingUserIdForStatus : AdminState()
        object AwaitingGrantParams : AdminState()
    }

    private data class BroadcastDraft(
        val text: String?,
        val caption: String?,
        val entities: List<TgMessageEntity>?,
        val captionEntities: List<TgMessageEntity>?,
        val sourceFromChatId: Long?,
        val sourceMessageId: Int?,
    ) {
        val contentLength: Int
            get() = text?.length ?: caption?.length ?: 0
    }

    private enum class BroadcastResult { SENT, BLOCKED, FAILED }

    private data class BroadcastSendOutcome(
        val result: BroadcastResult,
        val errorCode: Int? = null,
        val description: String? = null,
    )

    private data class AdminStatsSnapshot(
        val total: Long,
        val activeInstalls: Long,
        val blocked: Long,
        val premium: Long,
        val active7d: Long,
    )

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
        private const val ADMIN_GRANT_COMMAND_USAGE = "Использование: /grantpremium <tgId> <days>. Пример: /grantpremium 6859850730 30"
        private const val ADMIN_STATUS_COMMAND_USAGE = "Использование: /premiumstatus <tgId>"
        private const val MAX_BROADCAST_CHARS = 2000
        private const val ACTIVITY_MAX_CHARS = 3800
        private const val BROADCAST_RATE_DELAY_MIN_MS = 35L
        private const val BROADCAST_RATE_DELAY_MAX_MS = 50L
        private const val BROADCAST_BATCH_SIZE = 500
        private const val DAY_MS = 24L * 60L * 60L * 1000L
        private val NON_TEXT_REPLY = "Я работаю только с текстом. Пришлите запрос текстом."
        private const val MAX_USER_MESSAGE_CHARS = 3500
        private val TOO_LONG_REPLY =
            "Сообщение слишком длинное. Пожалуйста, сократите запрос и отправьте ещё раз."

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

    private fun normalizeLineBreaks(raw: String): String =
        raw.replace("\r\n", "\n").replace("\r", "\n")

    private fun parseTelegramId(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().removePrefix("+").replace("\\s+".toRegex(), "")
        if (normalized.isEmpty()) return null
        if (normalized.any { !it.isDigit() }) return null
        return normalized.toLongOrNull()
    }

    private fun parsePositiveDays(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim()
        val value = normalized.toIntOrNull() ?: return null
        return value.takeIf { it > 0 }
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

    private fun markUserActive(userId: Long, reason: String) {
        if (userId <= 0) return
        runCatching {
            val changed = UsersRepo.markBlocked(userId, blocked = false)
            if (changed) {
                println("USERS-UNBLOCK: user=$userId source=$reason")
            }
        }.onFailure {
            println("USERS-UNBLOCK-ERR: user=$userId source=$reason reason=${it.message}")
        }
    }

    private fun markUserBlocked(userId: Long, reason: String, status: String? = null) {
        if (userId <= 0) return
        runCatching {
            val changed = UsersRepo.markBlocked(userId, blocked = true)
            if (changed) {
                val statusPart = status?.let { " status=$it" } ?: ""
                println("USERS-BLOCK: user=$userId source=$reason$statusPart")
            }
        }.onFailure {
            println("USERS-BLOCK-ERR: user=$userId source=$reason reason=${it.message}")
        }
    }

    private fun handleChatMemberUpdate(update: TgChatMemberUpdated, source: String) {
        val chat = update.chat
        val chatType = chat.type?.lowercase()
        if (chatType != null && chatType != "private") {
            return
        }
        val userId = chat.id
        val newStatus = update.new_chat_member?.status?.lowercase() ?: return
        when (newStatus) {
            "kicked", "left" -> markUserBlocked(userId, "chat_member:$source", newStatus)
            "member", "administrator", "creator" -> markUserActive(userId, "chat_member:$source:$newStatus")
        }
    }

    private fun upsertUser(from: TgUser?, source: String) {
        val user = from ?: return
        val now = System.currentTimeMillis()
        runCatching { UserRegistry.upsert(user, now) }
            .onSuccess { inserted ->
                val insertedFlag = if (inserted) 1 else 0
                println("DB-USERS-UPSERT: id=${user.id} source=$source inserted=$insertedFlag")
            }
            .onFailure {
                println("DB-USERS-UPSERT-ERR: id=${user.id} source=$source reason=${it.message}")
            }
    }

    suspend fun run() {
        require(api.getMe()) { "Telegram getMe failed" }
        GlobalScope.launch { ReminderJob(api).runForever() }

        var lastProcessedId = ProcessedUpdatesRepo.lastProcessedId()
        var offset: Long = lastProcessedId + 1
        println("OFFSET init last_id=$lastProcessedId offset=$offset")

        while (true) {
            try {
                val updates: List<TgUpdate> = try {
                    api.getUpdates(offset)
                } catch (conflict: TelegramPollingConflictException) {
                    println(
                        "TG-POLL-CONFLICT: code=${conflict.statusCode} body=${conflict.body}"
                    )
                    delay(5000)
                    continue
                }
                if (updates.isEmpty()) { delay(1200); continue }

                for (u in updates) {
                    println("RECV update_id=${u.update_id}")
                    val firstSeen = ProcessedUpdatesRepo.tryInsert(u.update_id)
                    if (!firstSeen) {
                        println("DEDUP duplicate update_id=${u.update_id}")
                        println("TG-POLL-SKIP: update=${u.update_id} reason=duplicate")
                        if (u.update_id > lastProcessedId) {
                            lastProcessedId = u.update_id
                            offset = lastProcessedId + 1
                        }
                        continue
                    }

                    println("DEDUP first_seen update_id=${u.update_id}")

                    u.message?.from?.let { upsertUser(it, "message") }
                    u.edited_message?.from?.let { upsertUser(it, "message") }
                    u.callback_query?.let { upsertUser(it.from, "callback") }
                    u.pre_checkout_query?.let { upsertUser(it.from, "precheckout") }
                    u.my_chat_member?.from?.let { upsertUser(it, "chat_member") }
                    u.chat_member?.from?.let { upsertUser(it, "chat_member") }

                    u.my_chat_member?.let { handleChatMemberUpdate(it, "my_chat_member") }
                    u.chat_member?.let { handleChatMemberUpdate(it, "chat_member") }

                    val handledSuccessfully = try {
                        val pcq = u.pre_checkout_query
                        if (pcq != null) {
                            handlePreCheckout(pcq)
                        } else {
                            val cb: TgCallbackQuery? = u.callback_query
                            if (cb != null) {
                                handleCallback(cb)
                            } else {
                                val msg = u.message ?: u.edited_message
                                if (msg != null) {
                                    val sp = msg.successful_payment
                                    if (sp != null) {
                                        upsertUser(msg.from, "payment")
                                        handleSuccessfulPayment(msg)
                                    } else {
                                        route(msg)
                                    }
                                }
                            }
                        }
                        true
                    } catch (t: Throwable) {
                        println("HANDLE-ERR update_id=${u.update_id} cause=${t::class.simpleName}:${t.message}")
                        ProcessedUpdatesRepo.remove(u.update_id)
                        false
                    }

                    if (handledSuccessfully && u.update_id > lastProcessedId) {
                        lastProcessedId = u.update_id
                        offset = lastProcessedId + 1
                    }
                }
            } catch (t: Throwable) {
                val type = t::class.simpleName ?: "Throwable"
                println("TG-POLL-ERR: $type:${t.message}")
                delay(1500)
            }
        }
    }

    // ===== Payments =====

    private fun handlePreCheckout(q: TgPreCheckoutQuery) {
        markUserActive(q.from.id, "pre_checkout")
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
        markUserActive(payerId, "successful_payment")
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

        markUserActive(userId, "callback")
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
                println("ADMIN-AUDIT: action=broadcast_prompt chat=$chatId user=$userId source=menu")
                AdminAuditRepo.record(userId, action = "broadcast_prompt", target = chatId.toString(), meta = "source=menu")
                adminStates[chatId] = AdminState.AwaitingBroadcastText
                api.sendMessage(chatId, "Пришлите текст рассылки одним сообщением.", parseMode = null)
            }
            CB_ADMIN_STATS -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                println("ADMIN-AUDIT: action=stats_request chat=$chatId user=$userId source=menu")
                AdminAuditRepo.record(userId, action = "stats_request", target = chatId.toString(), meta = "source=menu")
                sendAdminStats(chatId)
            }
            CB_ADMIN_USER_STATUS -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                println("ADMIN-AUDIT: action=status_prompt chat=$chatId user=$userId source=menu")
                AdminAuditRepo.record(userId, action = "status_prompt", target = chatId.toString(), meta = "source=menu")
                adminStates[chatId] = AdminState.AwaitingUserIdForStatus
                api.sendMessage(chatId, "Введите числовой Telegram ID пользователя.", parseMode = null)
            }
            CB_ADMIN_GRANT -> {
                api.answerCallback(cb.id)
                if (!isAdmin(userId)) {
                    api.sendMessage(chatId, "Команда доступна только админам.")
                    return
                }
                println("ADMIN-AUDIT: action=grant_prompt chat=$chatId user=$userId source=menu")
                AdminAuditRepo.record(userId, action = "grant_prompt", target = chatId.toString(), meta = "source=menu")
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
                    val length = prepared.draft.contentLength
                    println("ADMIN-AUDIT: action=broadcast_confirm chat=$chatId user=$userId chars=$length")
                    AdminAuditRepo.record(
                        adminId = userId,
                        action = "broadcast_confirm",
                        target = chatId.toString(),
                        meta = "chars=$length"
                    )
                    startBroadcast(chatId, prepared.draft, userId)
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
                    println("ADMIN-AUDIT: action=broadcast_cancel chat=$chatId user=$userId source=menu")
                    AdminAuditRepo.record(userId, action = "broadcast_cancel", target = chatId.toString(), meta = "source=menu")
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

        markUserActive(userId, "message")
        val hasAttachments = (msg.photo?.isNotEmpty() == true) ||
            msg.document != null ||
            msg.video != null ||
            msg.video_note != null ||
            msg.voice != null ||
            msg.audio != null ||
            msg.sticker != null ||
            msg.animation != null

        val adminState = adminStates[chatId]
        if (adminState != null) {
            if (!isAdmin(userId)) {
                adminStates.remove(chatId)
                println("ADMIN-AUDIT: action=state_force_exit chat=$chatId user=$userId reason=non_admin")
            } else {
                if (tryHandleAdminStateInput(chatId, userId, msg)) {
                    return
                }
                adminStates.remove(chatId)
                val stateName = adminState::class.simpleName ?: "unknown"
                println("ADMIN-AUDIT: action=state_exit chat=$chatId user=$userId state=$stateName reason=unhandled_input")
                AdminAuditRepo.record(
                    adminId = userId,
                    action = "state_exit",
                    target = chatId.toString(),
                    meta = "state=$stateName"
                )
            }
        }

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
        if (text.length > MAX_USER_MESSAGE_CHARS) {
            api.sendMessage(chatId, TOO_LONG_REPLY, parseMode = null)
            return
        }
        val lower = text.lowercase()

        trackUserActivity(userId, originalText)

        if (lower.startsWith("/admin")) {
            if (!isAdmin(userId)) {
                api.sendMessage(chatId, "Команда доступна только админам.")
            } else {
                println("ADMIN-AUDIT: action=menu_open chat=$chatId user=$userId source=command")
                AdminAuditRepo.record(userId, action = "menu_open", target = chatId.toString(), meta = "source=command")
                showAdminMenu(chatId, userId)
            }
            return
        }

        if (text.isBlank()) return

        if (handleAdminCommand(chatId, userId, originalText)) {
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

    private fun tryHandleAdminStateInput(chatId: Long, adminId: Long, message: TgMessage): Boolean {
        val state = adminStates[chatId] ?: return false
        val rawInput = message.text ?: message.caption ?: ""
        val trimmed = rawInput.trim()
        val lower = trimmed.lowercase()
        return when (state) {
            AdminState.AwaitingBroadcastText -> {
                if (lower == "/cancel") {
                    adminStates.remove(chatId)
                    println("ADMIN-AUDIT: action=broadcast_cancel chat=$chatId user=$adminId source=state")
                    AdminAuditRepo.record(adminId, action = "broadcast_cancel", target = chatId.toString(), meta = "source=state")
                    api.sendMessage(chatId, "Рассылка отменена.", parseMode = null)
                    true
                } else if (trimmed.startsWith("/")) {
                    false
                } else {
                    val draft = prepareBroadcastDraft(chatId, message)
                    if (draft != null) {
                        adminStates[chatId] = AdminState.AwaitingConfirmation(draft)
                        println("ADMIN-AUDIT: action=broadcast_prepared chat=$chatId user=$adminId chars=${draft.contentLength}")
                        AdminAuditRepo.record(
                            adminId,
                            action = "broadcast_prepared",
                            target = chatId.toString(),
                            meta = "chars=${draft.contentLength}"
                        )
                        showBroadcastPreview(chatId, draft)
                    }
                    true
                }
            }
            is AdminState.AwaitingConfirmation -> {
                if (lower == "/cancel") {
                    adminStates.remove(chatId)
                    println("ADMIN-AUDIT: action=broadcast_cancel chat=$chatId user=$adminId source=state_confirm")
                    AdminAuditRepo.record(
                        adminId,
                        action = "broadcast_cancel",
                        target = chatId.toString(),
                        meta = "source=state_confirm"
                    )
                    api.sendMessage(chatId, "Рассылка отменена.", parseMode = null)
                    true
                } else if (trimmed.startsWith("/")) {
                    false
                } else {
                    val draft = prepareBroadcastDraft(chatId, message)
                    if (draft != null) {
                        adminStates[chatId] = AdminState.AwaitingConfirmation(draft)
                        println("ADMIN-AUDIT: action=broadcast_updated chat=$chatId user=$adminId chars=${draft.contentLength}")
                        AdminAuditRepo.record(
                            adminId,
                            action = "broadcast_updated",
                            target = chatId.toString(),
                            meta = "chars=${draft.contentLength}"
                        )
                        showBroadcastPreview(chatId, draft)
                    }
                    true
                }
            }
            AdminState.AwaitingUserIdForStatus -> {
                if (lower == "/cancel") {
                    adminStates.remove(chatId)
                    println("ADMIN-AUDIT: action=status_cancel chat=$chatId user=$adminId")
                    AdminAuditRepo.record(adminId, action = "status_cancel", target = chatId.toString(), meta = null)
                    api.sendMessage(chatId, "Операция отменена.", parseMode = null)
                    true
                } else if (trimmed.startsWith("/")) {
                    false
                } else if (trimmed.isEmpty()) {
                    api.sendMessage(chatId, "Введите числовой Telegram ID пользователя.", parseMode = null)
                    true
                } else {
                    val targetId = parseTelegramId(trimmed)
                    if (targetId == null) {
                        println("ADMIN-STATUS: requester=$adminId raw=$trimmed source=panel result=bad_id")
                        api.sendMessage(chatId, "Неверный ID. Введите числовой Telegram ID.", parseMode = null)
                        true
                    } else {
                        val handled = handlePremiumStatusLookup(
                            chatId = chatId,
                            adminId = adminId,
                            targetId = targetId,
                            source = "panel",
                            showMenuAfter = true
                        )
                        if (handled) {
                            adminStates.remove(chatId)
                        }
                        true
                    }
                }
            }
            AdminState.AwaitingGrantParams -> {
                if (lower == "/cancel") {
                    adminStates.remove(chatId)
                    println("ADMIN-AUDIT: action=grant_cancel chat=$chatId user=$adminId")
                    AdminAuditRepo.record(adminId, action = "grant_cancel", target = chatId.toString(), meta = null)
                    api.sendMessage(chatId, "Операция отменена.", parseMode = null)
                    true
                } else if (trimmed.startsWith("/")) {
                    false
                } else if (trimmed.isEmpty()) {
                    api.sendMessage(chatId, ADMIN_GRANT_USAGE, parseMode = null)
                    true
                } else {
                    val parts = trimmed.split("\\s+".toRegex(), limit = 3)
                    val targetToken = parts.getOrNull(0)
                    val daysToken = parts.getOrNull(1)
                    if (targetToken.isNullOrBlank() || daysToken.isNullOrBlank()) {
                        println("ADMIN-GRANT: requester=$adminId raw=$trimmed source=panel result=missing_args")
                        api.sendMessage(chatId, ADMIN_GRANT_USAGE, parseMode = null)
                        true
                    } else {
                        val targetId = parseTelegramId(targetToken)
                        val days = parsePositiveDays(daysToken)
                        if (targetId == null) {
                            println("ADMIN-GRANT: requester=$adminId raw=$trimmed source=panel result=bad_id")
                            api.sendMessage(chatId, "Неверный ID. Введите числовой Telegram ID.", parseMode = null)
                            true
                        } else if (days == null) {
                            val isNumeric = daysToken.trim().toIntOrNull() != null
                            if (isNumeric) {
                                println("ADMIN-GRANT: requester=$adminId target=$targetId raw=$trimmed source=panel result=bad_days")
                                api.sendMessage(chatId, "Количество дней должно быть больше 0.", parseMode = null)
                            } else {
                                println("ADMIN-GRANT: requester=$adminId raw=$trimmed source=panel result=bad_days_format")
                                api.sendMessage(chatId, ADMIN_GRANT_USAGE, parseMode = null)
                            }
                            true
                        } else {
                            val handled = handleGrantPremium(
                                chatId = chatId,
                                adminId = adminId,
                                targetId = targetId,
                                days = days,
                                source = "panel",
                                showMenuAfter = true,
                            )
                            if (handled) {
                                adminStates.remove(chatId)
                            }
                            true
                        }
                    }
                }
            }
        }
    }

    private fun handleAdminCommand(chatId: Long, userId: Long, rawInput: String): Boolean {
        val trimmed = rawInput.trim()
        if (!trimmed.startsWith("/")) return false
        val lower = trimmed.lowercase()

        if (lower.startsWith("/premiumstatus")) {
            if (!isAdmin(userId)) {
                api.sendMessage(chatId, "Команда доступна только админам.")
                return true
            }
            val parts = trimmed.split("\\s+".toRegex(), limit = 3)
            val targetIdRaw = parts.getOrNull(1)
            if (targetIdRaw.isNullOrBlank()) {
                api.sendMessage(chatId, ADMIN_STATUS_COMMAND_USAGE, parseMode = null)
                println("ADMIN-STATUS: requester=$userId raw=$trimmed source=command result=missing_args")
                return true
            }
            val targetId = parseTelegramId(targetIdRaw)
            if (targetId == null) {
                api.sendMessage(chatId, "Неверный ID. Введите числовой Telegram ID.", parseMode = null)
                println("ADMIN-STATUS: requester=$userId raw=$trimmed source=command result=bad_id")
                return true
            }
            handlePremiumStatusLookup(
                chatId = chatId,
                adminId = userId,
                targetId = targetId,
                source = "command",
                showMenuAfter = false
            )
            return true
        }

        if (lower.startsWith("/grantpremium")) {
            if (!isAdmin(userId)) {
                api.sendMessage(chatId, "Команда доступна только админам.")
                return true
            }
            val parts = trimmed.split("\\s+".toRegex(), limit = 3)
            val targetId = parseTelegramId(parts.getOrNull(1))
            val daysRaw = parts.getOrNull(2)
            val days = parsePositiveDays(daysRaw)
            if (targetId == null) {
                api.sendMessage(chatId, "Неверный ID. Введите числовой Telegram ID.", parseMode = null)
                println("ADMIN-GRANT: requester=$userId raw=$trimmed source=command result=bad_id")
                return true
            }
            if (days == null) {
                val numeric = daysRaw?.trim()?.toIntOrNull() != null
                if (numeric) {
                    api.sendMessage(chatId, "Количество дней должно быть больше 0.", parseMode = null)
                    println("ADMIN-GRANT: requester=$userId target=$targetId raw=$trimmed source=command result=bad_days")
                } else {
                    api.sendMessage(chatId, ADMIN_GRANT_COMMAND_USAGE, parseMode = null)
                    println("ADMIN-GRANT: requester=$userId raw=$trimmed source=command result=missing_days")
                }
                return true
            }
            handleGrantPremium(
                chatId = chatId,
                adminId = userId,
                targetId = targetId,
                days = days,
                source = "command",
                showMenuAfter = false
            )
            return true
        }

        return false
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

    private fun prepareBroadcastDraft(chatId: Long, message: TgMessage): BroadcastDraft? {
        val normalizedText = message.text?.let(::normalizeLineBreaks)
        val normalizedCaption = message.caption?.let(::normalizeLineBreaks)
        val hasMedia = (message.photo?.isNotEmpty() == true) ||
            message.document != null ||
            message.video != null ||
            message.video_note != null ||
            message.voice != null ||
            message.audio != null ||
            message.sticker != null ||
            message.animation != null
        val hasContent = !normalizedText.isNullOrBlank() || !normalizedCaption.isNullOrBlank()
        if (!hasContent && !hasMedia) {
            api.sendMessage(chatId, "Текст рассылки не может быть пустым.", parseMode = null)
            return null
        }
        if (!normalizedText.isNullOrBlank() && normalizedText.length > MAX_BROADCAST_CHARS) {
            api.sendMessage(
                chatId,
                "Текст рассылки превышает ${MAX_BROADCAST_CHARS} символов. Сократите сообщение.",
                parseMode = null
            )
            return null
        }
        if (!normalizedCaption.isNullOrBlank() && normalizedCaption.length > MAX_BROADCAST_CHARS) {
            api.sendMessage(
                chatId,
                "Подпись рассылки превышает ${MAX_BROADCAST_CHARS} символов. Сократите сообщение.",
                parseMode = null
            )
            return null
        }
        return BroadcastDraft(
            text = normalizedText,
            caption = normalizedCaption,
            entities = message.entities,
            captionEntities = message.caption_entities,
            sourceFromChatId = message.chat.id,
            sourceMessageId = message.message_id,
        )
    }

    private fun showBroadcastPreview(chatId: Long, draft: BroadcastDraft) {
        val fromChatId = draft.sourceFromChatId
        val messageId = draft.sourceMessageId
        if (fromChatId != null && messageId != null) {
            val previewResult = runCatching { api.copyMessage(chatId, fromChatId, messageId) }
                .onFailure {
                    println("BCAST-PREVIEW-ERR chat=$chatId reason=${it.message}")
                }
                .getOrNull()
            if (previewResult != null && !previewResult.ok) {
                val code = previewResult.errorCode?.toString() ?: "unknown"
                val desc = previewResult.description?.replace("\n", " ")?.take(200) ?: "unknown"
                println("BCAST-PREVIEW-ERR chat=$chatId code=$code reason=$desc")
            }
        } else {
            println("BCAST-PREVIEW-WARN chat=$chatId reason=missing_source_message")
        }
        api.sendMessage(
            chatId,
            "Отправить рассылку всем пользователям?",
            replyMarkup = broadcastConfirmKeyboard(),
            parseMode = null
        )
    }

    private fun handlePremiumStatusLookup(
        chatId: Long,
        adminId: Long,
        targetId: Long,
        source: String,
        showMenuAfter: Boolean
    ): Boolean {
        AdminAuditRepo.record(adminId, action = "status_lookup", target = targetId.toString(), meta = "source=$source")
        val snapshot = runCatching { UsersRepo.loadSnapshot(targetId) }
            .onFailure {
                println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId source=$source reason=${it.message}")
            }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return false
            }

        if (snapshot == null) {
            println("ADMIN-STATUS: requester=$adminId target=$targetId source=$source result=not_found")
            api.sendMessage(chatId, "Пользователь не найден", parseMode = null)
            if (showMenuAfter) {
                showAdminMenu(chatId, adminId)
            }
            return false
        }

        if (!snapshot.existsInUsers) {
            runCatching { UsersRepo.touch(targetId) }
                .onFailure {
                    println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId source=$source reason=touch_failed err=${it.message}")
                }
        }

        val now = System.currentTimeMillis()
        val until = runCatching { PremiumRepo.getUntil(targetId) }
            .onFailure {
                println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId source=$source reason=${it.message}")
            }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return false
            }

        val messages7d = runCatching { MessagesRepo.countUserMessagesSince(targetId, now - 7 * DAY_MS) }
            .onFailure {
                println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId source=$source reason=${it.message}")
            }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return false
            }

        val messagesTotal = runCatching { MessagesRepo.countTotalUserMessages(targetId) }
            .onFailure {
                println("ADMIN-STATUS-ERR: requester=$adminId target=$targetId source=$source reason=${it.message}")
            }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return false
            }

        val premiumActive = until != null && until > now
        val untilDisplay = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
        val firstSeenDisplay = snapshot.firstSeen?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
        val blockedLine = if (snapshot.blocked || snapshot.blockedTs > 0) {
            val blockedAt = snapshot.blockedTs.takeIf { it > 0 }?.let { dtf.format(Instant.ofEpochMilli(it)) }
            if (blockedAt != null) {
                "Статус: неактивен (ошибка доставки $blockedAt)"
            } else {
                "Статус: неактивен"
            }
        } else {
            "Статус: активен"
        }
        val premiumLine = when {
            premiumActive -> {
                val remaining = ((until!! - now + DAY_MS - 1) / DAY_MS).coerceAtLeast(0L)
                val remainText = if (remaining <= 0L) "менее 1 дня" else "$remaining дн."
                "Премиум: активен до $untilDisplay (осталось $remainText)"
            }
            until != null && until > 0L -> {
                val expiredAt = dtf.format(Instant.ofEpochMilli(until))
                "Премиум: не активен (истёк $expiredAt)"
            }
            else -> "Премиум: не активен"
        }

        val statusMessage = """
            Telegram ID: $targetId
            Регистрация: $firstSeenDisplay
            $blockedLine
            $premiumLine
            Сообщений за 7 дней: $messages7d
            Всего сообщений: $messagesTotal
        """.trimIndent()

        api.sendMessage(chatId, statusMessage, parseMode = null)
        println(
            "ADMIN-STATUS: requester=$adminId target=$targetId source=$source result=ok premium=$premiumActive " +
                "until=$untilDisplay messages7d=$messages7d messagesTotal=$messagesTotal blocked=${snapshot.blocked} " +
                "blockedTs=${snapshot.blockedTs} " +
                "existsInUsers=${snapshot.existsInUsers}"
        )
        if (showMenuAfter) {
            showAdminMenu(chatId)
        }
        return true
    }

    private fun handleGrantPremium(
        chatId: Long,
        adminId: Long,
        targetId: Long,
        days: Int,
        source: String,
        showMenuAfter: Boolean
    ): Boolean {
        AdminAuditRepo.record(
            adminId,
            action = "grant_request",
            target = targetId.toString(),
            meta = "days=$days source=$source"
        )
        val snapshot = runCatching { UsersRepo.loadSnapshot(targetId) }
            .onFailure {
                println("ADMIN-GRANT-ERR: requester=$adminId target=$targetId days=$days source=$source reason=${it.message}")
            }
            .getOrElse {
                api.sendMessage(chatId, "Не удалось получить данные. Проверьте логи.", parseMode = null)
                return false
            }

        if (snapshot == null) {
            println("ADMIN-GRANT: requester=$adminId target=$targetId days=$days source=$source result=not_found")
            api.sendMessage(chatId, "Пользователь не найден", parseMode = null)
            if (showMenuAfter) {
                showAdminMenu(chatId)
            }
            return false
        }

        if (!snapshot.existsInUsers) {
            runCatching { UsersRepo.touch(targetId) }
                .onFailure {
                    println("ADMIN-GRANT-ERR: requester=$adminId target=$targetId days=$days source=$source reason=touch_failed err=${it.message}")
                }
        }

        val until = runCatching {
            PremiumRepo.grantDays(targetId, days)
            PremiumRepo.getUntil(targetId)
        }.onFailure {
            println("ADMIN-GRANT-ERR: requester=$adminId target=$targetId days=$days source=$source reason=${it.message}")
        }.getOrElse {
            api.sendMessage(chatId, "Не удалось выдать премиум. Проверьте логи.", parseMode = null)
            return false
        }

        val untilDisplay = until?.let { dtf.format(Instant.ofEpochMilli(it)) } ?: "—"
        api.sendMessage(chatId, "Премиум пользователю $targetId активен до: $untilDisplay", parseMode = null)
        runCatching { UsersRepo.touch(targetId) }
            .onFailure {
                println(
                    "ADMIN-GRANT-ERR: requester=$adminId target=$targetId days=$days source=$source reason=touch_failed err=${it.message}"
                )
            }
        println(
            "ADMIN-GRANT: requester=$adminId target=$targetId days=$days source=$source result=ok until=$untilDisplay " +
                "existsInUsers=${snapshot.existsInUsers} blocked=${snapshot.blocked} blockedTs=${snapshot.blockedTs}"
        )
        if (showMenuAfter) {
            showAdminMenu(chatId, adminId)
        }
        return true
    }

    private fun trackUserActivity(userId: Long, text: String, role: String = "user") {
        val sanitized = sanitizeActivityText(text) ?: return
        runCatching { MessagesRepo.record(userId, sanitized, role) }
            .onFailure {
                println(
                    "DB-MESSAGES-ERR: user_id=$userId role=$role chars=${sanitized.length} reason=${it.message}"
                )
            }
    }

    private fun startBroadcast(adminChatId: Long, draft: BroadcastDraft, adminId: Long) {
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
                    val context = runCatching { AudienceRepo.createContext(includeBlocked = false) }
                        .onFailure {
                            println("ADMIN-BROADCAST-ERR: failed to form audience ${it.message}")
                            api.sendMessage(adminChatId, "Не удалось получить список пользователей. Проверьте логи.", parseMode = null)
                        }
                        .getOrNull()
                        ?: return@launch
                    val totalRecipients = context.filteredCount
                    if (totalRecipients <= 0L) {
                        api.sendMessage(adminChatId, "Активных пользователей не найдено — рассылать некому.", parseMode = null)
                        return@launch
                    }
                    println("BCAST-START total=$totalRecipients")
                    AdminAuditRepo.record(
                        adminId = adminId,
                        action = "broadcast_start",
                        target = adminChatId.toString(),
                        meta = "recipients=$totalRecipients"
                    )
                    var sentRecipients = 0L
                    var failedRecipients = 0L
                    var blockedRecipients = 0L
                    var offset = 0L
                    while (offset < totalRecipients) {
                        val batch = AudienceRepo.loadPage(context, offset, BROADCAST_BATCH_SIZE)
                        if (batch.isEmpty()) {
                            break
                        }
                        for (recipient in batch) {
                            val outcome = sendBroadcastToUser(recipient, draft)
                            when (outcome.result) {
                                BroadcastResult.SENT -> {
                                    sentRecipients++
                                    println("BCAST-SEND ok user=$recipient")
                                }
                                BroadcastResult.BLOCKED -> {
                                    blockedRecipients++
                                    val code = outcome.errorCode?.toString() ?: "403"
                                    val desc = outcome.description?.replace("\n", " ")?.take(200) ?: "blocked"
                                    println("BCAST-ERR user=$recipient code=$code reason=$desc")
                                }
                                BroadcastResult.FAILED -> {
                                    failedRecipients++
                                    val code = outcome.errorCode?.toString() ?: "unknown"
                                    val desc = outcome.description?.replace("\n", " ")?.take(200) ?: "unknown"
                                    println("BCAST-ERR user=$recipient code=$code reason=$desc")
                                }
                            }
                            val delayMs = Random.nextLong(
                                BROADCAST_RATE_DELAY_MIN_MS,
                                BROADCAST_RATE_DELAY_MAX_MS + 1
                            )
                            delay(delayMs)
                        }
                        offset += batch.size
                    }
                    val errors = failedRecipients + blockedRecipients
                    val summary = "Рассылка завершена. ok=$sentRecipients, errors=$errors"
                    api.sendMessage(adminChatId, summary, parseMode = null)
                    AdminAuditRepo.record(
                        adminId = adminId,
                        action = "broadcast_done",
                        target = adminChatId.toString(),
                        meta = "sent=$sentRecipients failed=$failedRecipients blocked=$blockedRecipients"
                    )
                    println("BCAST-DONE ok=$sentRecipients err=$errors failed=$failedRecipients blocked=$blockedRecipients")
                } finally {
                    synchronized(broadcastMutex) { broadcastJob = null }
                }
            }
        }
    }

    private fun isBlockedResponse(code: Int?, description: String?): Boolean {
        if (code == 403) return true
        val normalized = description?.lowercase()?.trim() ?: return false
        return normalized.contains("bot was blocked by the user")
    }

    private fun isBlockedException(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val normalized = message.lowercase()
        if (normalized.contains("bot was blocked by the user")) return true
        return normalized.contains("forbidden") && normalized.contains("403")
    }

    private suspend fun sendBroadcastToUser(recipient: Long, draft: BroadcastDraft): BroadcastSendOutcome {
        val copyOutcome = tryCopyBroadcast(recipient, draft)
        if (copyOutcome != null) {
            return copyOutcome
        }
        return sendBroadcastFallback(recipient, draft)
    }

    private suspend fun tryCopyBroadcast(recipient: Long, draft: BroadcastDraft): BroadcastSendOutcome? {
        val fromChatId = draft.sourceFromChatId ?: return null
        val messageId = draft.sourceMessageId ?: return null
        for (attempt in 0 until 5) {
            val outcome = try {
                api.copyMessage(recipient, fromChatId, messageId)
            } catch (t: Throwable) {
                val message = t.message
                if (isBlockedException(message)) {
                    markRecipientBlocked(recipient)
                    return BroadcastSendOutcome(BroadcastResult.BLOCKED, 403, message)
                }
                println("BCAST-ERR user=$recipient code=exception reason=${message ?: "unknown"} attempt=${attempt + 1}")
                delay(500)
                continue
            }
            if (outcome.ok) {
                return BroadcastSendOutcome(BroadcastResult.SENT)
            }
            when {
                outcome.errorCode == 429 -> {
                    val waitSec = (outcome.retryAfterSeconds ?: 1).coerceAtLeast(1)
                    println("BCAST-RATE-LIMIT user=$recipient wait=${waitSec}s attempt=${attempt + 1}")
                    delay((waitSec + 1) * 1000L)
                }
                isBlockedResponse(outcome.errorCode, outcome.description) -> {
                    markRecipientBlocked(recipient)
                    return BroadcastSendOutcome(BroadcastResult.BLOCKED, outcome.errorCode, outcome.description)
                }
                outcome.errorCode == 400 -> {
                    return null
                }
                else -> {
                    return BroadcastSendOutcome(BroadcastResult.FAILED, outcome.errorCode, outcome.description)
                }
            }
        }
        return BroadcastSendOutcome(BroadcastResult.FAILED, 429, "copy_retries_exhausted")
    }

    private suspend fun sendBroadcastFallback(recipient: Long, draft: BroadcastDraft): BroadcastSendOutcome {
        val text = draft.text ?: draft.caption
        if (text.isNullOrBlank()) {
            return BroadcastSendOutcome(BroadcastResult.FAILED, 400, "fallback_empty")
        }
        val entities = if (draft.text != null) draft.entities else draft.captionEntities
        for (attempt in 0 until 5) {
            val outcome = try {
                api.sendMessageDetailed(recipient, text, parseMode = null, maxChars = 4096, entities = entities)
            } catch (t: Throwable) {
                val message = t.message
                if (isBlockedException(message)) {
                    markRecipientBlocked(recipient)
                    return BroadcastSendOutcome(BroadcastResult.BLOCKED, 403, message)
                }
                println("BCAST-ERR user=$recipient code=exception reason=${message ?: "unknown"} attempt=${attempt + 1}")
                delay(500)
                continue
            }
            if (outcome.ok) {
                return BroadcastSendOutcome(BroadcastResult.SENT)
            }
            when {
                outcome.errorCode == 429 -> {
                    val waitSec = (outcome.retryAfterSeconds ?: 1).coerceAtLeast(1)
                    println("BCAST-RATE-LIMIT user=$recipient wait=${waitSec}s attempt=${attempt + 1}")
                    delay((waitSec + 1) * 1000L)
                }
                isBlockedResponse(outcome.errorCode, outcome.description) -> {
                    markRecipientBlocked(recipient)
                    return BroadcastSendOutcome(BroadcastResult.BLOCKED, outcome.errorCode, outcome.description)
                }
                else -> {
                    return BroadcastSendOutcome(BroadcastResult.FAILED, outcome.errorCode, outcome.description)
                }
            }
        }
        return BroadcastSendOutcome(BroadcastResult.FAILED, 500, "fallback_retries_exhausted")
    }

    private fun markRecipientBlocked(recipient: Long) {
        markUserBlocked(recipient, "broadcast")
    }

    private fun sendAdminStats(chatId: Long) {
        runCatching { UsersRepo.repairOrphans(source = "admin_stats") }
            .onFailure { println("ADMIN-STATS-ERR: repair_users ${it.message}") }
        val stats = runCatching {
            val audience = AudienceRepo.createContext(includeBlocked = false)
            val total = audience.unionCount
            val blocked = UsersRepo.countBlocked()
            val premium = PremiumRepo.countActive()
            val active7d = MessagesRepo.countActiveSince(System.currentTimeMillis() - 7L * DAY_MS)
            val activeInstalls = audience.filteredCount.coerceAtLeast(0L)
            AdminStatsSnapshot(total, activeInstalls, blocked, premium, active7d)
        }.getOrElse {
            println("ADMIN-STATS-ERR: ${it.message}")
            api.sendMessage(chatId, "Не удалось получить статистику. Проверьте логи.", parseMode = null)
            return
        }
        val total = stats.total.coerceAtLeast(0L)
        val blocked = stats.blocked.coerceAtLeast(0L)
        val activeInstalls = stats.activeInstalls.coerceAtLeast(0L)
        val premium = stats.premium.coerceAtLeast(0L)
        val active7d = stats.active7d.coerceAtLeast(0L)
        println("ADMIN-STATS-OK: total=$total activeInstalls=$activeInstalls premium=$premium active7d=$active7d blocked=$blocked")
        val message = buildString {
            appendLine("Статистика:")
            appendLine("• Установок бота: $total")
            appendLine("• Активных установок: $activeInstalls")
            appendLine("• Премиум-пользователей: $premium")
            appendLine("• Активно за 7 дней: $active7d")
            appendLine("• Заблокировали бота: $blocked")
        }.trimEnd()
        api.sendMessage(chatId, message, parseMode = null)
    }

    private fun showAdminMenu(chatId: Long, adminId: Long? = null) {
        adminStates.remove(chatId)
        val actor = adminId ?: chatId
        println("ADMIN-AUDIT: action=menu_show chat=$chatId actor=$actor")
        AdminAuditRepo.record(actor, action = "menu_show", target = chatId.toString(), meta = null)
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

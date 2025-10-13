package app.web.dto

// ===== Базовый ответ Telegram =====
data class TgApiResp<T>(
    val ok: Boolean,
    val result: T? = null,
    val error_code: Int? = null,
    val description: String? = null
)

// ===== Пользователь/чат/сообщения =====
data class TgApiUserMe(
    val id: Long,
    val is_bot: Boolean,
    val first_name: String,
    val username: String?
)

data class TgChat(
    val id: Long,
    val type: String = "private"
)

data class TgUser(
    val id: Long,
    val is_bot: Boolean,
    val first_name: String? = null,
    val username: String? = null
)

/**
 * ВАЖНО: добавлены поля:
 * - from: автор сообщения (для /whoami и grant по reply)
 * - reply_to_message: исходное сообщение, на которое ответили
 * - successful_payment: оставлено для платежей (если используете)
 */
data class TgMessage(
    val message_id: Int,
    val chat: TgChat,
    val text: String? = null,
    val from: TgUser? = null,
    val reply_to_message: TgMessage? = null,
    val successful_payment: TgSuccessfulPayment? = null
)

// ===== Обновления =====
data class TgUpdate(
    val update_id: Long,
    val message: TgMessage? = null,
    val edited_message: TgMessage? = null,
    val callback_query: TgCallbackQuery? = null,
    val pre_checkout_query: TgPreCheckoutQuery? = null
)

// ===== Callback Query =====
data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val message: TgMessage?,
    val data: String?
)

// ===== Разметка =====
interface ReplyMarkup

data class InlineKeyboardButton(
    val text: String,
    val callback_data: String
)

data class InlineKeyboardMarkup(
    val inline_keyboard: List<List<InlineKeyboardButton>>
) : ReplyMarkup

data class ReplyKeyboardRemove(val remove_keyboard: Boolean = true) : ReplyMarkup

// ===== SendMessage =====
data class TgSendMessage(
    val chat_id: Long,
    val text: String,
    val parse_mode: String? = null,
    val reply_markup: ReplyMarkup? = null
)

// ===================== PAYMENTS (на будущее/если уже включены) =====================

/** Цена (целое число в минимальных единицах валюты: копейки) */
data class LabeledPrice(val label: String, val amount: Int)

/** Pre-checkout запрос */
data class TgPreCheckoutQuery(
    val id: String,
    val from: TgUser,
    val currency: String,
    val total_amount: Int,
    val invoice_payload: String
)

/** Данные об успешной оплате */
data class TgSuccessfulPayment(
    val currency: String,
    val total_amount: Int,
    val invoice_payload: String,
    val provider_payment_charge_id: String,
    val telegram_payment_charge_id: String
)

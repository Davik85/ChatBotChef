package app.web.dto

// ---- Generic wrapper ----
data class TgApiResp<T>(
    val ok: Boolean,
    val result: T?,
    val error_code: Int? = null,
    val description: String? = null
)

// ---- getMe ----
data class TgApiUserMe(
    val id: Long,
    val is_bot: Boolean,
    val first_name: String?,
    val username: String?
)

// ---- Updates ----
data class TgUpdate(
    val update_id: Long,
    val message: TgMessage? = null,
    val edited_message: TgMessage? = null,
    val callback_query: TgCallbackQuery? = null
)

data class TgMessage(
    val message_id: Int,
    val chat: TgChat,
    val text: String? = null
)

data class TgChat(val id: Long)

// ---- Callback Query ----
data class TgCallbackQuery(
    val id: String,
    val from: TgUser?,
    val message: TgMessage?,
    val data: String?
)

data class TgUser(
    val id: Long,
    val is_bot: Boolean,
    val first_name: String?,
    val username: String?
)

// ---- sendMessage ----
data class TgSendMessage(
    val chat_id: Long,
    val text: String,
    val parse_mode: String? = null,
    val reply_markup: ReplyMarkup? = null
)

// ---- Keyboards ----
sealed interface ReplyMarkup

// Inline keyboard (наше меню)
data class InlineKeyboardMarkup(
    val inline_keyboard: List<List<InlineKeyboardButton>>
) : ReplyMarkup

data class InlineKeyboardButton(
    val text: String,
    val callback_data: String
)

// Reply keyboard (не используем, оставлено для совместимости)
data class ReplyKeyboardMarkup(
    val keyboard: List<List<KeyboardButton>>,
    val resize_keyboard: Boolean = true,
    val one_time_keyboard: Boolean = false,
    val is_persistent: Boolean = false
) : ReplyMarkup

data class KeyboardButton(val text: String)

data class ReplyKeyboardRemove(val remove_keyboard: Boolean = true) : ReplyMarkup

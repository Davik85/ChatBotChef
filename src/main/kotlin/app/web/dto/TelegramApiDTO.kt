package app.web.dto

// --- Generic API wrapper ---
data class TgApiResp<T>(
    val ok: Boolean,
    val result: T?,
    val error_code: Int? = null,
    val description: String? = null
)

// --- getMe ---
data class TgApiUserMe(
    val id: Long,
    val is_bot: Boolean,
    val first_name: String?,
    val username: String?
)

// --- updates / messages ---
data class TgUpdate(
    val update_id: Long,
    val message: TgMessage? = null,
    val edited_message: TgMessage? = null
)

data class TgMessage(
    val message_id: Long,
    val chat: TgChat,
    val text: String? = null
)

data class TgChat(val id: Long)

// --- sendMessage ---
data class TgSendMessage(
    val chat_id: Long,
    val text: String,
    val parse_mode: String? = null,
    val reply_markup: ReplyMarkup? = null
)

// --- Keyboards ---
sealed interface ReplyMarkup

data class ReplyKeyboardMarkup(
    val keyboard: List<List<KeyboardButton>>,
    val resize_keyboard: Boolean = true,
    val one_time_keyboard: Boolean = false,
    val is_persistent: Boolean = false
) : ReplyMarkup

data class KeyboardButton(
    val text: String
)

data class ReplyKeyboardRemove(
    val remove_keyboard: Boolean = true
) : ReplyMarkup

package app.telegram.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

// ==== БАЗОВЫЕ ОБЁРТКИ API ====

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgApiResp<T>(
    val ok: Boolean,
    val result: T? = null,
    val error_code: Int? = null,
    val description: String? = null,
    val parameters: TgResponseParameters? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgResponseParameters(
    val retry_after: Int? = null,
)

// ==== ОБЪЕКТЫ СООБЩЕНИЙ/ЧАТА/ПОЛЬЗОВАТЕЛЯ ====

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgChat(
    val id: Long,
    val type: String? = null,
    val title: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgUser(
    val id: Long,
    val is_bot: Boolean? = null,
    val first_name: String? = null,
    val last_name: String? = null,
    val username: String? = null,
    // Телеграм иногда присылает язык пользователя (например, "ru")
    val language_code: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgSuccessfulPayment(
    val currency: String,
    val total_amount: Int,                 // в минимальных единицах (копейки)
    val invoice_payload: String? = null,
    val provider_payment_charge_id: String? = null,
    val telegram_payment_charge_id: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgPhotoSize(
    val file_id: String,
    val width: Int? = null,
    val height: Int? = null,
    val file_unique_id: String? = null,
    val file_size: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgDocument(
    val file_id: String,
    val file_name: String? = null,
    val mime_type: String? = null,
    val file_size: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgVideo(
    val file_id: String,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null,
    val mime_type: String? = null,
    val file_size: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgVideoNote(
    val file_id: String,
    val length: Int? = null,
    val duration: Int? = null,
    val file_size: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgVoice(
    val file_id: String,
    val duration: Int? = null,
    val mime_type: String? = null,
    val file_size: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgAudio(
    val file_id: String,
    val duration: Int? = null,
    val performer: String? = null,
    val title: String? = null,
    val mime_type: String? = null,
    val file_size: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgSticker(
    val file_id: String,
    val width: Int? = null,
    val height: Int? = null,
    val emoji: String? = null,
    val file_size: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgAnimation(
    val file_id: String,
    val width: Int? = null,
    val height: Int? = null,
    val duration: Int? = null,
    val file_name: String? = null,
    val mime_type: String? = null,
    val file_size: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgMessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
    val user: TgUser? = null,
    val language: String? = null,
    val custom_emoji_id: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgMessage(
    val message_id: Int,
    val from: TgUser? = null,
    val chat: TgChat,
    val date: Long? = null,
    val text: String? = null,
    val caption: String? = null,
    val entities: List<TgMessageEntity>? = null,
    val caption_entities: List<TgMessageEntity>? = null,

    val photo: List<TgPhotoSize>? = null,
    val document: TgDocument? = null,
    val video: TgVideo? = null,
    val video_note: TgVideoNote? = null,
    val voice: TgVoice? = null,
    val audio: TgAudio? = null,
    val sticker: TgSticker? = null,
    val animation: TgAnimation? = null,

    // Платёж
    val successful_payment: TgSuccessfulPayment? = null,

    // Вспомогательные поля
    val reply_to_message: TgMessage? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgMessageId(val message_id: Int)

// ==== CALLBACK-КНОПКИ ====

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val message: TgMessage? = null,
    val data: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InlineKeyboardButton(
    val text: String,
    val callback_data: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class InlineKeyboardMarkup(
    val inline_keyboard: List<List<InlineKeyboardButton>>
)

// ==== ПЛАТЕЖИ / ИНВОЙС ====

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgLabeledPrice(
    val label: String,
    val amount: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgPreCheckoutQuery(
    val id: String,
    val from: TgUser,
    val currency: String,
    val total_amount: Int,
    val invoice_payload: String? = null
)

// ==== ОБНОВЛЕНИЯ ====

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgUpdate(
    val update_id: Long,

    // обычные сообщения
    val message: TgMessage? = null,
    val edited_message: TgMessage? = null,

    // нажатия инлайн-кнопок
    val callback_query: TgCallbackQuery? = null,

    // Telegram Payments
    val pre_checkout_query: TgPreCheckoutQuery? = null,

    // изменения статуса чата/бота
    val my_chat_member: TgChatMemberUpdated? = null,
    val chat_member: TgChatMemberUpdated? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgChatMember(
    val status: String? = null,
    val user: TgUser? = null,
    val is_member: Boolean? = null,
    val can_send_messages: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgChatMemberUpdated(
    val chat: TgChat,
    val from: TgUser? = null,
    val date: Long? = null,
    val old_chat_member: TgChatMember? = null,
    val new_chat_member: TgChatMember? = null
)

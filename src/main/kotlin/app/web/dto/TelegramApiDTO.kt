package app.web.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgApiUserMe(
    val id: Long,
    val is_bot: Boolean,
    val first_name: String? = null,
    val username: String? = null
)

data class TgApiErrorParams(val retry_after: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TgApiResp<T>(
    val ok: Boolean,
    val result: T? = null,
    val error_code: Int? = null,
    val description: String? = null,
    val parameters: TgApiErrorParams? = null
)

data class TgSendMessage(
    val chat_id: Long,
    val text: String,
    val parse_mode: String? = null,
    val disable_web_page_preview: Boolean? = null,
    val disable_notification: Boolean? = null,
    val reply_to_message_id: Long? = null
)

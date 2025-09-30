package app.web.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class LpChat(val id: Long)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LpUser(
    val id: Long,
    val first_name: String? = null,
    val username: String? = null,
    val is_bot: Boolean? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LpMessage(
    val message_id: Long,
    val date: Long,
    val text: String?,
    val chat: LpChat,
    val from: LpUser?
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LpUpdate(val update_id: Long, val message: LpMessage?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LpErrParams(val retry_after: Int? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class LpResp<T>(
    val ok: Boolean,
    val result: T?,
    val error_code: Int? = null,
    val description: String? = null,
    val parameters: LpErrParams? = null
)

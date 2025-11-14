package app.logic.user

import app.db.UsersRepo
import app.telegram.dto.TgUser

object UserService {
    private fun sanitize(raw: String?): String? = raw
        ?.replace("\n", " ")
        ?.replace("\r", " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(200)

    fun ensureUser(from: TgUser?, source: String, now: Long = System.currentTimeMillis()) {
        val user = from ?: return
        val userId = user.id
        if (userId <= 0L) return
        runCatching { UsersRepo.recordSeen(userId, now) }
            .onSuccess { result ->
                val insertedFlag = if (result.inserted) 1 else 0
                println("DB-USERS-UPSERT: id=$userId source=$source inserted=$insertedFlag")
            }
            .onFailure {
                println("DB-USERS-UPSERT-ERR: id=$userId source=$source reason=${it.message}")
            }
    }

    fun markInteraction(userId: Long, source: String, now: Long = System.currentTimeMillis()) {
        if (userId <= 0L) return
        runCatching { UsersRepo.recordSeen(userId, now) }
            .onFailure { println("USER-SEEN-ERR: user_id=$userId source=$source reason=${it.message}") }
        runCatching { UsersRepo.markBlocked(userId, blocked = false, now = now) }
            .onSuccess { result ->
                if (result.changed && result.previousBlocked && !result.currentBlocked) {
                    println("USER-UNBLOCKED: user_id=$userId source=$source")
                }
            }
            .onFailure { println("USER-UNBLOCKED-ERR: user_id=$userId source=$source reason=${it.message}") }
    }

    fun markBlocked(
        userId: Long,
        source: String,
        reason: String? = null,
        status: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        if (userId <= 0L) return
        runCatching { UsersRepo.recordSeen(userId, now) }
            .onFailure { println("USER-BLOCKED-SEEN-ERR: user_id=$userId source=$source reason=${it.message}") }
        val sanitizedReason = sanitize(reason)
        val sanitizedStatus = sanitize(status)
        runCatching { UsersRepo.markBlocked(userId, blocked = true, now = now) }
            .onSuccess { result ->
                if (result.changed && result.currentBlocked) {
                    val parts = mutableListOf(
                        "USER-BLOCKED: user_id=$userId",
                        "source=$source",
                    )
                    sanitizedReason?.let { parts += "reason=$it" }
                    sanitizedStatus?.let { parts += "status=$it" }
                    println(parts.joinToString(separator = " "))
                }
            }
            .onFailure { println("USER-BLOCKED-ERR: user_id=$userId source=$source reason=${it.message}") }
    }
}

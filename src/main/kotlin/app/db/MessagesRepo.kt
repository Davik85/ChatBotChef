package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.countDistinct

object MessagesRepo {
    private const val MAX_STORED_TEXT = 4000

    private fun normalizeRole(role: String): String = when (role.lowercase()) {
        "user", "assistant", "system" -> role.lowercase()
        else -> "user"
    }

    fun record(
        userId: Long,
        text: String,
        role: String = "user",
        ts: Long = System.currentTimeMillis()
    ) = transaction {
        val normalized = if (text.length > MAX_STORED_TEXT) {
            text.substring(0, MAX_STORED_TEXT)
        } else {
            text
        }
        val trimmed = normalized.trim()
        if (trimmed.isEmpty()) return@transaction
        val safeRole = normalizeRole(role)
        Messages.insert {
            it[Messages.user_id] = userId
            it[Messages.ts] = ts
            it[Messages.text] = trimmed
            it[Messages.role] = safeRole
        }
    }

    fun countActiveSince(fromMs: Long): Long = transaction {
        val countExpr = Messages.user_id.countDistinct()
        Messages
            .slice(countExpr)
            .select { (Messages.ts.greaterEq(fromMs)) and (Messages.role eq "user") }
            .firstOrNull()
            ?.get(countExpr)
            ?.toLong()
            ?: 0L
    }

    fun countUserMessagesSince(userId: Long, fromMs: Long): Long = transaction {
        Messages
            .select {
                (Messages.user_id eq userId) and
                    (Messages.ts.greaterEq(fromMs)) and
                    (Messages.role eq "user")
            }
            .count()
            .toLong()
    }

    fun countTotalUserMessages(userId: Long): Long = transaction {
        Messages
            .select { (Messages.user_id eq userId) and (Messages.role eq "user") }
            .count()
            .toLong()
    }
}

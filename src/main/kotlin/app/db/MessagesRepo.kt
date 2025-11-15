package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

object MessagesRepo {

    private fun resolveDirection(role: String): String = when (role.lowercase()) {
        "user" -> "in"
        else -> "out"
    }

    private fun resolveKind(role: String): String = when (role.lowercase()) {
        "user", "assistant" -> "text"
        "system" -> "other"
        else -> "other"
    }

    fun record(
        userId: Long,
        text: String,
        role: String = "user",
        ts: Long = System.currentTimeMillis()
    ) = transaction {
        if (userId <= 0L) return@transaction
        val direction = resolveDirection(role)
        val kind = resolveKind(role)
        Messages.insert {
            it[Messages.userId] = userId
            it[Messages.timestamp] = ts
            it[Messages.direction] = direction
            it[Messages.kind] = kind
        }
    }

    fun countActiveSince(fromMs: Long, onlyActiveUsers: Boolean = false): Long {
        if (onlyActiveUsers) {
            return UsersRepo.countActiveSince(fromMs)
        }
        return transaction {
            val countExpr = Messages.userId.countDistinct()
            val baseCondition = (Messages.timestamp greater fromMs) and (Messages.direction eq "in")
            Messages
                .slice(countExpr)
                .select { baseCondition }
                .firstOrNull()
                ?.get(countExpr)
                ?.toLong()
                ?: 0L
        }
    }

    fun countUserMessagesSince(userId: Long, fromMs: Long): Long = transaction {
        Messages
            .select {
                (Messages.userId eq userId) and
                    (Messages.timestamp greaterEq fromMs) and
                    (Messages.direction eq "in")
            }
            .count()
            .toLong()
    }

    fun countTotalUserMessages(userId: Long): Long = transaction {
        Messages
            .select { (Messages.userId eq userId) and (Messages.direction eq "in") }
            .count()
            .toLong()
    }
}

package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

object MessagesRepo {
    private const val MAX_STORED_TEXT = 4000

    fun record(userId: Long, text: String, ts: Long = System.currentTimeMillis()) = transaction {
        val normalized = if (text.length > MAX_STORED_TEXT) {
            text.substring(0, MAX_STORED_TEXT)
        } else {
            text
        }
        Messages.insert {
            it[Messages.user_id] = userId
            it[Messages.ts] = ts
            it[Messages.text] = normalized
        }
    }

    fun countActiveSince(fromMs: Long): Long = transaction {
        val distinctUsers = Messages.user_id.countDistinct()
        Messages
            .slice(distinctUsers)
            .select { Messages.ts.greaterEq(fromMs) }
            .singleOrNull()
            ?.get(distinctUsers)
            ?.toLong()
            ?: 0L
    }
}

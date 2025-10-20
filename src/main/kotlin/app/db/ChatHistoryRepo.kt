package app.db

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction

object ChatHistoryRepo {

    data class HistoryItem(
        val role: String,
        val text: String,
        val ts: Long
    )

    fun append(userId: Long, mode: String, role: String, text: String, ts: Long = System.currentTimeMillis()) {
        transaction {
            ChatHistory.insert {
                it[ChatHistory.user_id] = userId
                it[ChatHistory.mode] = mode
                it[ChatHistory.role] = role
                it[ChatHistory.text] = text
                it[ChatHistory.ts] = ts
            }
        }
    }

    fun fetchLast(userId: Long, mode: String, limit: Int): List<HistoryItem> = transaction {
        ChatHistory
            .select { (ChatHistory.user_id eq userId) and (ChatHistory.mode eq mode) }
            .orderBy(ChatHistory.ts to SortOrder.DESC)
            .limit(limit)
            .map {
                HistoryItem(
                    role = it[ChatHistory.role],
                    text = it[ChatHistory.text],
                    ts = it[ChatHistory.ts]
                )
            }
            .asReversed()
    }

    fun clear(userId: Long, mode: String) {
        transaction {
            ChatHistory.deleteWhere { (ChatHistory.user_id eq userId) and (ChatHistory.mode eq mode) }
        }
    }
}

package app.logic

import app.db.MemoryNotesV2
import app.db.Messages
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object MemoryService {

    fun getNote(userId: Long): String? = transaction {
        MemoryNotesV2
            .slice(MemoryNotesV2.note)
            .select { MemoryNotesV2.userId eq userId }
            .firstOrNull()
            ?.get(MemoryNotesV2.note)
    }

    fun setNote(userId: Long, note: String) = transaction {
        val exists = MemoryNotesV2
            .slice(MemoryNotesV2.userId)
            .select { MemoryNotesV2.userId eq userId }
            .limit(1)
            .any()

        if (exists) {
            MemoryNotesV2.update({ MemoryNotesV2.userId eq userId }) {
                it[MemoryNotesV2.note] = note
                it[MemoryNotesV2.updatedAt] = System.currentTimeMillis()
            }
        } else {
            MemoryNotesV2.insert {
                it[MemoryNotesV2.userId] = userId
                it[MemoryNotesV2.note] = note
                it[MemoryNotesV2.updatedAt] = System.currentTimeMillis()
            }
        }
    }

    fun append(userId: Long, role: String, content: String, ts: Long) = transaction {
        Messages.insert {
            it[Messages.userId] = userId
            it[Messages.role] = role
            it[Messages.content] = content
            it[Messages.ts] = ts
        }
    }

    /** Возвращает последние N реплик диалога в виде пар (role, content) */
    fun recentDialog(userId: Long, limit: Int = 10): List<Pair<String, String>> = transaction {
        Messages
            .slice(Messages.role, Messages.content)
            .select { Messages.userId eq userId }
            .orderBy(Messages.ts, SortOrder.DESC)
            .limit(limit)
            .map { it[Messages.role] to it[Messages.content] }
            .reversed() // хронологический порядок
    }
}

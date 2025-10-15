package app.logic

import app.db.MemoryNotesV2
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

object MemoryService {

    /** Вернуть сохранённую заметку пользователя или null. */
    fun getNote(userId: Long): String? = transaction {
        MemoryNotesV2
            .slice(MemoryNotesV2.note)
            .select { MemoryNotesV2.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?.get(MemoryNotesV2.note)
    }

    /** Сохранить/обновить заметку пользователя. */
    fun setNote(userId: Long, note: String) = transaction {
        val exists = MemoryNotesV2
            .slice(MemoryNotesV2.user_id)
            .select { MemoryNotesV2.user_id eq userId }
            .limit(1)
            .any()

        if (exists) {
            MemoryNotesV2.update({ MemoryNotesV2.user_id eq userId }) {
                it[MemoryNotesV2.note] = note
                it[MemoryNotesV2.ts] = System.currentTimeMillis()
            }
        } else {
            MemoryNotesV2.insert {
                it[MemoryNotesV2.user_id] = userId
                it[MemoryNotesV2.note] = note
                it[MemoryNotesV2.ts] = System.currentTimeMillis()
            }
        }
    }

    /** Удалить заметку пользователя. */
    fun clearNote(userId: Long) = transaction {
        MemoryNotesV2.deleteWhere { MemoryNotesV2.user_id eq userId }
    }
}

package app.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.between
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.math.max

object PremiumRepo {

    fun getUntil(userId: Long): Long? = transaction {
        PremiumUsers
            .slice(PremiumUsers.until_ts)
            .select { PremiumUsers.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?.get(PremiumUsers.until_ts)
    }

    fun grantDays(userId: Long, days: Int) = transaction {
        val now = System.currentTimeMillis()
        val cur = getUntil(userId) ?: now
        val base = max(cur, now)
        val plus = days.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        val newUntil = base + plus

        val updated = PremiumUsers.update({ PremiumUsers.user_id eq userId }) {
            it[until_ts] = newUntil
        }
        if (updated == 0) {
            PremiumUsers.insert {
                it[PremiumUsers.user_id] = userId
                it[until_ts] = newUntil
            }
        }
        // Сбрасываем отметки напоминаний, чтобы на новый период снова прислать 3d/1d/0d
        PremiumReminders.deleteWhere { PremiumReminders.user_id eq userId }
    }

    // --- Напоминания ---

    fun listExpiringBetween(fromMs: Long, toMs: Long, kind: String): List<Long> = transaction {
        // выбираем кандидатов по периоду
        val candidates = PremiumUsers
            .slice(PremiumUsers.user_id)
            .select { PremiumUsers.until_ts.between(fromMs, toMs) }
            .map { it[PremiumUsers.user_id] }

        if (candidates.isEmpty()) return@transaction emptyList<Long>()

        // убираем тех, кому уже слали это напоминание
        val already = PremiumReminders
            .slice(PremiumReminders.user_id)
            .select { (PremiumReminders.user_id inList candidates) and (PremiumReminders.kind eq kind) }
            .map { it[PremiumReminders.user_id] }
            .toSet()

        candidates.filterNot { already.contains(it) }
    }

    /** Помечаем, что отправили напоминание */
    fun markReminded(userId: Long, kind: String) = transaction {
        PremiumReminders.insertIgnore {
            it[PremiumReminders.user_id] = userId
            it[PremiumReminders.kind] = kind
            it[sent_ts] = System.currentTimeMillis()
        }
    }
}

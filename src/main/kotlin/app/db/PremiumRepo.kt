package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.between
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import kotlin.math.max

object PremiumRepo {

    fun isActive(userId: Long): Boolean {
        val until = getUntil(userId) ?: return false
        return until > System.currentTimeMillis()
    }

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
        PremiumReminders.deleteWhere { PremiumReminders.user_id eq userId }
    }

    fun markReminderSent(userId: Long, kind: String): Boolean = transaction {
        val exists = PremiumReminders
            .select { (PremiumReminders.user_id eq userId) and (PremiumReminders.kind eq kind) }
            .limit(1)
            .count() > 0
        if (exists) return@transaction false
        PremiumReminders.insert {
            it[PremiumReminders.user_id] = userId
            it[PremiumReminders.kind] = kind
            it[sent_ts] = System.currentTimeMillis()
        }
        true
    }

    fun forEachExpiringInWindow(fromMs: Long, toMs: Long, cb: (Long, Long) -> Unit) {
        transaction {
            PremiumUsers
                .slice(PremiumUsers.user_id, PremiumUsers.until_ts)
                .select { PremiumUsers.until_ts.between(fromMs, toMs) }
                .forEach { row ->
                    cb(row[PremiumUsers.user_id], row[PremiumUsers.until_ts])
                }
        }
    }

    fun countActive(now: Long = System.currentTimeMillis()): Long = transaction {
        PremiumUsers
            .select { PremiumUsers.until_ts.greater(now) }
            .count()
    }
}

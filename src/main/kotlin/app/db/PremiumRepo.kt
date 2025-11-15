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
            .slice(PremiumUsers.premiumUntil)
            .select { PremiumUsers.userId eq userId }
            .limit(1)
            .firstOrNull()
            ?.get(PremiumUsers.premiumUntil)
    }

    fun grantDays(userId: Long, days: Int) = transaction {
        val now = System.currentTimeMillis()
        val currentUntil = getUntil(userId) ?: now
        val base = max(currentUntil, now)
        val plus = days.coerceAtLeast(1) * 24L * 60L * 60L * 1000L
        val newUntil = base + plus

        val updated = PremiumUsers.update({ PremiumUsers.userId eq userId }) {
            it[premiumUntil] = newUntil
        }
        if (updated == 0) {
            PremiumUsers.insert {
                it[PremiumUsers.userId] = userId
                it[premiumUntil] = newUntil
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
            it[PremiumReminders.sent_ts] = System.currentTimeMillis()
        }
        true
    }

    fun forEachExpiringInWindow(fromMs: Long, toMs: Long, cb: (Long, Long) -> Unit) {
        transaction {
            PremiumUsers
                .slice(PremiumUsers.userId, PremiumUsers.premiumUntil)
                .select { PremiumUsers.premiumUntil.between(fromMs, toMs) }
                .forEach { row ->
                    cb(row[PremiumUsers.userId], row[PremiumUsers.premiumUntil])
                }
        }
    }

    fun countActive(now: Long = System.currentTimeMillis()): Long = transaction {
        PremiumUsers
            .select { PremiumUsers.premiumUntil.greater(now) }
            .count()
    }
}

package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.time.ZoneOffset

object UsageCountersRepo {

    data class Counters(
        val userId: Long,
        val totalMessages: Int,
        val dailyMessages: Int,
        val dailyResetAt: Long,
    )

    private fun startOfDayUtc(now: Long): Long {
        return Instant.ofEpochMilli(now)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()
    }

    fun ensureRow(userId: Long, now: Long = System.currentTimeMillis()) {
        if (userId <= 0L) return
        val todayStart = startOfDayUtc(now)
        transaction {
            val inserted = UsageCounters.insertIgnore { row ->
                row[UsageCounters.userId] = userId
                row[UsageCounters.totalMessages] = 0
                row[UsageCounters.dailyMessages] = 0
                row[UsageCounters.dailyResetAt] = todayStart
            }
            if (inserted.insertedCount == 0) {
                val existing = UsageCounters
                    .select { UsageCounters.userId eq userId }
                    .limit(1)
                    .firstOrNull()
                if (existing != null && existing[UsageCounters.dailyResetAt] <= 0L) {
                    UsageCounters.update({ UsageCounters.userId eq userId }) { update ->
                        update[UsageCounters.dailyResetAt] = todayStart
                    }
                }
            }
        }
    }

    fun getOrCreate(userId: Long, now: Long = System.currentTimeMillis()): Counters = transaction {
        val todayStart = startOfDayUtc(now)
        val row = UsageCounters
            .select { UsageCounters.userId eq userId }
            .limit(1)
            .firstOrNull()
        if (row == null) {
            UsageCounters.insertIgnore { insert ->
                insert[UsageCounters.userId] = userId
                insert[UsageCounters.totalMessages] = 0
                insert[UsageCounters.dailyMessages] = 0
                insert[UsageCounters.dailyResetAt] = todayStart
            }
            return@transaction Counters(userId, 0, 0, todayStart)
        }
        var total = row[UsageCounters.totalMessages]
        var daily = row[UsageCounters.dailyMessages]
        var resetAt = row[UsageCounters.dailyResetAt]
        if (resetAt <= 0L || todayStart > resetAt) {
            daily = 0
            resetAt = todayStart
            UsageCounters.update({ UsageCounters.userId eq userId }) { update ->
                update[UsageCounters.dailyMessages] = daily
                update[UsageCounters.dailyResetAt] = resetAt
            }
        }
        Counters(userId, total, daily, resetAt)
    }

    fun incrementIfAllowed(
        userId: Long,
        now: Long = System.currentTimeMillis(),
        totalLimit: Int,
        dailyLimit: Int
    ): Boolean = transaction {
        if (userId <= 0L) return@transaction false
        val effectiveTotalLimit = if (totalLimit <= 0) Int.MAX_VALUE else totalLimit
        val effectiveDailyLimit = if (dailyLimit <= 0) Int.MAX_VALUE else dailyLimit
        val todayStart = startOfDayUtc(now)

        val row = UsageCounters
            .select { UsageCounters.userId eq userId }
            .limit(1)
            .firstOrNull()

        if (row == null) {
            val allowed = effectiveTotalLimit > 0 && effectiveDailyLimit > 0
            if (!allowed) return@transaction false
            UsageCounters.insertIgnore { insert ->
                insert[UsageCounters.userId] = userId
                insert[UsageCounters.totalMessages] = 1
                insert[UsageCounters.dailyMessages] = 1
                insert[UsageCounters.dailyResetAt] = todayStart
            }
            return@transaction true
        }

        var total = row[UsageCounters.totalMessages]
        var daily = row[UsageCounters.dailyMessages]
        var resetAt = row[UsageCounters.dailyResetAt]
        var resetChanged = false
        if (resetAt <= 0L || todayStart > resetAt) {
            daily = 0
            resetAt = todayStart
            resetChanged = true
        }

        val allowed = total < effectiveTotalLimit && daily < effectiveDailyLimit
        if (!allowed) {
            if (resetChanged) {
                UsageCounters.update({ UsageCounters.userId eq userId }) { update ->
                    update[UsageCounters.dailyMessages] = daily
                    update[UsageCounters.dailyResetAt] = resetAt
                }
            }
            return@transaction false
        }

        total += 1
        daily += 1
        UsageCounters.update({ UsageCounters.userId eq userId }) { update ->
            update[UsageCounters.totalMessages] = total
            update[UsageCounters.dailyMessages] = daily
            update[UsageCounters.dailyResetAt] = resetAt
        }
        true
    }
}

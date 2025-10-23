package app.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

object UsageCounters : Table("usage_counters") {
    val user_id = long("user_id").uniqueIndex()
    val total_used = integer("total_used").default(0)
    override val primaryKey = PrimaryKey(user_id)
}

object UsageCountersRepo {
    fun getTotalUsed(userId: Long): Int = transaction {
        UsageCounters
            .slice(UsageCounters.total_used)
            .select { UsageCounters.user_id eq userId }
            .singleOrNull()
            ?.get(UsageCounters.total_used)
            ?: 0
    }

    fun inc(userId: Long, delta: Int = 1): Int = transaction {
        val row = UsageCounters
            .slice(UsageCounters.total_used)
            .select { UsageCounters.user_id eq userId }
            .singleOrNull()
        val curr = row?.get(UsageCounters.total_used) ?: 0
        val next = curr + delta
        if (row == null) {
            UsageCounters.insert {
                it[user_id] = userId
                it[total_used] = next
            }
        } else {
            UsageCounters.update({ UsageCounters.user_id eq userId }) { it[total_used] = next }
        }
        next
    }
}

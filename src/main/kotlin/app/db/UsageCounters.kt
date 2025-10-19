package app.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

object UsageCounters : Table(name = "usage_counters") {
    val user_id = long("user_id").uniqueIndex()
    val used = integer("used").default(0)

    override val primaryKey = PrimaryKey(user_id)
}

object UsageRepo {

    fun getUsed(userId: Long): Int = transaction {
        UsageCounters
            .slice(UsageCounters.used)
            .select { UsageCounters.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?.get(UsageCounters.used)
            ?: 0
    }

    fun incrementAndGet(userId: Long): Int = transaction {
        val current = UsageCounters
            .slice(UsageCounters.used)
            .select { UsageCounters.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?.get(UsageCounters.used)

        if (current == null) {
            UsageCounters.insert {
                it[UsageCounters.user_id] = userId
                it[used] = 1
            }
            1
        } else {
            val next = current + 1
            UsageCounters.update({ UsageCounters.user_id eq userId }) {
                it[used] = next
            }
            next
        }
    }
}

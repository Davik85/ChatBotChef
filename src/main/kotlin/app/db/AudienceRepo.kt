package app.db

import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.*

object AudienceRepo {
    data class AudienceContext(
        val includeBlocked: Boolean,
        val hasUsersTable: Boolean,
        val baseUnionQuery: String?,
        val usersCount: Long,
        val messagesCount: Long,
        val premiumCount: Long,
        val usageCount: Long?,
        val unionCount: Long,
        val blockedFiltered: Long,
    ) {
        val filteredCount: Long
            get() = unionCount - blockedFiltered
    }

    fun createContext(includeBlocked: Boolean): AudienceContext = transaction {
        val hasUsers = tableExists("users") && columnExists("users", "user_id")
        val hasMessages = tableExists("messages") && columnExists("messages", "user_id")
        val hasPremium = tableExists("premium_users") && columnExists("premium_users", "user_id")
        val hasUsage = tableExists("usage_counters") && columnExists("usage_counters", "user_id")

        val usersCount = if (hasUsers) countDistinct("users") else 0L
        val messagesCount = if (hasMessages) countDistinct("messages") else 0L
        val premiumCount = if (hasPremium) countDistinct("premium_users") else 0L
        val usageCount = if (hasUsage) countDistinct("usage_counters") else null

        val sources = mutableListOf<String>()
        if (hasUsers) sources += selectDistinct("users")
        if (hasMessages) sources += selectDistinct("messages")
        if (hasPremium) sources += selectDistinct("premium_users")
        if (hasUsage) sources += selectDistinct("usage_counters")

        val baseUnion = if (sources.isEmpty()) {
            null
        } else {
            """
                SELECT DISTINCT CAST(user_id AS INTEGER) AS user_id
                FROM (
                    ${sources.joinToString("\nUNION\n")}
                )
                WHERE user_id IS NOT NULL AND TRIM(CAST(user_id AS TEXT)) != ''
            """.trimIndent()
        }

        val unionCount = if (baseUnion == null) {
            0L
        } else {
            execAndCount("""SELECT COUNT(1) AS total FROM ( $baseUnion ) AS audience""")
        }

        val blockedFiltered = if (!includeBlocked && hasUsers && unionCount > 0) {
            execAndCount(
                """
                    SELECT COUNT(1) AS total
                    FROM ( $baseUnion ) AS audience
                    JOIN users ON users.user_id = audience.user_id
                    WHERE users.blocked = 1 OR users.blocked_ts > 0
                """.trimIndent()
            )
        } else {
            0L
        }

        val logParts = mutableListOf(
            "AUDIENCE: users=$usersCount",
            "messages=$messagesCount",
            "premium=$premiumCount"
        )
        if (hasUsage) {
            logParts += "usage=${usageCount ?: 0L}"
        }
        logParts += "union=$unionCount"
        logParts += "blockedFiltered=$blockedFiltered"
        println(logParts.joinToString(separator = " "))

        AudienceContext(
            includeBlocked = includeBlocked,
            hasUsersTable = hasUsers,
            baseUnionQuery = baseUnion,
            usersCount = usersCount,
            messagesCount = messagesCount,
            premiumCount = premiumCount,
            usageCount = usageCount,
            unionCount = unionCount,
            blockedFiltered = blockedFiltered,
        )
    }

    fun loadPage(context: AudienceContext, offset: Long, limit: Int): List<Long> {
        if (context.baseUnionQuery.isNullOrBlank() || limit <= 0) return emptyList()
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        return transaction {
            val baseQuery = context.baseUnionQuery
            val sql = if (!context.includeBlocked && context.hasUsersTable) {
                """
                    SELECT audience.user_id AS user_id
                    FROM ( $baseQuery ) AS audience
                    LEFT JOIN users ON users.user_id = audience.user_id
                    WHERE COALESCE(users.blocked, 0) = 0 AND COALESCE(users.blocked_ts, 0) <= 0
                    ORDER BY audience.user_id ASC
                    LIMIT $safeLimit OFFSET $safeOffset
                """.trimIndent()
            } else {
                """
                    SELECT audience.user_id AS user_id
                    FROM ( $baseQuery ) AS audience
                    ORDER BY audience.user_id ASC
                    LIMIT $safeLimit OFFSET $safeOffset
                """.trimIndent()
            }
            exec(sql) { rs ->
                val result = mutableListOf<Long>()
                while (rs?.next() == true) {
                    val value = rs.getLong("user_id")
                    if (value > 0L) {
                        result += value
                    }
                }
                result
            } ?: emptyList()
        }
    }

    private fun Transaction.countDistinct(table: String): Long {
        val sql = """
            SELECT COUNT(DISTINCT user_id) AS total
            FROM $table
            WHERE user_id IS NOT NULL AND TRIM(CAST(user_id AS TEXT)) != ''
        """.trimIndent()
        return execAndCount(sql)
    }

    private fun Transaction.selectDistinct(table: String): String =
        "SELECT DISTINCT user_id FROM $table WHERE user_id IS NOT NULL AND TRIM(CAST(user_id AS TEXT)) != ''"

    private fun Transaction.execAndCount(sql: String): Long =
        exec(sql) { rs -> if (rs?.next() == true) rs.getLong("total") else 0L } ?: 0L

    private fun Transaction.tableExists(name: String): Boolean =
        exec("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'") { rs ->
            var found = false
            while (rs?.next() == true) found = true
            found
        } ?: false

    private fun Transaction.columnExists(table: String, column: String): Boolean =
        exec("PRAGMA table_info($table)") { rs ->
            var ok = false
            while (rs?.next() == true) {
                if (rs.getString("name") == column) {
                    ok = true
                    break
                }
            }
            ok
        } ?: false
}

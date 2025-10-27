package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.countDistinct
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and

object UsersRepo {

    data class UserInfo(
        val userId: Long,
        val firstSeen: Long,
        val blockedTs: Long,
    )

    fun touch(userId: Long, now: Long = System.currentTimeMillis()): Boolean = transaction {
        val inserted = Users.insertIgnore {
            it[Users.user_id] = userId
            it[Users.first_seen] = now
            it[Users.blocked_ts] = 0L
        }
        if (inserted.insertedCount > 0) return@transaction true
        val updatedFirstSeen = Users.update({ (Users.user_id eq userId) and (Users.first_seen eq 0L) }) {
            it[Users.first_seen] = now
        } > 0
        Users.update({ Users.user_id eq userId }) {
            it[Users.blocked_ts] = 0L
        }
        updatedFirstSeen
    }

    fun getAllUserIds(includeBlocked: Boolean = false): List<Long> = transaction {
        val query = if (includeBlocked) {
            Users.slice(Users.user_id).selectAll()
        } else {
            Users.slice(Users.user_id).select { Users.blocked_ts eq 0L }
        }
        query.map { it[Users.user_id] }
    }

    fun countUsers(includeBlocked: Boolean = true): Long = transaction {
        val distinctUsers = Users.user_id.countDistinct()
        val query = if (includeBlocked) {
            Users.slice(distinctUsers).selectAll()
        } else {
            Users.slice(distinctUsers).select { Users.blocked_ts eq 0L }
        }
        query.firstOrNull()?.get(distinctUsers)?.toLong() ?: 0L
    }

    fun countBlocked(): Long = transaction {
        Users
            .select { Users.blocked_ts greater 0L }
            .count()
            .toLong()
    }

    fun exists(userId: Long): Boolean = transaction {
        Users
            .slice(Users.user_id)
            .select { Users.user_id eq userId }
            .limit(1)
            .any()
    }

    fun find(userId: Long): UserInfo? = transaction {
        Users
            .select { Users.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?.let {
                UserInfo(
                    userId = it[Users.user_id],
                    firstSeen = it[Users.first_seen],
                    blockedTs = it[Users.blocked_ts]
                )
            }
    }

    fun markBlocked(userId: Long, blocked: Boolean, now: Long = System.currentTimeMillis()): Boolean = transaction {
        val value = if (blocked) now else 0L
        Users.update({ Users.user_id eq userId }) { row ->
            row[Users.blocked_ts] = value
        } > 0
    }

    fun repairOrphans(source: String? = null, now: Long = System.currentTimeMillis()): Long {
        val inserted = transaction {
            fun tableExists(name: String): Boolean =
                exec("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'") { rs ->
                    var found = false
                    while (rs?.next() == true) found = true
                    found
                } ?: false

            if (!tableExists("users")) return@transaction 0L

            fun run(sql: String) {
                exec(sql)
            }

            val before = exec("SELECT COUNT(*) AS cnt FROM users") { rs ->
                var total = 0L
                while (rs?.next() == true) total = rs.getLong("cnt")
                total
            } ?: 0L

            val nowValue = now

            if (tableExists("messages")) {
                run(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen, blocked_ts)
                        SELECT user_id,
                               COALESCE(MIN(ts), $nowValue) AS first_seen,
                               0 AS blocked_ts
                        FROM messages
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent()
                )
            }

            if (tableExists("chat_history")) {
                run(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen, blocked_ts)
                        SELECT user_id,
                               COALESCE(MIN(ts), $nowValue) AS first_seen,
                               0 AS blocked_ts
                        FROM chat_history
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent()
                )
            }

            if (tableExists("memory_notes_v2")) {
                run(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen, blocked_ts)
                        SELECT user_id,
                               COALESCE(MIN(ts), $nowValue) AS first_seen,
                               0 AS blocked_ts
                        FROM memory_notes_v2
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent()
                )
            }

            if (tableExists("usage_counters")) {
                run(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen, blocked_ts)
                        SELECT user_id,
                               $nowValue AS first_seen,
                               0 AS blocked_ts
                        FROM usage_counters
                        WHERE user_id IS NOT NULL AND user_id > 0;
                    """.trimIndent()
                )
            }

            if (tableExists("premium_users")) {
                run(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen, blocked_ts)
                        SELECT user_id,
                               $nowValue AS first_seen,
                               0 AS blocked_ts
                        FROM premium_users
                        WHERE user_id IS NOT NULL AND user_id > 0;
                    """.trimIndent()
                )
            }

            if (tableExists("premium_reminders")) {
                run(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen, blocked_ts)
                        SELECT user_id,
                               COALESCE(MIN(sent_ts), $nowValue) AS first_seen,
                               0 AS blocked_ts
                        FROM premium_reminders
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent()
                )
            }

            if (tableExists("payments")) {
                run(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen, blocked_ts)
                        SELECT user_id,
                               COALESCE(MIN(created_at), $nowValue) AS first_seen,
                               0 AS blocked_ts
                        FROM payments
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent()
                )
            }

            if (tableExists("user_stats")) {
                run(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen, blocked_ts)
                        SELECT user_id,
                               $nowValue AS first_seen,
                               0 AS blocked_ts
                        FROM user_stats
                        WHERE user_id IS NOT NULL AND user_id > 0;
                    """.trimIndent()
                )
            }

            val after = exec("SELECT COUNT(*) AS cnt FROM users") { rs ->
                var total = before
                while (rs?.next() == true) total = rs.getLong("cnt")
                total
            } ?: before

            (after - before).coerceAtLeast(0L)
        }

        if (source != null && inserted > 0) {
            println("USERS: backfilled $inserted existing users source=$source")
        }

        return inserted
    }
}

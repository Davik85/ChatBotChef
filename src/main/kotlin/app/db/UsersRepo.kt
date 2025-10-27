package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.min

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
        val query = if (includeBlocked) {
            Users.selectAll()
        } else {
            Users.select { Users.blocked_ts eq 0L }
        }
        query.count().toLong()
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
            if (!tableExists("users")) return@transaction 0L

            val candidates = mutableSetOf<Long>()

            fun collect(table: String, column: String) {
                if (!tableExists(table) || !columnExists(table, column)) return
                exec(
                    """
                        SELECT DISTINCT $column AS user_id
                        FROM $table
                        WHERE $column IS NOT NULL
                    """.trimIndent()
                ) { rs ->
                    while (rs?.next() == true) {
                        val raw = rs.getString("user_id")
                        val parsed = raw?.trim()?.toLongOrNull()
                        val id = parsed ?: run {
                            val numeric = rs.getLong("user_id")
                            if (rs.wasNull()) null else numeric
                        }
                        if (id != null && id > 0) {
                            candidates += id
                        }
                    }
                }
            }

            collect("messages", "user_id")
            collect("chat_history", "user_id")
            collect("memory_notes_v2", "user_id")
            collect("usage_counters", "user_id")
            collect("premium_users", "user_id")
            collect("premium_reminders", "user_id")
            collect("payments", "user_id")
            collect("user_stats", "user_id")

            var insertedCount = 0L
            for (userId in candidates) {
                val (success, insertedNew) = ensureUserInternal(userId, now)
                if (success && insertedNew) {
                    insertedCount += 1
                }
            }
            insertedCount
        }

        if (source != null && inserted > 0) {
            println("USERS: backfilled $inserted existing users source=$source")
        }

        return inserted
    }

    fun repairUser(userId: Long, source: String? = null, now: Long = System.currentTimeMillis()): Boolean {
        val (success, insertedNew) = transaction {
            if (!tableExists("users")) return@transaction false to false
            ensureUserInternal(userId, now)
        }
        if (success && insertedNew && source != null) {
            println("USERS: repaired user_id=$userId source=$source")
        }
        return success
    }

    private fun Transaction.ensureUserInternal(userId: Long, now: Long): Pair<Boolean, Boolean> {
        if (userId <= 0) return false to false
        val (hasAnyData, resolvedTs) = resolveFirstSeenCandidate(userId, now)
        if (!hasAnyData) return false to false
        val firstSeenValue = resolvedTs?.coerceAtLeast(0L) ?: now
        val insert = Users.insertIgnore {
            it[Users.user_id] = userId
            it[Users.first_seen] = firstSeenValue
            it[Users.blocked_ts] = 0L
        }
        if (insert.insertedCount > 0) {
            return true to true
        }

        val row = Users
            .select { Users.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?: return false to false

        val existingFirst = row[Users.first_seen]
        val existingBlocked = row[Users.blocked_ts]
        val normalizedExisting = if (existingFirst > 0L) existingFirst else Long.MAX_VALUE
        val normalizedCandidate = resolvedTs?.takeIf { it > 0L } ?: firstSeenValue
        val desiredNormalized = min(normalizedExisting, normalizedCandidate)
        val desiredFirst = if (desiredNormalized == Long.MAX_VALUE) firstSeenValue else desiredNormalized
        val needsFirstUpdate = desiredFirst != existingFirst
        val needsBlockReset = existingBlocked > 0L
        if (needsFirstUpdate || needsBlockReset) {
            Users.update({ Users.user_id eq userId }) { update ->
                if (needsFirstUpdate) update[Users.first_seen] = desiredFirst
                if (needsBlockReset) update[Users.blocked_ts] = 0L
            }
        }
        return true to false
    }

    private fun Transaction.resolveFirstSeenCandidate(userId: Long, now: Long): Pair<Boolean, Long?> {
        if (userId <= 0) return false to null
        var earliest: Long? = null
        var hasFallbackPresence = false

        fun considerMin(table: String, tsColumn: String) {
            if (!tableExists(table) || !columnExists(table, "user_id") || !columnExists(table, tsColumn)) return
            exec(
                """
                    SELECT COUNT(*) AS total, MIN($tsColumn) AS first_seen
                    FROM $table
                    WHERE user_id = $userId
                """.trimIndent()
            ) { rs ->
                if (rs?.next() == true) {
                    val total = rs.getLong("total")
                    if (total > 0) {
                        val first = rs.getLong("first_seen")
                        val firstNull = rs.wasNull()
                        if (!firstNull && first > 0L) {
                            earliest = earliest?.let { min(it, first) } ?: first
                        } else {
                            hasFallbackPresence = true
                        }
                    }
                }
            }
        }

        fun considerPresence(table: String) {
            if (!tableExists(table) || !columnExists(table, "user_id")) return
            exec(
                """
                    SELECT 1
                    FROM $table
                    WHERE user_id = $userId
                    LIMIT 1
                """.trimIndent()
            ) { rs ->
                if (rs?.next() == true) {
                    hasFallbackPresence = true
                }
            }
        }

        considerMin("messages", "ts")
        considerMin("chat_history", "ts")
        considerMin("memory_notes_v2", "ts")
        considerMin("premium_reminders", "sent_ts")
        considerMin("payments", "created_at")

        if (tableExists("user_stats") && columnExists("user_stats", "user_id") && columnExists("user_stats", "day")) {
            exec(
                """
                    SELECT day
                    FROM user_stats
                    WHERE user_id = $userId AND day IS NOT NULL AND TRIM(day) != ''
                """.trimIndent()
            ) { rs ->
                var sawAny = false
                while (rs?.next() == true) {
                    sawAny = true
                    val dayRaw = rs.getString("day") ?: continue
                    val parsedTs = runCatching {
                        val date = LocalDate.parse(dayRaw.trim(), DateTimeFormatter.ISO_DATE)
                        date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                    }.getOrNull()
                    if (parsedTs != null && parsedTs > 0L) {
                        earliest = earliest?.let { min(it, parsedTs) } ?: parsedTs
                    } else {
                        hasFallbackPresence = true
                    }
                }
                if (sawAny && earliest == null) {
                    hasFallbackPresence = true
                }
            }
        }

        considerPresence("usage_counters")
        considerPresence("premium_users")

        val hasAny = earliest != null || hasFallbackPresence
        val resolved = when {
            earliest != null -> earliest
            hasFallbackPresence -> now
            else -> null
        }
        return hasAny to resolved
    }

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

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

            val existingFirstSeen = Users
                .slice(Users.user_id, Users.first_seen)
                .selectAll()
                .associate { it[Users.user_id] to it[Users.first_seen] }
                .toMutableMap()

            val candidates = mutableMapOf<Long, Long>()
            fun registerCandidate(id: Long?, ts: Long?) {
                if (id == null || id <= 0) return
                val candidate = when {
                    ts == null || ts <= 0L -> now
                    else -> ts
                }
                val prev = candidates[id]
                if (prev == null || candidate < prev) {
                    candidates[id] = candidate
                }
            }

            fun registerBySql(sql: String, tsColumn: String?) {
                exec(sql) { rs ->
                    while (rs?.next() == true) {
                        val userValue = rs.getLong("user_id")
                        val userWasNull = rs.wasNull()
                        if (userWasNull) continue
                        val ts = if (tsColumn != null) {
                            val value = rs.getLong(tsColumn)
                            if (rs.wasNull()) null else value
                        } else {
                            null
                        }
                        registerCandidate(userValue, ts)
                    }
                }
            }

            if (tableExists("messages")) {
                registerBySql(
                    """
                        SELECT user_id, MIN(ts) AS first_seen
                        FROM messages
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent(),
                    tsColumn = "first_seen"
                )
            }

            if (tableExists("chat_history")) {
                registerBySql(
                    """
                        SELECT user_id, MIN(ts) AS first_seen
                        FROM chat_history
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent(),
                    tsColumn = "first_seen"
                )
            }

            if (tableExists("memory_notes_v2")) {
                registerBySql(
                    """
                        SELECT user_id, MIN(ts) AS first_seen
                        FROM memory_notes_v2
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent(),
                    tsColumn = "first_seen"
                )
            }

            if (tableExists("premium_reminders")) {
                registerBySql(
                    """
                        SELECT user_id, MIN(sent_ts) AS first_seen
                        FROM premium_reminders
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent(),
                    tsColumn = "first_seen"
                )
            }

            if (tableExists("payments")) {
                registerBySql(
                    """
                        SELECT user_id, MIN(created_at) AS first_seen
                        FROM payments
                        WHERE user_id IS NOT NULL AND user_id > 0
                        GROUP BY user_id;
                    """.trimIndent(),
                    tsColumn = "first_seen"
                )
            }

            if (tableExists("usage_counters")) {
                registerBySql(
                    """
                        SELECT user_id
                        FROM usage_counters
                        WHERE user_id IS NOT NULL AND user_id > 0;
                    """.trimIndent(),
                    tsColumn = null
                )
            }

            if (tableExists("premium_users")) {
                registerBySql(
                    """
                        SELECT user_id
                        FROM premium_users
                        WHERE user_id IS NOT NULL AND user_id > 0;
                    """.trimIndent(),
                    tsColumn = null
                )
            }

            if (tableExists("user_stats")) {
                exec(
                    """
                        SELECT user_id, day
                        FROM user_stats
                        WHERE user_id IS NOT NULL AND user_id > 0 AND day IS NOT NULL AND TRIM(day) != '';
                    """.trimIndent()
                ) { rs ->
                    while (rs?.next() == true) {
                        val userId = rs.getLong("user_id")
                        if (rs.wasNull()) continue
                        val dayRaw = rs.getString("day") ?: continue
                        val parsedTs = runCatching {
                            val date = LocalDate.parse(dayRaw.trim(), DateTimeFormatter.ISO_DATE)
                            date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                        }.getOrNull()
                        registerCandidate(userId, parsedTs)
                    }
                }
            }

            var insertedCount = 0L

            candidates.forEach { (userId, firstSeenCandidate) ->
                val sanitized = firstSeenCandidate.coerceAtLeast(0L)
                val existing = existingFirstSeen[userId]
                if (existing == null) {
                    val result = Users.insertIgnore {
                        it[Users.user_id] = userId
                        it[Users.first_seen] = sanitized
                        it[Users.blocked_ts] = 0L
                    }
                    if (result.insertedCount > 0) {
                        insertedCount += result.insertedCount
                        existingFirstSeen[userId] = sanitized
                    }
                } else {
                    val effectiveExisting = if (existing > 0L) existing else Long.MAX_VALUE
                    val desired = min(effectiveExisting, sanitized.takeIf { it > 0L } ?: now)
                    if (desired < effectiveExisting || existing <= 0L) {
                        Users.update({ Users.user_id eq userId }) { row ->
                            row[Users.first_seen] = desired
                            row[Users.blocked_ts] = 0L
                        }
                        existingFirstSeen[userId] = desired
                    }
                }
            }

            insertedCount
        }

        if (source != null && inserted > 0) {
            println("USERS: backfilled $inserted existing users source=$source")
        }

        return inserted
    }
}

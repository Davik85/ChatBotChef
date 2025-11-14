package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and
import java.sql.ResultSet
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.min

object UsersRepo {

    data class UserInfo(
        val userId: Long,
        val firstSeen: Long,
        val blockedTs: Long,
        val blocked: Boolean,
    )

    data class UserSnapshot(
        val userId: Long,
        val firstSeen: Long?,
        val blockedTs: Long,
        val existsInUsers: Boolean,
        val blocked: Boolean,
    )

    fun touch(userId: Long, now: Long = System.currentTimeMillis()): Boolean = transaction {
        val inserted = Users.insertIgnore {
            it[Users.user_id] = userId
            it[Users.first_seen] = now
            it[Users.blocked_ts] = 0L
            it[Users.blocked] = false
        }
        if (inserted.insertedCount > 0) return@transaction true
        val updatedFirstSeen = Users.update({ (Users.user_id eq userId) and (Users.first_seen eq 0L) }) {
            it[Users.first_seen] = now
        } > 0
        Users.update({ Users.user_id eq userId }) {
            it[Users.blocked_ts] = 0L
            it[Users.blocked] = false
        }
        updatedFirstSeen
    }

    fun getAllUserIds(includeBlocked: Boolean = false): List<Long> = transaction {
        val query = if (includeBlocked) {
            Users.slice(Users.user_id).selectAll()
        } else {
            Users.slice(Users.user_id).select { Users.blocked eq false }
        }
        query.map { it[Users.user_id] }
    }

    fun countUsers(includeBlocked: Boolean = true): Long = transaction {
        val query = if (includeBlocked) {
            Users.selectAll()
        } else {
            Users.select { Users.blocked eq false }
        }
        query.count().toLong()
    }

    fun countBlocked(): Long = transaction {
        Users
            .select { Users.blocked eq true }
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
                    blockedTs = it[Users.blocked_ts],
                    blocked = it[Users.blocked]
                )
            }
    }

    fun loadSnapshot(userId: Long): UserSnapshot? = transaction {
        Users
            .select { Users.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?.let {
                return@transaction UserSnapshot(
                    userId = it[Users.user_id],
                    firstSeen = it[Users.first_seen].takeIf { ts -> ts > 0L },
                    blockedTs = it[Users.blocked_ts],
                    existsInUsers = true,
                    blocked = it[Users.blocked]
                )
            }

        val (hasPresence, resolvedTs) = resolveFirstSeenCandidate(userId)
        if (!hasPresence) {
            return@transaction null
        }

        UserSnapshot(
            userId = userId,
            firstSeen = resolvedTs?.takeIf { it > 0L },
            blockedTs = 0L,
            existsInUsers = false,
            blocked = false,
        )
    }

    fun markBlocked(userId: Long, blocked: Boolean, now: Long = System.currentTimeMillis()): Boolean = transaction {
        val value = if (blocked) now else 0L
        val existing = Users
            .slice(Users.blocked, Users.blocked_ts)
            .select { Users.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?: return@transaction false

        val currentBlocked = existing[Users.blocked]
        val currentBlockedTs = existing[Users.blocked_ts]
        val shouldUpdate = when {
            blocked -> !currentBlocked || currentBlockedTs != value
            else -> currentBlocked || currentBlockedTs != 0L
        }

        if (!shouldUpdate) return@transaction false

        Users.update({ Users.user_id eq userId }) { row ->
            row[Users.blocked_ts] = value
            row[Users.blocked] = blocked
        } > 0
    }

    fun repairOrphans(source: String? = null, now: Long = System.currentTimeMillis()): Long {
        val inserted = transaction {
            if (!tableExists("users")) return@transaction 0L

            val candidates = collectBackfillCandidates()
            var insertedCount = 0L
            for ((userId, firstSeenCandidate) in candidates) {
                val result = ensureUserInternal(userId, firstSeenCandidate, now)
                if (result.success && result.inserted) {
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
        val result = transaction {
            if (!tableExists("users")) return@transaction EnsureResult(success = false, inserted = false)
            val (hasAnyData, resolvedTs) = resolveFirstSeenCandidate(userId)
            if (!hasAnyData) return@transaction EnsureResult(success = false, inserted = false)
            ensureUserInternal(userId, resolvedTs, now)
        }

        if (result.success && result.inserted && source != null) {
            println("USERS: repaired user_id=$userId source=$source")
        }

        return result.success
    }

    private data class EnsureResult(val success: Boolean, val inserted: Boolean)

    private fun Transaction.collectBackfillCandidates(): Map<Long, Long?> {
        val result = mutableMapOf<Long, Long?>()

        fun record(userId: Long, candidateTs: Long?) {
            if (userId <= 0) return
            if (candidateTs != null && candidateTs > 0L) {
                val current = result[userId]
                result[userId] = when {
                    current == null -> candidateTs
                    current <= 0L -> candidateTs
                    else -> min(current, candidateTs)
                }
            } else {
                result.putIfAbsent(userId, null)
            }
        }

        fun scanWithTimestamp(table: String, tsColumn: String) {
            if (!tableExists(table) || !columnExists(table, "user_id") || !columnExists(table, tsColumn)) return
            exec(
                """
                    SELECT CAST(user_id AS TEXT) AS user_id,
                           MIN($tsColumn)           AS first_ts,
                           COUNT(1)                 AS total
                    FROM $table
                    WHERE user_id IS NOT NULL AND TRIM(CAST(user_id AS TEXT)) != ''
                    GROUP BY user_id
                """.trimIndent()
            ) { rs ->
                while (rs?.next() == true) {
                    val id = parseUserId(rs) ?: continue
                    val total = rs.getLong("total")
                    if (total <= 0L) continue
                    val ts = parseEpoch(rs.getObject("first_ts"))
                    if (ts != null && ts > 0L) {
                        record(id, ts)
                    } else {
                        record(id, null)
                    }
                }
            }
        }

        fun scanUserStats() {
            if (!tableExists("user_stats") || !columnExists("user_stats", "user_id") || !columnExists("user_stats", "day")) {
                return
            }
            exec(
                """
                    SELECT CAST(user_id AS TEXT) AS user_id,
                           MIN(day)               AS first_day
                    FROM user_stats
                    WHERE user_id IS NOT NULL AND TRIM(CAST(user_id AS TEXT)) != ''
                      AND day IS NOT NULL AND TRIM(day) != ''
                    GROUP BY user_id
                """.trimIndent()
            ) { rs ->
                while (rs?.next() == true) {
                    val id = parseUserId(rs) ?: continue
                    val ts = parseStatsDay(rs.getString("first_day"))
                    if (ts != null && ts > 0L) {
                        record(id, ts)
                    } else {
                        record(id, null)
                    }
                }
            }
        }

        fun scanPresence(table: String) {
            if (!tableExists(table) || !columnExists(table, "user_id")) return
            exec(
                """
                    SELECT DISTINCT CAST(user_id AS TEXT) AS user_id
                    FROM $table
                    WHERE user_id IS NOT NULL AND TRIM(CAST(user_id AS TEXT)) != ''
                """.trimIndent()
            ) { rs ->
                while (rs?.next() == true) {
                    val id = parseUserId(rs) ?: continue
                    record(id, null)
                }
            }
        }

        scanWithTimestamp("messages", "ts")
        scanWithTimestamp("chat_history", "ts")
        scanWithTimestamp("memory_notes_v2", "ts")
        scanWithTimestamp("premium_reminders", "sent_ts")
        scanWithTimestamp("payments", "created_at")
        scanUserStats()
        scanPresence("usage_counters")
        scanPresence("premium_users")

        return result
    }

    private fun Transaction.ensureUserInternal(userId: Long, resolvedTs: Long?, now: Long): EnsureResult {
        if (userId <= 0) return EnsureResult(success = false, inserted = false)
        val firstSeenValue = resolvedTs?.takeIf { it > 0L } ?: now
        val insert = Users.insertIgnore {
            it[Users.user_id] = userId
            it[Users.first_seen] = firstSeenValue
            it[Users.blocked_ts] = 0L
            it[Users.blocked] = false
        }
        if (insert.insertedCount > 0) {
            return EnsureResult(success = true, inserted = true)
        }

        val row = Users
            .select { Users.user_id eq userId }
            .limit(1)
            .firstOrNull()
            ?: return EnsureResult(success = false, inserted = false)

        val existingFirst = row[Users.first_seen]
        val existingBlockedTs = row[Users.blocked_ts]
        val existingBlockedFlag = row[Users.blocked]
        val desiredFirst = when {
            resolvedTs != null && resolvedTs > 0L && existingFirst > 0L -> min(existingFirst, resolvedTs)
            resolvedTs != null && resolvedTs > 0L -> resolvedTs
            existingFirst > 0L -> existingFirst
            else -> now
        }
        val needsFirstUpdate = desiredFirst != existingFirst
        val needsBlockReset = existingBlockedTs > 0L || existingBlockedFlag
        if (needsFirstUpdate || needsBlockReset) {
            Users.update({ Users.user_id eq userId }) { update ->
                if (needsFirstUpdate) update[Users.first_seen] = desiredFirst
                if (needsBlockReset) {
                    update[Users.blocked_ts] = 0L
                    update[Users.blocked] = false
                }
            }
        }
        return EnsureResult(success = true, inserted = false)
    }

    private fun Transaction.resolveFirstSeenCandidate(userId: Long): Pair<Boolean, Long?> {
        if (userId <= 0) return false to null
        var earliest: Long? = null
        var hasPresence = false

        fun recordTimestamp(ts: Long?) {
            hasPresence = true
            if (ts != null && ts > 0L) {
                earliest = earliest?.let { min(it, ts) } ?: ts
            }
        }

        fun recordPresence() {
            hasPresence = true
        }

        fun scanWithTimestamp(table: String, tsColumn: String) {
            if (!tableExists(table) || !columnExists(table, "user_id") || !columnExists(table, tsColumn)) return
            val whereClause = matchUserClause("user_id", userId)
            exec(
                """
                    SELECT COUNT(1) AS total, MIN($tsColumn) AS first_ts
                    FROM $table
                    WHERE $whereClause
                """.trimIndent()
            ) { rs ->
                if (rs?.next() == true) {
                    val total = rs.getLong("total")
                    if (total > 0L) {
                        recordTimestamp(parseEpoch(rs.getObject("first_ts")))
                    }
                }
            }
        }

        fun scanUserStats() {
            if (!tableExists("user_stats") || !columnExists("user_stats", "user_id") || !columnExists("user_stats", "day")) {
                return
            }
            val whereClause = matchUserClause("user_id", userId)
            exec(
                """
                    SELECT day
                    FROM user_stats
                    WHERE $whereClause AND day IS NOT NULL AND TRIM(day) != ''
                """.trimIndent()
            ) { rs ->
                var sawAny = false
                while (rs?.next() == true) {
                    sawAny = true
                    val ts = parseStatsDay(rs.getString("day"))
                    if (ts != null && ts > 0L) {
                        earliest = earliest?.let { min(it, ts) } ?: ts
                    }
                }
                if (sawAny) {
                    recordPresence()
                }
            }
        }

        fun scanPresence(table: String) {
            if (!tableExists(table) || !columnExists(table, "user_id")) return
            val whereClause = matchUserClause("user_id", userId)
            exec(
                """
                    SELECT 1
                    FROM $table
                    WHERE $whereClause
                    LIMIT 1
                """.trimIndent()
            ) { rs ->
                if (rs?.next() == true) {
                    recordPresence()
                }
            }
        }

        scanWithTimestamp("messages", "ts")
        scanWithTimestamp("chat_history", "ts")
        scanWithTimestamp("memory_notes_v2", "ts")
        scanWithTimestamp("premium_reminders", "sent_ts")
        scanWithTimestamp("payments", "created_at")
        scanUserStats()
        scanPresence("usage_counters")
        scanPresence("premium_users")

        return hasPresence to earliest
    }

    private fun parseUserId(rs: ResultSet, column: String = "user_id"): Long? {
        val raw = rs.getObject(column) ?: return null
        val parsed = when (raw) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> raw.toString().trim().toLongOrNull()
        }
        return parsed?.takeIf { it > 0L }
    }

    private fun parseEpoch(value: Any?): Long? = when (value) {
        null -> null
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> value.toString().trim().toLongOrNull()
    }

    private fun parseStatsDay(dayRaw: String?): Long? {
        if (dayRaw.isNullOrBlank()) return null
        return runCatching {
            val date = LocalDate.parse(dayRaw.trim(), DateTimeFormatter.ISO_DATE)
            date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun matchUserClause(column: String, userId: Long): String =
        "( ($column = $userId) OR (TRIM(CAST($column AS TEXT)) = '$userId') )"

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

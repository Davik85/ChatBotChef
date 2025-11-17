package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

object UsersRepo {

    data class SeenRecordResult(
        val inserted: Boolean,
        val updated: Boolean,
    )

    data class MarkBlockedResult(
        val changed: Boolean,
        val previousBlocked: Boolean,
        val currentBlocked: Boolean,
    )

    data class UserSnapshot(
        val userId: Long,
        val firstSeen: Long?,
        val lastSeen: Long?,
        val blockedTs: Long,
        val existsInUsers: Boolean,
        val blocked: Boolean,
        val languageCode: String?,
    )

    fun recordSeen(
        userId: Long,
        now: Long = System.currentTimeMillis(),
        languageCode: String? = null
    ): SeenRecordResult = transaction {
        if (userId <= 0L) return@transaction SeenRecordResult(inserted = false, updated = false)
        val sanitizedLanguage = languageCode?.trim()?.takeIf { it.isNotEmpty() }?.take(16)

        val inserted = Users.insertIgnore { row ->
            row[Users.userId] = userId
            row[Users.firstSeenAt] = now
            row[Users.lastSeenAt] = now
            row[Users.isBlocked] = false
            row[Users.blockedAt] = null
            row[Users.languageCode] = sanitizedLanguage
        }
        if (inserted.insertedCount > 0) {
            return@transaction SeenRecordResult(inserted = true, updated = true)
        }

        val existing = Users
            .select { Users.userId eq userId }
            .limit(1)
            .firstOrNull()
            ?: return@transaction SeenRecordResult(inserted = false, updated = false)

        val currentLastSeen = existing[Users.lastSeenAt]
        val shouldUpdateLastSeen = now > currentLastSeen
        val currentLanguage = existing[Users.languageCode]
        val shouldUpdateLanguage = sanitizedLanguage != null && sanitizedLanguage != currentLanguage

        if (!shouldUpdateLastSeen && !shouldUpdateLanguage) {
            return@transaction SeenRecordResult(inserted = false, updated = false)
        }

        Users.update({ Users.userId eq userId }) { row ->
            if (shouldUpdateLastSeen) {
                row[Users.lastSeenAt] = now
            }
            if (shouldUpdateLanguage) {
                row[Users.languageCode] = sanitizedLanguage
            }
        }

        SeenRecordResult(inserted = false, updated = true)
    }

    fun markBlocked(
        userId: Long,
        blocked: Boolean,
        now: Long = System.currentTimeMillis()
    ): MarkBlockedResult = transaction {
        if (userId <= 0L) return@transaction MarkBlockedResult(false, false, blocked)
        val blockedValue: Long? = if (blocked) now else null

        val inserted = Users.insertIgnore { row ->
            row[Users.userId] = userId
            row[Users.firstSeenAt] = now
            row[Users.lastSeenAt] = now
            row[Users.isBlocked] = blocked
            row[Users.blockedAt] = blockedValue
            row[Users.languageCode] = null
        }
        if (inserted.insertedCount > 0) {
            return@transaction MarkBlockedResult(
                changed = blocked,
                previousBlocked = false,
                currentBlocked = blocked,
            )
        }

        val row = Users
            .select { Users.userId eq userId }
            .limit(1)
            .firstOrNull()
            ?: return@transaction MarkBlockedResult(false, false, blocked)

        val previousBlocked = row[Users.isBlocked]
        val previousBlockedAt = row[Users.blockedAt]
        val shouldUpdate = previousBlocked != blocked || previousBlockedAt != blockedValue

        if (shouldUpdate) {
            Users.update({ Users.userId eq userId }) { update ->
                update[Users.isBlocked] = blocked
                update[Users.blockedAt] = blockedValue
            }
        }

        MarkBlockedResult(
            changed = shouldUpdate,
            previousBlocked = previousBlocked,
            currentBlocked = blocked,
        )
    }

    fun loadSnapshot(userId: Long): UserSnapshot? = transaction {
        Users
            .select { Users.userId eq userId }
            .limit(1)
            .firstOrNull()
            ?.let { row ->
                val firstSeen = row[Users.firstSeenAt].takeIf { it > 0L }
                val lastSeen = row[Users.lastSeenAt].takeIf { it > 0L }
                val blockedTs = row[Users.blockedAt] ?: 0L
                UserSnapshot(
                    userId = row[Users.userId],
                    firstSeen = firstSeen,
                    lastSeen = lastSeen,
                    blockedTs = blockedTs,
                    existsInUsers = true,
                    blocked = row[Users.isBlocked],
                    languageCode = row[Users.languageCode]
                )
            }
    }

    fun countTotal(): Long = transaction {
        Users.selectAll().count().toLong()
    }

    fun countUsers(includeBlocked: Boolean): Long = transaction {
        if (includeBlocked) {
            Users.selectAll().count().toLong()
        } else {
            Users.select { Users.isBlocked eq false }.count().toLong()
        }
    }

    fun countBlocked(): Long = transaction {
        Users.select { Users.isBlocked eq true }.count().toLong()
    }

    fun countActive(): Long = transaction {
        Users.select { Users.isBlocked eq false }.count().toLong()
    }

    fun countActiveSince(fromMs: Long): Long = transaction {
        val threshold = fromMs.coerceAtLeast(0L)
        Users
            .select { (Users.isBlocked eq false) and (Users.lastSeenAt greaterEq threshold) }
            .count()
            .toLong()
    }

    fun loadActiveBatch(
        afterUserId: Long? = null,
        limit: Int,
        activeSince: Long? = null
    ): List<Long> = transaction {
        if (limit <= 0) return@transaction emptyList()
        var condition = Users.isBlocked eq false
        if (afterUserId != null && afterUserId > 0) {
            condition = condition and (Users.userId greater afterUserId)
        }
        val threshold = activeSince?.takeIf { it > 0L }
        if (threshold != null) {
            condition = condition and (Users.lastSeenAt greaterEq threshold)
        }
        Users
            .slice(Users.userId)
            .select { condition }
            .orderBy(Users.userId to SortOrder.ASC)
            .limit(limit)
            .map { it[Users.userId] }
    }

    fun repairOrphans(source: String? = null, now: Long = System.currentTimeMillis()): Long = 0L
}

package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
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
}

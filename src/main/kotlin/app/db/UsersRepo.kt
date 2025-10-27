package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and

object UsersRepo {

    fun touch(userId: Long, now: Long = System.currentTimeMillis()) = transaction {
        val inserted = Users.insertIgnore {
            it[Users.user_id] = userId
            it[first_seen] = now
        }
        if (inserted.insertedCount == 0) {
            Users.update({ (Users.user_id eq userId) and (Users.first_seen eq 0L) }) {
                it[first_seen] = now
            }
        }
    }

    fun getAllUserIds(): List<Long> = transaction {
        Users
            .slice(Users.user_id)
            .selectAll()
            .map { it[Users.user_id] }
    }

    fun countUsers(): Long = transaction {
        Users.selectAll().count()
    }
}

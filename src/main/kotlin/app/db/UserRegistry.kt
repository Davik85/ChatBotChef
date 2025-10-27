package app.db

import app.web.dto.TgUser
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.Transaction

object UserRegistry {
    fun upsert(from: TgUser, tsNow: Long = System.currentTimeMillis()): Boolean {
        val userId = from.id
        if (userId <= 0L) return false
        return transaction {
            val inserted = Users.insertIgnore {
                it[Users.user_id] = userId
                it[Users.first_seen] = tsNow
                it[Users.blocked_ts] = 0L
                it[Users.blocked] = false
            }
            inserted.insertedCount > 0
        }
    }

    fun backfillFromExistingData() {
        transaction {
            if (!tableExists(this, "users")) return@transaction
            if (tableExists(this, "messages")) {
                exec(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen)
                        SELECT DISTINCT user_id, MIN(ts) AS first_seen
                        FROM messages
                        WHERE user_id IS NOT NULL
                        GROUP BY user_id;
                    """.trimIndent()
                )
            }
            if (tableExists(this, "premium_users")) {
                exec(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen)
                        SELECT user_id, strftime('%s','now')*1000 AS first_seen
                        FROM premium_users
                        WHERE user_id IS NOT NULL;
                    """.trimIndent()
                )
            }
            if (tableExists(this, "usage_counters")) {
                exec(
                    """
                        INSERT OR IGNORE INTO users(user_id, first_seen)
                        SELECT user_id, strftime('%s','now')*1000 AS first_seen
                        FROM usage_counters
                        WHERE user_id IS NOT NULL;
                    """.trimIndent()
                )
            }
        }
    }

    private fun tableExists(tx: Transaction, table: String): Boolean =
        tx.exec("SELECT name FROM sqlite_master WHERE type='table' AND name='$table'") { rs ->
            var found = false
            while (rs?.next() == true) {
                found = true
            }
            found
        } ?: false
}

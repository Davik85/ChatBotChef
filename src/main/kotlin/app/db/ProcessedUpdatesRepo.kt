package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.transaction

object ProcessedUpdatesRepo {
    private const val PRUNE_EVERY = 10_000L
    private const val PRUNE_WINDOW = 200_000L

    fun markProcessed(updateId: Long): Boolean {
        if (updateId <= 0L) return true
        val inserted = runCatching {
            transaction {
                val affected = exec(
                    "INSERT OR IGNORE INTO processed_updates (update_id) VALUES ($updateId)"
                ) ?: 0
                affected != 0
            }
        }.onFailure {
            println("TG-POLL-ERR: failed_to_mark_processed update=$updateId reason=${it.message}")
        }.getOrElse { true }

        if (inserted && updateId % PRUNE_EVERY == 0L) {
            pruneOlderThan(updateId - PRUNE_WINDOW)
        }

        return inserted
    }

    private fun pruneOlderThan(threshold: Long) {
        if (threshold <= 0L) return
        runCatching {
            transaction {
                ProcessedUpdates.deleteWhere { ProcessedUpdates.update_id less threshold }
            }
        }.onFailure {
            println("TG-POLL-ERR: prune_failed reason=${it.message}")
        }
    }
}

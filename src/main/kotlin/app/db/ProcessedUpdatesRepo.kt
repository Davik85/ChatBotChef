package app.db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.max
import org.jetbrains.exposed.sql.transactions.transaction

object ProcessedUpdatesRepo {
    private const val PRUNE_EVERY = 10_000L
    private const val PRUNE_WINDOW = 200_000L

    fun tryInsert(updateId: Long): Boolean {
        if (updateId <= 0L) return true

        var failed = false
        val inserted = runCatching {
            transaction {
                val statement = ProcessedUpdates.insertIgnore {
                    it[ProcessedUpdates.updateId] = updateId
                }
                (statement?.insertedCount ?: 0) > 0
            }
        }.onFailure {
            failed = true
            println("TG-POLL-ERR: failed_to_track_update update=$updateId reason=${it.message}")
        }.getOrElse { true }

        if (!failed && inserted && updateId % PRUNE_EVERY == 0L) {
            pruneOlderThan(updateId - PRUNE_WINDOW)
        }

        return inserted
    }

    fun remove(updateId: Long) {
        if (updateId <= 0L) return
        runCatching {
            transaction {
                ProcessedUpdates.deleteWhere { ProcessedUpdates.updateId eq updateId }
            }
        }.onFailure {
            println("TG-POLL-ERR: failed_to_remove_update update=$updateId reason=${it.message}")
        }
    }

    fun lastProcessedId(): Long {
        return runCatching {
            transaction {
                val maxIdAlias = ProcessedUpdates.updateId.max()
                ProcessedUpdates
                    .slice(maxIdAlias)
                    .selectAll()
                    .firstOrNull()
                    ?.get(maxIdAlias)
                    ?: 0L
            }
        }.onFailure {
            println("TG-POLL-ERR: failed_to_fetch_last_update reason=${it.message}")
        }.getOrElse { 0L }
    }

    private fun pruneOlderThan(threshold: Long) {
        if (threshold <= 0L) return
        runCatching {
            transaction {
                ProcessedUpdates.deleteWhere { ProcessedUpdates.updateId less threshold }
            }
        }.onFailure {
            println("TG-POLL-ERR: prune_failed reason=${it.message}")
        }
    }
}

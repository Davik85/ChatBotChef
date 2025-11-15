package app.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class UsersRepoStatsTest {

    @Before
    fun setUp() {
        Database.connect("jdbc:sqlite::memory:")
        TransactionManager.manager.defaultIsolationLevel = java.sql.Connection.TRANSACTION_SERIALIZABLE
        transaction { SchemaUtils.create(Users, UsageCounters) }
    }

    @After
    fun tearDown() {
        transaction { SchemaUtils.drop(UsageCounters, Users) }
        TransactionManager.resetCurrent(null)
    }

    @Test
    fun `summarizeForStats reports totals active and blocked`() {
        val dayMs = 24L * 60 * 60 * 1000
        val now = 10 * dayMs

        UsersRepo.recordSeen(1001L, now - 40 * dayMs)
        UsersRepo.recordSeen(1002L, now)
        UsersRepo.recordSeen(1003L, now)
        UsersRepo.markBlocked(1003L, blocked = true, now = now)

        transaction {
            UsageCounters.insert {
                it[user_id] = 2001L
                it[total_used] = 5
            }
        }

        val summary = UsersRepo.summarizeForStats(now - dayMs)

        assertEquals(4L, summary.totalUsers)
        assertEquals(1L, summary.blockedUsers)
        assertEquals(1L, summary.activeUsers)
        assertEquals(2, summary.sourcesUsed)
        assertEquals(2L, summary.activeWindowPopulation)

        val active7d = UsersRepo.countActiveSince(now - 7 * dayMs)
        assertEquals(1L, active7d)
    }
}

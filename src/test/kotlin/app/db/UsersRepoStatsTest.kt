package app.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import app.testsupport.assertEquals
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.pathString

class UsersRepoStatsTest {
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        val path = createTempFile(prefix = "users-stats", suffix = ".sqlite")
        dbFile = path.toFile().apply { deleteOnExit() }
        Database.connect("jdbc:sqlite:${path.pathString}", driver = "org.sqlite.JDBC")
        TransactionManager.manager.defaultIsolationLevel = java.sql.Connection.TRANSACTION_SERIALIZABLE
        transaction { SchemaUtils.create(Users, UsageCounters) }
    }

    @After
    fun tearDown() {
        transaction { SchemaUtils.drop(UsageCounters, Users) }
        TransactionManager.resetCurrent(null)
        if (this::dbFile.isInitialized) {
            dbFile.delete()
        }
    }

    @Test
    fun `summarizeForStats reports totals active and blocked`() {
        val dayMs = 24L * 60 * 60 * 1000
        val now = 10 * dayMs

        UsersRepo.recordSeen(1001L, now - 40 * dayMs)
        UsersRepo.recordSeen(1002L, now)
        UsersRepo.recordSeen(1003L, now)
        UsersRepo.markBlocked(1003L, blocked = true, now = now)

        val total = UsersRepo.countTotal()
        assertEquals(3L, total)

        val blocked = UsersRepo.countBlocked()
        assertEquals(1L, blocked)

        val active = UsersRepo.countActive()
        assertEquals(2L, active)

        val activeWindow = UsersRepo.countActiveSince(now - dayMs)
        assertEquals(1L, activeWindow)

        val active7d = UsersRepo.countActiveSince(now - 7 * dayMs)
        assertEquals(1L, active7d)
    }
}

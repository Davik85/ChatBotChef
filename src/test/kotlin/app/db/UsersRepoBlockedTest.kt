package app.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsersRepoBlockedTest {

    @Before
    fun setUp() {
        Database.connect("jdbc:sqlite::memory:")
        TransactionManager.manager.defaultIsolationLevel = java.sql.Connection.TRANSACTION_SERIALIZABLE
        transaction {
            SchemaUtils.create(Users)
        }
    }

    @After
    fun tearDown() {
        transaction {
            SchemaUtils.drop(Users)
        }
        TransactionManager.resetCurrent(null)
    }

    @Test
    fun `markBlocked toggles state and impacts counts`() {
        val userId = 42L

        assertEquals(0L, UsersRepo.countUsers(includeBlocked = true))
        assertEquals(0L, UsersRepo.countUsers(includeBlocked = false))
        assertEquals(0L, UsersRepo.countBlocked())

        val blockedResult = UsersRepo.markBlocked(userId, blocked = true, now = 1_000L)
        assertTrue(blockedResult.changed)
        assertTrue(blockedResult.currentBlocked)
        assertFalse(blockedResult.previousBlocked)

        assertEquals(1L, UsersRepo.countUsers(includeBlocked = true))
        assertEquals(0L, UsersRepo.countUsers(includeBlocked = false))
        assertEquals(1L, UsersRepo.countBlocked())

        val unblockedResult = UsersRepo.markBlocked(userId, blocked = false, now = 2_000L)
        assertTrue(unblockedResult.changed)
        assertFalse(unblockedResult.currentBlocked)
        assertTrue(unblockedResult.previousBlocked)

        assertEquals(1L, UsersRepo.countUsers(includeBlocked = true))
        assertEquals(1L, UsersRepo.countUsers(includeBlocked = false))
        assertEquals(0L, UsersRepo.countBlocked())
    }
}

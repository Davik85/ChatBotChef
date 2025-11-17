package app.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import app.testsupport.assertEquals
import app.testsupport.assertFalse
import app.testsupport.assertTrue
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.pathString

class UsersRepoBlockedTest {
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        val path = createTempFile(prefix = "users-blocked", suffix = ".sqlite")
        dbFile = path.toFile().apply { deleteOnExit() }
        Database.connect("jdbc:sqlite:${path.pathString}", driver = "org.sqlite.JDBC")
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
        if (this::dbFile.isInitialized) {
            dbFile.delete()
        }
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

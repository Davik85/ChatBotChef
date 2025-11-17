package app.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.pathString
import app.testsupport.AfterTest
import app.testsupport.BeforeTest
import app.testsupport.Test
import app.testsupport.assertEquals
import app.testsupport.assertFalse
import app.testsupport.assertTrue

class ProcessedUpdatesRepoTest {
    private lateinit var dbFile: File

    @BeforeTest
    fun setUp() {
        val path = createTempFile(prefix = "updates", suffix = ".sqlite")
        dbFile = path.toFile().apply { deleteOnExit() }
        Database.connect(url = "jdbc:sqlite:${path.pathString}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.drop(ProcessedUpdates)
            SchemaUtils.create(ProcessedUpdates)
        }
    }

    @AfterTest
    fun tearDown() {
        if (this::dbFile.isInitialized) {
            dbFile.delete()
        }
    }

    @Test
    fun firstArrivalIsProcessed() {
        val inserted = ProcessedUpdatesRepo.tryInsert(101)
        assertTrue(inserted)
        assertEquals(101L, ProcessedUpdatesRepo.lastProcessedId())
    }

    @Test
    fun duplicateUpdateIsSkipped() {
        assertTrue(ProcessedUpdatesRepo.tryInsert(202))
        assertFalse(ProcessedUpdatesRepo.tryInsert(202))
    }

    @Test
    fun failedHandlingRemovesMarker() {
        val updateId = 303L
        assertTrue(ProcessedUpdatesRepo.tryInsert(updateId))
        ProcessedUpdatesRepo.remove(updateId)
        assertTrue(ProcessedUpdatesRepo.tryInsert(updateId))
    }
}

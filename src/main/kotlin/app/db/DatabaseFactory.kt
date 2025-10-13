package app.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

// ---------- Таблицы ----------

object Users : Table("users") {
    val id = long("id")
    val firstName = varchar("first_name", 64).nullable()
    val username = varchar("username", 64).nullable()
    val isPremiumUntil = long("premium_until").default(0L)
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

object Messages : Table("messages") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val role = varchar("role", 32) // system/user/assistant
    val content = text("content")
    val ts = long("ts")
    override val primaryKey = PrimaryKey(id)
}

/** Оставляем только PK(userId), чтобы не дублировать индексы. */
object MemoryNotesV2 : Table("memory_notes_v2") {
    val userId = long("user_id")
    val note = text("note")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(userId)
}

/** Оставляем только PK(userId). */
object UserStats : Table("user_stats") {
    val userId = long("user_id")
    val day = varchar("day", 10) // yyyy-MM-dd
    val sentToday = integer("sent_today").default(0)
    override val primaryKey = PrimaryKey(userId)
}

/** Оставляем только PK(updateId). */
object ProcessedUpdates : Table("processed_updates") {
    val updateId = long("update_id")
    val ts = long("ts")
    override val primaryKey = PrimaryKey(updateId)
}

// ---------- Утилиты ----------

/**
 * Путь к SQLite:
 * 1) пробуем app.AppConfig.DB_PATH (если существует);
 * 2) ENV DB_PATH;
 * 3) дефолт.
 */
private fun resolveDbPath(): String {
    runCatching {
        val clazz = Class.forName("app.AppConfig")
        val field = clazz.getDeclaredField("DB_PATH")
        field.isAccessible = true
        val v = field.get(null) as? String
        if (!v.isNullOrBlank()) return v.trim()
    }
    System.getenv("DB_PATH")?.trim()?.let { if (it.isNotBlank()) return it }
    return "data/chatbotchef.sqlite"
}

/** Все PRAGMA, которые нельзя менять в транзакции — применяем единым сырым соединением. */
private fun applyPragmasOutsideTx(dbPath: String) {
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
        conn.createStatement().use { st ->
            // ВАЖНО: всё выполняем вне любой транзакции
            st.execute("PRAGMA journal_mode=WAL;")
            st.execute("PRAGMA synchronous=NORMAL;")
            st.execute("PRAGMA foreign_keys=ON;")
        }
    }
}

// ---------- Инициализация БД ----------

object DatabaseFactory {
    fun init() {
        val dbPath = resolveDbPath()

        // каталог для файла БД
        File(dbPath).parentFile?.mkdirs()

        // PRAGMA — строго до подключения Exposed и вне транзакций
        runCatching { applyPragmasOutsideTx(dbPath) }
            .onFailure { println("DB WARN: PRAGMA apply failed (${it.message}). Продолжаем с настройками по умолчанию.") }

        // Подключаемся к SQLite
        Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC"
        )

        // разумный уровень изоляции
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // Схема/миграции (внутри транзакции НИКАКИХ PRAGMA)
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users, Messages, MemoryNotesV2, UserStats, ProcessedUpdates
            )
        }
    }
}

// ---------- Репозитории ----------

object UserRepo {
    fun upsert(userId: Long, firstName: String?, username: String?) = transaction {
        val exists = Users.select { Users.id eq userId }.limit(1).any()
        if (!exists) {
            Users.insert {
                it[id] = userId
                it[Users.firstName] = firstName
                it[Users.username] = username
                it[createdAt] = System.currentTimeMillis()
            }
        } else {
            Users.update({ Users.id eq userId }) {
                it[Users.firstName] = firstName
                it[Users.username] = username
            }
        }
    }
}

object MessageRepo {
    fun add(userId: Long, role: String, content: String) = transaction {
        Messages.insert {
            it[Messages.userId] = userId
            it[Messages.role] = role
            it[Messages.content] = content
            it[ts] = System.currentTimeMillis()
        }
    }
}

object ProcessedUpdatesRepo {
    fun seen(updateId: Long): Boolean = transaction {
        ProcessedUpdates.select { ProcessedUpdates.updateId eq updateId }.limit(1).any()
    }
    fun mark(updateId: Long) = transaction {
        ProcessedUpdates.insertIgnore {
            it[ProcessedUpdates.updateId] = updateId
            it[ts] = System.currentTimeMillis()
        }
    }
}



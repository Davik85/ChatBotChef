package app.db

import app.AppConfig
import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.io.File
import java.sql.Connection

object Users : Table(name = "users") {
    val userId = long("user_id")
    val firstSeenAt = long("first_seen_at")
    val lastSeenAt = long("last_seen_at")
    val isBlocked = bool("is_blocked").default(false)
    val blockedAt = long("blocked_at").nullable()
    val languageCode = varchar("language_code", length = 16).nullable()

    override val primaryKey = PrimaryKey(userId)

    init {
        index(isUnique = true, columns = arrayOf(userId))
        index(isUnique = false, columns = arrayOf(lastSeenAt))
    }
}

object UsageCounters : Table(name = "usage_counters") {
    val userId = long("user_id").references(Users.userId, onDelete = ReferenceOption.CASCADE)
    val totalMessages = integer("total_messages").default(0)
    val dailyMessages = integer("daily_messages").default(0)
    val dailyResetAt = long("daily_reset_at").default(0)

    override val primaryKey = PrimaryKey(userId)

    init {
        index(isUnique = true, columns = arrayOf(userId))
    }
}

object PremiumUsers : Table(name = "premium_users") {
    val userId = long("user_id").references(Users.userId, onDelete = ReferenceOption.CASCADE)
    val premiumUntil = long("premium_until")

    override val primaryKey = PrimaryKey(userId)

    init {
        index(isUnique = true, columns = arrayOf(userId))
        index(isUnique = false, columns = arrayOf(premiumUntil))
    }
}

object Messages : LongIdTable(name = "messages") {
    val userId = long("user_id").references(Users.userId, onDelete = ReferenceOption.CASCADE)
    val timestamp = long("ts")
    val direction = varchar("direction", length = 8)
    val kind = varchar("kind", length = 16)

    init {
        index(isUnique = false, columns = arrayOf(userId))
        index(isUnique = false, columns = arrayOf(timestamp))
    }
}

object ProcessedUpdates : Table(name = "processed_updates") {
    val updateId = long("update_id")

    override val primaryKey = PrimaryKey(updateId)

    init {
        index(isUnique = true, columns = arrayOf(updateId))
    }
}

object Broadcasts : LongIdTable(name = "broadcasts") {
    val createdAt = long("created_at")
    val createdBy = long("created_by")
    val text = text("text").nullable()
    val mediaType = varchar("media_type", length = 16).nullable()
    val mediaFileId = varchar("media_file_id", length = 512).nullable()

    init {
        index(isUnique = false, columns = arrayOf(createdAt))
        index(isUnique = false, columns = arrayOf(createdBy))
    }
}

object BroadcastRecipients : LongIdTable(name = "broadcast_recipients") {
    val broadcastId = reference("broadcast_id", Broadcasts, onDelete = ReferenceOption.CASCADE)
    val userId = long("user_id").references(Users.userId, onDelete = ReferenceOption.CASCADE)
    val status = varchar("status", length = 32)
    val errorCode = integer("error_code").nullable()
    val errorMessage = varchar("error_message", length = 256).nullable()
    val updatedAt = long("updated_at").default(0)

    init {
        index(isUnique = false, columns = arrayOf(broadcastId))
        index(isUnique = false, columns = arrayOf(userId))
        uniqueIndex("broadcast_recipient_unique", broadcastId, userId)
    }
}

object MemoryNotesV2 : Table(name = "memory_notes_v2") {
    val id = long("id").autoIncrement()
    val user_id = long("user_id").references(Users.userId, onDelete = ReferenceOption.CASCADE)
    val note = text("note")
    val ts = long("ts")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, columns = arrayOf(user_id))
    }
}

object PremiumReminders : Table(name = "premium_reminders") {
    val id = long("id").autoIncrement()
    val user_id = long("user_id").references(Users.userId, onDelete = ReferenceOption.CASCADE)
    val kind = varchar("kind", length = 8)
    val sent_ts = long("sent_ts")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, columns = arrayOf(user_id))
        uniqueIndex("premium_reminders_unique", user_id, kind)
    }
}

object Payments : Table(name = "payments") {
    val payload = varchar("payload", length = 128)
    val user_id = long("user_id").references(Users.userId, onDelete = ReferenceOption.CASCADE)
    val amount_minor = integer("amount_minor")
    val currency = varchar("currency", length = 8)
    val status = varchar("status", length = 32)
    val failure_reason = text("failure_reason").nullable()
    val telegram_charge_id = varchar("telegram_charge_id", length = 128).nullable()
    val provider_charge_id = varchar("provider_charge_id", length = 128).nullable()
    val created_at = long("created_at")
    val updated_at = long("updated_at")

    override val primaryKey = PrimaryKey(payload)

    init {
        index(isUnique = false, columns = arrayOf(user_id))
        index(isUnique = false, columns = arrayOf(status))
    }
}

object ChatHistory : Table(name = "chat_history") {
    val id = long("id").autoIncrement()
    val user_id = long("user_id").references(Users.userId, onDelete = ReferenceOption.CASCADE)
    val mode = varchar("mode", length = 32)
    val role = varchar("role", length = 16)
    val text = text("text")
    val ts = long("ts")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, columns = arrayOf(user_id, mode))
        index(isUnique = false, columns = arrayOf(ts))
    }
}

object AdminAudit : Table(name = "admin_audit") {
    val id = long("id").autoIncrement()
    val admin_id = long("admin_id")
    val action = varchar("action", length = 64)
    val target = varchar("target", length = 128).nullable()
    val meta = varchar("meta", length = 256).nullable()
    val ts = long("ts")

    override val primaryKey = PrimaryKey(id)

    init {
        index(isUnique = false, columns = arrayOf(admin_id))
        index(isUnique = false, columns = arrayOf(ts))
    }
}

object DatabaseFactory {

    fun init() {
        File(AppConfig.DB_PATH).parentFile?.mkdirs()

        val url = "jdbc:sqlite:${AppConfig.DB_PATH}?foreign_keys=on"
        val dataSource = SQLiteConfig().apply {
            enforceForeignKeys(true)
        }.let { cfg ->
            SQLiteDataSource(cfg).also { it.url = url }
        }
        Database.connect(dataSource)
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        transaction {
            fun tableExists(name: String): Boolean =
                exec("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'") { rs ->
                    var found = false
                    while (rs?.next() == true) {
                        found = true
                    }
                    found
                } ?: false

            fun columnExists(table: String, column: String): Boolean =
                exec("PRAGMA table_info($table)") { rs ->
                    var ok = false
                    while (rs?.next() == true) {
                        if (rs.getString("name") == column) {
                            ok = true
                            break
                        }
                    }
                    ok
                } ?: false

            fun ensureFreshTable(table: String, requiredColumns: List<String>) {
                if (!tableExists(table)) return
                val missing = requiredColumns.any { !columnExists(table, it) }
                if (missing) {
                    exec("DROP TABLE IF EXISTS $table")
                }
            }

            ensureFreshTable(
                table = "users",
                requiredColumns = listOf("first_seen_at", "last_seen_at", "is_blocked", "blocked_at", "language_code")
            )
            ensureFreshTable(
                table = "usage_counters",
                requiredColumns = listOf("total_messages", "daily_messages", "daily_reset_at")
            )
            ensureFreshTable(
                table = "premium_users",
                requiredColumns = listOf("premium_until")
            )
            ensureFreshTable(
                table = "messages",
                requiredColumns = listOf("user_id", "ts", "direction", "kind")
            )
            ensureFreshTable(
                table = "processed_updates",
                requiredColumns = listOf("update_id")
            )
            ensureFreshTable(
                table = "broadcasts",
                requiredColumns = listOf("created_at", "created_by")
            )
            ensureFreshTable(
                table = "broadcast_recipients",
                requiredColumns = listOf("broadcast_id", "user_id", "status")
            )
        }

        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users,
                UsageCounters,
                PremiumUsers,
                Messages,
                ProcessedUpdates,
                Broadcasts,
                BroadcastRecipients,
                MemoryNotesV2,
                PremiumReminders,
                Payments,
                ChatHistory,
                AdminAudit
            )
        }
    }
}

package app.db

import app.AppConfig
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.sql.Connection

// ---------- Таблицы (актуальная схема) ----------
object Users : Table(name = "users") {
    val user_id = long("user_id").uniqueIndex()
    val first_seen = long("first_seen")
    val blocked_ts = long("blocked_ts").default(0)
    val blocked = bool("blocked").default(false)
    override val primaryKey = PrimaryKey(user_id)
}

object Messages : Table(name = "messages") {
    val id = long("id").autoIncrement()
    val user_id = long("user_id").index()
    val ts = long("ts")
    val text = text("text")
    val role = varchar("role", length = 16).default("user")
    override val primaryKey = PrimaryKey(id)
}

object MemoryNotesV2 : Table(name = "memory_notes_v2") {
    val id = long("id").autoIncrement()
    val user_id = long("user_id").index()
    val note = text("note")
    val ts = long("ts")
    override val primaryKey = PrimaryKey(id)
}

object UserStats : Table(name = "user_stats") {
    val user_id = long("user_id").uniqueIndex()
    val day = varchar("day", 10) // YYYY-MM-DD
    val free_used = integer("free_used")
    override val primaryKey = PrimaryKey(user_id)
}

object ProcessedUpdates : Table(name = "processed_updates") {
    val update_id = long("update_id").uniqueIndex()
    override val primaryKey = PrimaryKey(update_id)
}

object PremiumUsers : Table(name = "premium_users") {
    val user_id = long("user_id").uniqueIndex()
    val until_ts = long("until_ts") // millis epoch
    override val primaryKey = PrimaryKey(user_id)
}

/** Фиксация отправленных напоминаний: kind = "3d" | "1d" | "0d" (auto PK id) */
object PremiumReminders : Table(name = "premium_reminders") {
    val id = long("id").autoIncrement()
    val user_id = long("user_id").index()
    val kind = varchar("kind", 8)
    val sent_ts = long("sent_ts")
    init { index(isUnique = true, columns = arrayOf(user_id, kind)) }
    override val primaryKey = PrimaryKey(id)
}

/** История платежей для предотвращения дублей и расследования инцидентов */
object Payments : Table(name = "payments") {
    val payload = varchar("payload", length = 128)
    val user_id = long("user_id").index()
    val amount_minor = integer("amount_minor")
    val currency = varchar("currency", length = 8)
    val status = varchar("status", length = 32)
    val failure_reason = text("failure_reason").nullable()
    val telegram_charge_id = varchar("telegram_charge_id", length = 128).nullable()
    val provider_charge_id = varchar("provider_charge_id", length = 128).nullable()
    val created_at = long("created_at")
    val updated_at = long("updated_at")
    override val primaryKey = PrimaryKey(payload)
}

object ChatHistory : Table(name = "chat_history") {
    val id = long("id").autoIncrement()
    val user_id = long("user_id").index()
    val mode = varchar("mode", length = 16).index()
    val role = varchar("role", length = 10)
    val text = text("text")
    val ts = long("ts")

    init {
        index(isUnique = false, columns = arrayOf(user_id, mode))
    }

    override val primaryKey = PrimaryKey(id)
}

object DatabaseFactory {

    fun init() {
        // 1) гарантируем наличие директории БД
        File(AppConfig.DB_PATH).parentFile?.mkdirs()

        // 2) подключаемся к SQLite (FK включены)
        val url = "jdbc:sqlite:${AppConfig.DB_PATH}?foreign_keys=on"
        Database.connect(url = url, driver = "org.sqlite.JDBC")

        // 3) уровень изоляции
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // 4) ручные миграции (перед SchemaUtils)
        transaction {
            // --- helpers ---
            fun tableExists(name: String): Boolean =
                exec("SELECT name FROM sqlite_master WHERE type='table' AND name='$name'") { rs ->
                    var found = false
                    while (rs?.next() == true) found = true
                    found
                } ?: false

            fun columnExists(table: String, column: String): Boolean =
                exec("PRAGMA table_info($table)") { rs ->
                    var ok = false
                    while (rs?.next() == true) {
                        if (rs.getString("name") == column) { ok = true; break }
                    }
                    ok
                } ?: false

            fun hasAnyPrimaryKey(table: String): Boolean =
                exec("PRAGMA table_info($table)") { rs ->
                    var pk = false
                    while (rs?.next() == true) {
                        if (rs.getInt("pk") == 1) { pk = true; break }
                    }
                    pk
                } ?: false

            fun addColumnIfMissing(table: String, columnDef: String) {
                val name = columnDef.trim().split("\\s+".toRegex(), limit = 2).first()
                if (!columnExists(table, name)) exec("ALTER TABLE $table ADD COLUMN $columnDef;")
            }

            // === КРИТИЧЕСКАЯ МИГРАЦИЯ: premium_users.until_ms → until_ts ===
            fun forceDropUntilMsInPremiumUsers() {
                val t = "premium_users"
                if (tableExists(t) && columnExists(t, "until_ms")) {
                    // Всегда пересоздаём — чтобы выкинуть until_ms (NOT NULL на старой колонке ломает любые инсёрты)
                    exec("""
                        CREATE TABLE premium_users_new(
                            user_id  INTEGER PRIMARY KEY,
                            until_ts INTEGER NOT NULL
                        );
                    """.trimIndent())
                    // переносим данные: берём until_ts если есть, иначе until_ms, иначе 0
                    exec("""
                        INSERT INTO premium_users_new(user_id, until_ts)
                        SELECT user_id, COALESCE(until_ts, until_ms, 0) AS until_ts
                        FROM premium_users;
                    """.trimIndent())
                    exec("""DROP TABLE premium_users;""")
                    exec("""ALTER TABLE premium_users_new RENAME TO premium_users;""")
                    exec("""CREATE UNIQUE INDEX IF NOT EXISTS premium_users_user_id ON premium_users(user_id);""")
                }
            }

            // --- PK-реконструкции для таблиц с PK на существующих полях ---
            fun recreateIfNoPkUsers() {
                val t = "users"
                if (tableExists(t) && !hasAnyPrimaryKey(t)) {
                    exec("""
                        CREATE TABLE users_new(
                            user_id    INTEGER PRIMARY KEY,
                            first_seen INTEGER NOT NULL,
                            blocked_ts INTEGER NOT NULL DEFAULT 0,
                            blocked    INTEGER NOT NULL DEFAULT 0
                        );
                    """.trimIndent())
                    val hasFirstSeen = columnExists(t, "first_seen")
                    val hasBlocked = columnExists(t, "blocked_ts")
                    val hasBlockedFlag = columnExists(t, "blocked")
                    when {
                        hasFirstSeen && hasBlocked && hasBlockedFlag ->
                            exec(
                                """
                                    INSERT INTO users_new(user_id, first_seen, blocked_ts, blocked)
                                    SELECT user_id, first_seen, blocked_ts, blocked FROM users;
                                """.trimIndent()
                            )
                        hasFirstSeen && hasBlocked ->
                            exec(
                                """
                                    INSERT INTO users_new(user_id, first_seen, blocked_ts, blocked)
                                    SELECT user_id,
                                           first_seen,
                                           blocked_ts,
                                           CASE WHEN blocked_ts > 0 THEN 1 ELSE 0 END AS blocked
                                    FROM users;
                                """.trimIndent()
                            )
                        hasFirstSeen && hasBlockedFlag ->
                            exec(
                                """
                                    INSERT INTO users_new(user_id, first_seen, blocked_ts, blocked)
                                    SELECT user_id, first_seen, 0 AS blocked_ts, blocked FROM users;
                                """.trimIndent()
                            )
                        else ->
                            exec(
                                """
                                    INSERT INTO users_new(user_id, first_seen, blocked_ts, blocked)
                                    SELECT user_id,
                                           COALESCE(first_seen, 0) AS first_seen,
                                           COALESCE(blocked_ts, 0) AS blocked_ts,
                                           CASE
                                               WHEN COALESCE(blocked_ts, 0) > 0 THEN 1
                                               WHEN COALESCE(blocked, 0) != 0 THEN 1
                                               ELSE 0
                                           END AS blocked
                                    FROM users;
                                """.trimIndent()
                            )
                    }
                    exec("""DROP TABLE users;""")
                    exec("""ALTER TABLE users_new RENAME TO users;""")
                    exec("""CREATE UNIQUE INDEX IF NOT EXISTS users_user_id ON users(user_id);""")
                }
            }

            fun recreateIfNoPkUserStats() {
                val t = "user_stats"
                if (tableExists(t) && !hasAnyPrimaryKey(t)) {
                    exec("""
                        CREATE TABLE user_stats_new(
                            user_id   INTEGER PRIMARY KEY,
                            day       TEXT    NOT NULL,
                            free_used INTEGER NOT NULL
                        );
                    """.trimIndent())
                    val hasDay = columnExists(t, "day")
                    val hasFree = columnExists(t, "free_used")
                    when {
                        hasDay && hasFree ->
                            exec("""INSERT INTO user_stats_new(user_id, day, free_used) SELECT user_id, day, free_used FROM user_stats;""")
                        hasDay && !hasFree ->
                            exec("""INSERT INTO user_stats_new(user_id, day, free_used) SELECT user_id, day, 0 FROM user_stats;""")
                        !hasDay && hasFree ->
                            exec("""INSERT INTO user_stats_new(user_id, day, free_used) SELECT user_id, '' AS day, free_used FROM user_stats;""")
                        else ->
                            exec("""INSERT INTO user_stats_new(user_id, day, free_used) SELECT user_id, '' AS day, 0 AS free_used FROM user_stats;""")
                    }
                    exec("""DROP TABLE user_stats;""")
                    exec("""ALTER TABLE user_stats_new RENAME TO user_stats;""")
                    exec("""CREATE UNIQUE INDEX IF NOT EXISTS user_stats_user_id ON user_stats(user_id);""")
                }
            }

            fun recreateIfNoPkProcessedUpdates() {
                val t = "processed_updates"
                if (tableExists(t) && !hasAnyPrimaryKey(t)) {
                    exec("""
                        CREATE TABLE processed_updates_new(
                            update_id INTEGER PRIMARY KEY
                        );
                    """.trimIndent())
                    exec("""INSERT INTO processed_updates_new(update_id) SELECT update_id FROM processed_updates;""")
                    exec("""DROP TABLE processed_updates;""")
                    exec("""ALTER TABLE processed_updates_new RENAME TO processed_updates;""")
                    exec("""CREATE UNIQUE INDEX IF NOT EXISTS processed_updates_update_id ON processed_updates(update_id);""")
                }
            }

            // --- авто-PK реконструкции (id AUTOINCREMENT) ---
            fun recreateMessagesIfNeeded() {
                val t = "messages"
                if (tableExists(t) && !columnExists(t, "id")) {
                    exec("""
                        CREATE TABLE messages_new(
                            id      INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            ts      INTEGER NOT NULL,
                            text    TEXT    NOT NULL,
                            role    TEXT    NOT NULL DEFAULT 'user'
                        );
                    """.trimIndent())
                    val hasTsOld = columnExists(t, "ts")
                    val hasTextOld = columnExists(t, "text")
                    val hasContentOld = columnExists(t, "content")
                    val tsExpr = if (hasTsOld) "COALESCE(ts, 0)" else "0"
                    val textExpr = when {
                        hasTextOld && hasContentOld -> "COALESCE(text, content, '')"
                        hasTextOld -> "COALESCE(text, '')"
                        hasContentOld -> "COALESCE(content, '')"
                        else -> "''"
                    }
                    exec(
                        """
                            INSERT INTO messages_new(user_id, ts, text, role)
                            SELECT user_id,
                                   $tsExpr AS ts,
                                   CASE WHEN TRIM($textExpr) = '' THEN '[legacy]' ELSE $textExpr END AS text,
                                   COALESCE(role, 'user') AS role
                            FROM messages;
                        """.trimIndent()
                    )
                    exec("""DROP TABLE messages;""")
                    exec("""ALTER TABLE messages_new RENAME TO messages;""")
                    exec("""CREATE INDEX IF NOT EXISTS messages_user_id ON messages(user_id);""")
                }
            }

            fun recreateMessagesIfHasContentColumn() {
                val t = "messages"
                if (!tableExists(t) || !columnExists(t, "content")) return
                exec("""
                    CREATE TABLE messages_new(
                        id      INTEGER PRIMARY KEY AUTOINCREMENT,
                        user_id INTEGER NOT NULL,
                        ts      INTEGER NOT NULL,
                        text    TEXT    NOT NULL,
                        role    TEXT    NOT NULL DEFAULT 'user'
                    );
                """.trimIndent())
                val hasTsOld = columnExists(t, "ts")
                val hasTextOld = columnExists(t, "text")
                val tsExpr = if (hasTsOld) "COALESCE(ts, 0)" else "0"
                val textExpr = if (hasTextOld) "COALESCE(text, content, '')" else "COALESCE(content, '')"
                exec(
                    """
                        INSERT INTO messages_new(user_id, ts, text, role)
                        SELECT user_id,
                               $tsExpr AS ts,
                               CASE WHEN TRIM($textExpr) = '' THEN '[legacy]' ELSE $textExpr END AS text,
                               COALESCE(role, 'user') AS role
                        FROM messages;
                    """.trimIndent()
                )
                exec("""DROP TABLE messages;""")
                exec("""ALTER TABLE messages_new RENAME TO messages;""")
                exec("""CREATE INDEX IF NOT EXISTS messages_user_id ON messages(user_id);""")
            }

            fun recreateMemoryNotesIfNeeded() {
                val t = "memory_notes_v2"
                if (tableExists(t) && !columnExists(t, "id")) {
                    exec("""
                        CREATE TABLE memory_notes_v2_new(
                            id      INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            note    TEXT    NOT NULL,
                            ts      INTEGER NOT NULL
                        );
                    """.trimIndent())
                    val hasTsOld = columnExists(t, "ts")
                    if (hasTsOld)
                        exec("""INSERT INTO memory_notes_v2_new(user_id, note, ts) SELECT user_id, note, ts FROM memory_notes_v2;""")
                    else
                        exec("""INSERT INTO memory_notes_v2_new(user_id, note, ts) SELECT user_id, note, 0 AS ts FROM memory_notes_v2;""")
                    exec("""DROP TABLE memory_notes_v2;""")
                    exec("""ALTER TABLE memory_notes_v2_new RENAME TO memory_notes_v2;""")
                    exec("""CREATE INDEX IF NOT EXISTS memory_notes_v2_user_id ON memory_notes_v2(user_id);""")
                }
            }

            fun recreatePremiumRemindersIfNeeded() {
                val t = "premium_reminders"
                if (tableExists(t) && !columnExists(t, "id")) {
                    exec("""
                        CREATE TABLE premium_reminders_new(
                            id      INTEGER PRIMARY KEY AUTOINCREMENT,
                            user_id INTEGER NOT NULL,
                            kind    TEXT    NOT NULL,
                            sent_ts INTEGER NOT NULL
                        );
                    """.trimIndent())
                    exec("""INSERT INTO premium_reminders_new(user_id, kind, sent_ts) SELECT user_id, kind, sent_ts FROM premium_reminders;""")
                    exec("""DROP TABLE premium_reminders;""")
                    exec("""ALTER TABLE premium_reminders_new RENAME TO premium_reminders;""")
                    exec("""CREATE INDEX IF NOT EXISTS premium_reminders_user_id ON premium_reminders(user_id);""")
                    exec("""CREATE UNIQUE INDEX IF NOT EXISTS premium_reminders_user_kind ON premium_reminders(user_id, kind);""")
                }
            }

            // ---- Порядок критичен: сначала выносим until_ms, затем остальное ----
            forceDropUntilMsInPremiumUsers()
            recreateIfNoPkUsers()
            recreateIfNoPkUserStats()
            recreateIfNoPkProcessedUpdates()
            recreateMessagesIfHasContentColumn()
            recreateMessagesIfNeeded()
            recreateMemoryNotesIfNeeded()
            recreatePremiumRemindersIfNeeded()

            // ---- Добавляем недостающие колонки (если вдруг чего-то не было) ----
            if (tableExists("messages")) {
                addColumnIfMissing("messages", "ts INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("messages", "text TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing("messages", "user_id INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("messages", "role TEXT NOT NULL DEFAULT 'user'")
                exec("""UPDATE messages SET role = 'user' WHERE role IS NULL OR role = ''""")
                exec("""UPDATE messages SET text = '[legacy]' WHERE text IS NULL OR TRIM(text) = ''""")
            }
            if (tableExists("memory_notes_v2")) {
                addColumnIfMissing("memory_notes_v2", "ts INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("memory_notes_v2", "note TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing("memory_notes_v2", "user_id INTEGER NOT NULL DEFAULT 0")
            }
            if (tableExists("premium_reminders")) {
                addColumnIfMissing("premium_reminders", "sent_ts INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("premium_reminders", "kind TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing("premium_reminders", "user_id INTEGER NOT NULL DEFAULT 0")
            }
            if (tableExists("premium_users")) {
                addColumnIfMissing("premium_users", "until_ts INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("premium_users", "user_id INTEGER NOT NULL DEFAULT 0")
            }
            if (tableExists("users")) {
                addColumnIfMissing("users", "first_seen INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("users", "user_id INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("users", "blocked_ts INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("users", "blocked INTEGER NOT NULL DEFAULT 0")
                if (columnExists("users", "blocked") && columnExists("users", "blocked_ts")) {
                    exec("""UPDATE users SET blocked = CASE WHEN blocked_ts > 0 THEN 1 ELSE 0 END""")
                }
            }
            if (tableExists("user_stats")) {
                addColumnIfMissing("user_stats", "day TEXT NOT NULL DEFAULT ''")
                addColumnIfMissing("user_stats", "free_used INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("user_stats", "user_id INTEGER NOT NULL DEFAULT 0")
            }
            if (tableExists("processed_updates")) {
                addColumnIfMissing("processed_updates", "update_id INTEGER NOT NULL DEFAULT 0")
            }
            if (tableExists("payments")) {
                addColumnIfMissing("payments", "status TEXT NOT NULL DEFAULT 'invoice'")
                addColumnIfMissing("payments", "failure_reason TEXT NULL")
                addColumnIfMissing("payments", "telegram_charge_id TEXT NULL")
                addColumnIfMissing("payments", "provider_charge_id TEXT NULL")
                addColumnIfMissing("payments", "amount_minor INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("payments", "currency TEXT NOT NULL DEFAULT 'RUB'")
                addColumnIfMissing("payments", "created_at INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("payments", "updated_at INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing("payments", "user_id INTEGER NOT NULL DEFAULT 0")
            }
            if (tableExists("usage_counters")) {
                addColumnIfMissing("usage_counters", "total_used INTEGER NOT NULL DEFAULT 0")
                val hasUsed = columnExists("usage_counters", "used")
                val hasTotal = columnExists("usage_counters", "total_used")
                if (hasUsed && hasTotal) {
                    exec("UPDATE usage_counters SET total_used = used WHERE total_used = 0;")
                }
            }
        }

        // 5) добиваем недостающие объекты штатно
        transaction {
            SchemaUtils.createMissingTablesAndColumns(
                Users, Messages, MemoryNotesV2, UserStats, ProcessedUpdates,
                PremiumUsers, PremiumReminders, Payments, UsageCounters, ChatHistory
            )
        }

        runCatching { UserRegistry.backfillFromExistingData() }
            .onFailure { println("WARN: users quick backfill failed: ${it.message}") }

        runCatching { UsersRepo.repairOrphans(source = "startup") }
            .onFailure { println("WARN: users backfill failed: ${it.message}") }
    }
}

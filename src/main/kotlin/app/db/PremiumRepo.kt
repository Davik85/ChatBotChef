package app.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Репозиторий премиума в таблице premium_users.
 * Чинит дублирующийся индекс: больше НЕТ uniqueIndex на user_id — только PRIMARY KEY.
 * При первой инициализации пытается дропнуть старый индекс premium_users_user_id_unique.
 */
object PremiumRepo {

    private object PremiumUsers : Table("premium_users") {
        // Было: uniqueIndex("premium_users_user_id_unique") — УДАЛЕНО.
        val userId = long("user_id")
        val untilMs = long("until_ms").index("premium_users_until_ms_idx")
        override val primaryKey = PrimaryKey(userId, name = "pk_premium_users")
    }

    private val inited = AtomicBoolean(false)

    /** Ленивая инициализация таблицы + миграция (дроп лишнего индекса, если остался). */
    private fun ensure() {
        if (inited.compareAndSet(false, true)) {
            transaction {
                // Дроп legacy-индекса, который дублирует PRIMARY KEY:
                try {
                    exec("DROP INDEX IF EXISTS premium_users_user_id_unique")
                } catch (_: Throwable) {
                    // ничего страшного, просто продолжаем
                }
                createMissingTablesAndColumns(PremiumUsers)
            }
        }
    }

    /** Установить премиум до абсолютного времени (мс epoch). */
    fun setUntil(userId: Long, untilEpochMs: Long) {
        ensure()
        transaction {
            val exists = PremiumUsers.select { PremiumUsers.userId eq userId }.limit(1).any()
            if (exists) {
                PremiumUsers.update({ PremiumUsers.userId eq userId }) {
                    it[untilMs] = untilEpochMs
                }
            } else {
                PremiumUsers.insert {
                    it[PremiumUsers.userId] = userId
                    it[untilMs] = untilEpochMs
                }
            }
        }
    }

    /** Выдать премиум на days дней (продлевает, если уже активен). */
    fun grantDays(userId: Long, days: Int) {
        ensure()
        val addMs = days.toLong() * 24 * 60 * 60 * 1000
        transaction {
            val now = System.currentTimeMillis()
            val row = PremiumUsers.select { PremiumUsers.userId eq userId }.firstOrNull()
            val base = maxOf(now, row?.get(PremiumUsers.untilMs) ?: 0L)
            val newUntil = base + addMs
            if (row == null) {
                PremiumUsers.insert {
                    it[PremiumUsers.userId] = userId
                    it[untilMs] = newUntil
                }
            } else {
                PremiumUsers.update({ PremiumUsers.userId eq userId }) {
                    it[untilMs] = newUntil
                }
            }
        }
    }

    /** Премиум активен на текущий момент? */
    fun isActive(userId: Long): Boolean {
        ensure()
        return transaction {
            val now = System.currentTimeMillis()
            PremiumUsers
                .slice(PremiumUsers.untilMs)
                .select { PremiumUsers.userId eq userId }
                .firstOrNull()
                ?.get(PremiumUsers.untilMs)
                ?.let { it > now } == true
        }
    }

    /** Вернуть время окончания (мс epoch) или null. */
    fun getUntil(userId: Long): Long? {
        ensure()
        return transaction {
            PremiumUsers
                .slice(PremiumUsers.untilMs)
                .select { PremiumUsers.userId eq userId }
                .firstOrNull()
                ?.get(PremiumUsers.untilMs)
        }
    }
}

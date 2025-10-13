package app.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SchemaUtils.createMissingTablesAndColumns

/**
 * Лёгкий репозиторий для премиума.
 * Сам поднимет таблицу premium_users при первом вызове любого метода.
 */
object PremiumRepo {

    private object PremiumUsers : Table("premium_users") {
        val userId = long("user_id").uniqueIndex("premium_users_user_id_unique")
        val untilMs = long("until_ms").index("premium_users_until_ms_idx")
        override val primaryKey = PrimaryKey(userId, name = "pk_premium_users")
    }

    /** Ленивая инициализация таблицы (если DatabaseFactory.init() уже вызван — всё ок). */
    private fun ensure() {
        transaction {
            createMissingTablesAndColumns(PremiumUsers)
        }
    }

    /** Установить премиум до абсолютного времени (ms epoch). */
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

    /** Выдать премиум на days дней, начиная с текущего момента (или продлить, если уже есть). */
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

    /** Проверить активен ли премиум (на момент вызова). */
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

    /** Получить срок окончания (или null, если нет записи). */
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

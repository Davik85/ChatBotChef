package app.logic

import app.AppConfig
import app.db.UserStats
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max

object RateLimiter {
    private val tz = ZoneId.of("Europe/Belgrade")
    private fun today(): String = LocalDate.now(tz).toString() // yyyy-MM-dd

    /**
     * Проверяет лимит и, если день изменился — сбрасывает счётчик.
     * Ничего не инкрементит.
     */
    fun canSend(tgUserId: Long): Boolean = transaction {
        val d = today()
        val row = UserStats.select { UserStats.userId eq tgUserId }.firstOrNull()
        if (row == null) {
            // нет записи — значит 0/лимит
            return@transaction true
        }
        val dayInDb = row[UserStats.day]
        val sent = row[UserStats.sentToday]
        if (dayInDb != d) {
            // новый день — обнуляем
            UserStats.update({ UserStats.userId eq tgUserId }) {
                it[UserStats.day] = d
                it[UserStats.sentToday] = 0
            }
            return@transaction true
        }
        sent < AppConfig.FREE_DAILY_MSG_LIMIT
    }

    /**
     * Инкремент счётчика сообщений пользователя (с учётом смены дня).
     * Вызывай только ПОСЛЕ успешной отправки ответа в TG.
     */
    fun increment(tgUserId: Long) = transaction {
        val d = today()
        val row = UserStats.select { UserStats.userId eq tgUserId }.firstOrNull()
        if (row == null) {
            UserStats.insert {
                it[UserStats.userId] = tgUserId
                it[UserStats.day] = d
                it[UserStats.sentToday] = 1
            }
        } else {
            val dayInDb = row[UserStats.day]
            val sent = row[UserStats.sentToday]
            if (dayInDb != d) {
                UserStats.update({ UserStats.userId eq tgUserId }) {
                    it[UserStats.day] = d
                    it[UserStats.sentToday] = 1
                }
            } else {
                UserStats.update({ UserStats.userId eq tgUserId }) {
                    it[UserStats.sentToday] = max(0, sent + 1)
                }
            }
        }
    }

    /** Сколько осталось сообщений до лимита (для UX и диагностики). */
    fun remaining(tgUserId: Long): Int = transaction {
        val d = today()
        val row = UserStats.select { UserStats.userId eq tgUserId }.firstOrNull()
        if (row == null) return@transaction AppConfig.FREE_DAILY_MSG_LIMIT
        val dayInDb = row[UserStats.day]
        val sent = if (dayInDb == d) row[UserStats.sentToday] else 0
        (AppConfig.FREE_DAILY_MSG_LIMIT - sent).coerceAtLeast(0)
    }
}

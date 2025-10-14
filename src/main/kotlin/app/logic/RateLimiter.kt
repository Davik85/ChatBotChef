package app.logic

import app.AppConfig
import app.db.PremiumRepo
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Простой лимитер "N ответов ИИ в сутки на пользователя".
 * - Премиум-пользователи не ограничены.
 * - Счётчик хранится в памяти процесса и обнуляется при смене даты.
 *   (если понадобится жёсткая персистентность — можно вынести в БД, но для paywall это обычно не критично)
 */
object RateLimiter {

    private data class Counter(var day: LocalDate, var used: Int)

    // chatId -> Counter
    private val map = ConcurrentHashMap<Long, Counter>()

    /**
     * Проверить лимит и (если можно) списать единицу.
     * @return true — можно отвечать платным ответом ИИ; false — лимит исчерпан.
     */
    fun checkAndConsume(chatId: Long): Boolean {
        // Премиум — без ограничений
        if (PremiumRepo.isActive(chatId)) return true

        val today = LocalDate.now()
        val counter = map.compute(chatId) { _, old ->
            when {
                old == null -> Counter(today, 0)
                old.day != today -> Counter(today, 0) // новый день — обнуляем
                else -> old
            }
        }!!

        synchronized(counter) {
            if (counter.day != today) {
                counter.day = today
                counter.used = 0
            }
            if (counter.used >= AppConfig.FREE_DAILY_MSG_LIMIT) {
                return false
            }
            counter.used += 1
            return true
        }
    }

    /** Сколько уже потрачено сегодня (для диагностики/отладки). */
    fun usedToday(chatId: Long): Int {
        val c = map[chatId] ?: return 0
        return if (c.day == LocalDate.now()) c.used else 0
    }
}

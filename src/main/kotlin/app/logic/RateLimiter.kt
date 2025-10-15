package app.logic

import app.AppConfig
import app.db.PremiumRepo
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Простой лимитер: N бесплатных сообщений в сутки.
 * Если у пользователя активен премиум — лимита нет.
 */
object RateLimiter {

    private data class Counter(
        val day: LocalDate,
        val used: Int
    )

    // chatId -> Counter
    private val map = ConcurrentHashMap<Long, Counter>()

    /**
     * @return true — можно отвечать платным ответом ИИ; false — лимит исчерпан.
     */
    fun checkAndConsume(chatId: Long): Boolean {
        // Премиум — без ограничений
        val until = PremiumRepo.getUntil(chatId)
        val nowMs = System.currentTimeMillis()
        if (until != null && until > nowMs) return true

        val today = LocalDate.now()

        val updated = map.compute(chatId) { _, old ->
            when {
                old == null -> Counter(today, used = 1)
                old.day != today -> Counter(today, used = 1) // новый день — обнуляем
                else -> Counter(today, used = old.used + 1)
            }
        }!!

        return updated.used <= AppConfig.FREE_DAILY_MSG_LIMIT
    }

    /** Сбросить счетчик для пользователя (опционально, если пригодится). */
    fun reset(chatId: Long) {
        map.remove(chatId)
    }
}

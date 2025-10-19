package app.logic

import app.AppConfig
import app.db.PremiumRepo
import app.db.UsageRepo

object RateLimiter {

    private val adminIds: Set<Long> by lazy {
        val raw = System.getenv("ADMIN_IDS") ?: ""
        raw.split(",")
            .mapNotNull { token ->
                val trimmed = token.trim()
                if (trimmed.isEmpty()) null else trimmed.toLongOrNull()
            }
            .toSet()
    }

    private fun isAdmin(userId: Long): Boolean = adminIds.contains(userId)

    private fun hasPremium(userId: Long): Boolean {
        val until = PremiumRepo.getUntil(userId)
        val nowMs = System.currentTimeMillis()
        return until != null && until > nowMs
    }

    /**
     * @return true — можно отвечать платным ответом ИИ; false — лимит исчерпан.
     */
    fun checkAndConsume(chatId: Long): Boolean {
        if (isAdmin(chatId) || hasPremium(chatId)) return true

        val used = UsageRepo.getUsed(chatId)
        if (used >= AppConfig.FREE_TOTAL_MSG_LIMIT) return false

        val after = UsageRepo.incrementAndGet(chatId)
        return after <= AppConfig.FREE_TOTAL_MSG_LIMIT
    }
}

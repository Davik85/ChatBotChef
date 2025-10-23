package app.logic

import app.AppConfig
import app.db.PremiumRepo
import app.db.UsageCountersRepo

object RateLimiter {
    fun allow(userId: Long, isAdmin: Boolean): Boolean {
        if (isAdmin) return true
        if (PremiumRepo.isActive(userId)) return true
        return UsageCountersRepo.getTotalUsed(userId) < AppConfig.FREE_TOTAL_MSG_LIMIT
    }

    fun consumeIfFree(userId: Long, isAdmin: Boolean) {
        if (isAdmin) return
        if (PremiumRepo.isActive(userId)) return
        if (UsageCountersRepo.getTotalUsed(userId) < AppConfig.FREE_TOTAL_MSG_LIMIT) {
            UsageCountersRepo.inc(userId)
        }
    }
}

package app.logic

import app.AppConfig
import app.db.PremiumRepo
import app.db.UsageCountersRepo

object UsageLimiter {

    fun allow(userId: Long, isAdminFlag: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        if (isUnlimited(userId, isAdminFlag)) return true
        if (PremiumRepo.isActive(userId)) return true
        val counters = UsageCountersRepo.getOrCreate(userId, now)
        val totalLimit = AppConfig.FREE_TOTAL_MSG_LIMIT
        val dailyLimit = AppConfig.FREE_DAILY_MSG_LIMIT
        val totalOk = totalLimit <= 0 || counters.totalMessages < totalLimit
        val dailyOk = dailyLimit <= 0 || counters.dailyMessages < dailyLimit
        return totalOk && dailyOk
    }

    fun consume(userId: Long, isAdminFlag: Boolean, now: Long = System.currentTimeMillis()) {
        if (isUnlimited(userId, isAdminFlag)) return
        if (PremiumRepo.isActive(userId)) return
        val totalLimit = AppConfig.FREE_TOTAL_MSG_LIMIT
        val dailyLimit = AppConfig.FREE_DAILY_MSG_LIMIT
        if (totalLimit <= 0 && dailyLimit <= 0) return
        UsageCountersRepo.incrementIfAllowed(userId, now, totalLimit, dailyLimit)
    }

    private fun isUnlimited(userId: Long, isAdminFlag: Boolean): Boolean {
        if (isAdminFlag) return true
        return AppConfig.adminIds.contains(userId)
    }
}

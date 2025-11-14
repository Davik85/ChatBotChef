package app.logic.stats

import app.db.MessagesRepo
import app.db.PremiumRepo
import app.db.UsersRepo

object StatsService {
    data class Snapshot(
        val total: Long,
        val blocked: Long,
        val activeInstalls: Long,
        val premium: Long,
        val active7d: Long,
    )

    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val ACTIVE_INSTALL_WINDOW_DAYS = 30L

    fun activeInstallThreshold(now: Long = System.currentTimeMillis()): Long =
        now - ACTIVE_INSTALL_WINDOW_DAYS * DAY_MS

    fun collect(now: Long = System.currentTimeMillis()): Snapshot {
        val threshold = activeInstallThreshold(now)
        val userSummary = UsersRepo.summarizeForStats(threshold)
        val total = userSummary.totalUsers
        val blocked = userSummary.blockedUsers.coerceAtMost(total)
        val activeInstalls = userSummary.activeUsers.coerceAtMost(total)
        val premium = PremiumRepo.countActive(now)
        val active7dThreshold = now - 7 * DAY_MS
        val active7d = MessagesRepo.countActiveSince(active7dThreshold).coerceAtLeast(0L)
        println(
            "ADMIN-STATS-DBG: users_total=$total users_blocked=$blocked " +
                "users_active_window=$activeInstalls window_from=$threshold " +
                "premium_active=$premium users_active7d=$active7d " +
                "sources=${userSummary.sourcesUsed} active_window_candidates=${userSummary.activeWindowPopulation}"
        )
        return Snapshot(
            total = total,
            blocked = blocked,
            activeInstalls = activeInstalls,
            premium = premium,
            active7d = active7d,
        )
    }

    /**
     * Срезы статистики строятся на базе текущей схемы БД:
     * - UsersRepo.summarizeForStats() собирает пользователей из users/messages/chat_history/premium_* и usage_counters,
     *   чтобы total/blocked/activeInstalls оставались корректными даже если users ещё не успела наполниться
     *   или содержит легаси-записи.
     * - активные за 7 дней считаются по таблице messages, чтобы совпасть с ADMIN-STATUS;
     * - количество премиум-пользователей берётся из таблицы premium_users через PremiumRepo.
     */
}

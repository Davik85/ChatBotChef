package app.logic.stats

import app.db.PremiumRepo
import app.db.UsersRepo
import kotlin.math.min

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
        val derivedActiveInstalls = (total - blocked).coerceAtLeast(0L)
        val activeInstalls = min(userSummary.activeInstalls, derivedActiveInstalls).coerceAtLeast(0L)
        val premium = PremiumRepo.countActive(now)
        val active7dThreshold = now - 7 * DAY_MS
        val active7d = UsersRepo.countActiveSince(active7dThreshold).coerceAtLeast(0L)
        println(
            "ADMIN-STATS-DBG: users_total=$total users_blocked=$blocked " +
                "users_active_installs=$activeInstalls window_from=$threshold " +
                "users_summary_active_installs=${userSummary.activeInstalls} " +
                "users_active_window=${userSummary.activeWindowPopulation} " +
                "sources=${userSummary.sourcesUsed} " +
                "premium_active=$premium users_active7d=$active7d"
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
     * Срезы статистики строятся на базе объединения данных из `users` и вспомогательных таблиц:
     * - UsersRepo.summarizeForStats() агрегирует user_id из users/messages/chat_history, premium_* таблиц и usage_counters,
     *   чтобы total/blocked/activeInstalls учитывали всех, кто взаимодействовал с ботом, даже если users ещё не заполнена;
     * - активные за 7 дней считаются по `users.last_seen`, чтобы учитывать только актуальные, незаблокированные аккаунты;
     * - количество премиум-пользователей берётся из таблицы premium_users через PremiumRepo.
     */
}

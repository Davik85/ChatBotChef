package app.logic.stats

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

    fun activeInstallThreshold(now: Long = System.currentTimeMillis()): Long =
        now - 30L * DAY_MS

    fun collect(now: Long = System.currentTimeMillis()): Snapshot {
        val total = UsersRepo.countTotal().coerceAtLeast(0L)
        val blocked = UsersRepo.countBlocked().coerceAtLeast(0L)
        val activeInstalls = UsersRepo.countActive().coerceAtLeast(0L)
        val premium = PremiumRepo.countActive(now).coerceAtLeast(0L)
        val active7dThreshold = now - 7 * DAY_MS
        val active7d = UsersRepo.countActiveSince(active7dThreshold).coerceAtLeast(0L)
        return Snapshot(
            total = total,
            blocked = blocked,
            activeInstalls = activeInstalls,
            premium = premium,
            active7d = active7d,
        )
    }
}

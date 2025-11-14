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

    fun collect(now: Long = System.currentTimeMillis()): Snapshot {
        val total = UsersRepo.countUsers(includeBlocked = true)
        val activeInstalls = UsersRepo.countUsers(includeBlocked = false).coerceAtMost(total)
        val blocked = UsersRepo.countBlocked().coerceAtMost(total)
        val premium = PremiumRepo.countActive(now)
        val active7d = UsersRepo.countActiveSince(now - 7 * DAY_MS).coerceAtLeast(0)
        return Snapshot(
            total = total,
            blocked = blocked,
            activeInstalls = activeInstalls,
            premium = premium,
            active7d = active7d,
        )
    }
}

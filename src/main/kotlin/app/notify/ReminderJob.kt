package app.notify

import app.db.PremiumRepo
import app.telegram.TelegramApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class ReminderJob(private val api: TelegramApi) {
    private val dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

    suspend fun runForever() = coroutineScope {
        launch {
            while (isActive) {
                try {
                    tick()
                } catch (_: Throwable) {
                }
                delay(60 * 60 * 1000L)
            }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        fun win(days: Int) = now + days * 24L * 3600_000L to (now + days * 24L * 3600_000L + 3600_000L)

        win(2).let { (from, to) ->
            PremiumRepo.forEachExpiringInWindow(from, to) { uid, until ->
                if (PremiumRepo.markReminderSent(uid, "2d")) {
                    api.sendMessage(uid, "Напоминание: подписка истекает через 2 дня (до: ${dtf.format(Instant.ofEpochMilli(until))}). Продлить: /premium")
                }
            }
        }
        win(1).let { (from, to) ->
            PremiumRepo.forEachExpiringInWindow(from, to) { uid, until ->
                if (PremiumRepo.markReminderSent(uid, "1d")) {
                    api.sendMessage(uid, "Напоминание: подписка истекает завтра (до: ${dtf.format(Instant.ofEpochMilli(until))}). Продлить: /premium")
                }
            }
        }
    }
}

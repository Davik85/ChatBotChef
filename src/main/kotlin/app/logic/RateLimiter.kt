package app.logic

object RateLimiter {
    fun allow(userId: Long, isAdmin: Boolean): Boolean {
        return UsageLimiter.allow(userId, isAdmin)
    }

    fun consumeIfFree(userId: Long, isAdmin: Boolean) {
        if (!UsageLimiter.allow(userId, isAdmin)) return
        UsageLimiter.consume(userId, isAdmin)
    }
}

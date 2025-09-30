package app.logic

/**
 * Lightweight safety checks (keyword-based).
 * Later you can replace with small LLM classifiers.
 */
object Safety {
    private val crisisKeywords = listOf(
        "суицид", "покончить с собой", "не хочу жить", "умереть", "убить себя",
        "suicide", "kill myself", "i want to die"
    )
    private val nsfwKeywords = listOf(
        "18+", "порно", "sex", "nsfw" // keep short, rely on persona to deflect
    )

    fun isCrisis(text: String): Boolean =
        crisisKeywords.any { text.contains(it, ignoreCase = true) }

    fun isNSFW(text: String): Boolean =
        nsfwKeywords.any { text.contains(it, ignoreCase = true) }
}

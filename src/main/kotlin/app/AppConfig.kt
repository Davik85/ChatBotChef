package app

import io.github.cdimascio.dotenv.dotenv
import java.util.Locale

object AppConfig {
    private val env = dotenv { ignoreIfMissing = true }
    private fun readRaw(key: String): String? = System.getenv(key) ?: env[key]

    // --- утилиты ---
    private fun clean(s: String?): String? {
        if (s == null) return null
        var v = s.trim().trim('"', '\'')
        v = v.replace("\r", "").replace("\n", "")
        v = v.replace(Regex("[\\u0000-\\u001F\\u007F\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]"), "")
        return v.ifBlank { null }
    }

    private fun requireOrError(name: String, raw: String?, validator: (String) -> Boolean): String {
        val v = clean(raw)
        require(!v.isNullOrBlank()) { "$name is required" }
        require(validator(v!!)) { "$name has invalid format" }
        return v
    }

    // --- валидаторы ---
    private val tgTokenRegex = Regex("""^\d+:[A-Za-z0-9_-]{30,}$""")
    private fun isValidTgToken(s: String) = tgTokenRegex.matches(s)
    private val openAiRegex = Regex("""^(sk-[A-Za-z0-9_-]{10,}|sk-proj-[A-Za-z0-9_-]{10,})$""")
    private fun isValidOpenAi(s: String) = openAiRegex.matches(s)

    // --- обязательные токены ---
    val telegramToken: String by lazy {
        requireOrError("TELEGRAM_TOKEN", readRaw("TELEGRAM_TOKEN"), ::isValidTgToken)
    }
    val openAiApiKey: String by lazy {
        requireOrError("OPENAI_API_KEY", readRaw("OPENAI_API_KEY"), ::isValidOpenAi)
    }

    // --- доп. Заголовки OpenAI (опционально) ---
    val openAiOrg: String? by lazy { clean(readRaw("OPENAI_ORG")) }
    val openAiProject: String? by lazy { clean(readRaw("OPENAI_PROJECT")) }

    // --- общие константы ---
    const val TELEGRAM_BASE = "https://api.telegram.org"
    const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    const val OPENAI_MAX_TOKENS = 1800
    const val MAX_REPLY_CHARS = 1800
    const val FREE_TOTAL_MSG_LIMIT = 3
    const val FREE_DAILY_MSG_LIMIT = 3
    const val HISTORY_MAX_TURNS = 30
    const val HISTORY_MAX_CHARS_PER_MSG = 1000
    const val HISTORY_MAX_TOTAL_CHARS = 12_000

    // --- путь к sqlite ---
    val DB_PATH: String by lazy { clean(readRaw("DB_PATH")) ?: "data/chatbotchef.sqlite" }

    // --- платежи в Telegram ---
    // Тестовый/боевой provider token из @BotFather → Payments (может отсутствовать на окружениях без оплаты)
    val providerToken: String? by lazy { clean(readRaw("PROVIDER_TOKEN")) }
    val paymentsEnabled: Boolean
        get() = !providerToken.isNullOrBlank()

    private fun parseBoolean(key: String, raw: String): Boolean = when (raw.lowercase(Locale.ROOT)) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> error("$key must be boolean-like (true/false)")
    }

    private fun readOptionalBoolean(key: String): Boolean? {
        val raw = clean(readRaw(key)) ?: return null
        return parseBoolean(key, raw)
    }

    // Цена и срок подписки
    val premiumPriceRub: Int by lazy { (clean(readRaw("PREMIUM_PRICE_RUB")) ?: "700").toInt() }
    val premiumDays: Int by lazy { (clean(readRaw("PREMIUM_DAYS")) ?: "30").toInt() }

    // Параметры для чека (54-ФЗ)
    val isNpdMerchant: Boolean by lazy { readOptionalBoolean("NPD_MODE") ?: false }
    val receiptsEnabled: Boolean by lazy {
        if (isNpdMerchant) {
            false
        } else {
            readOptionalBoolean("RECEIPTS_ENABLED") ?: true
        }
    }

    private fun readOptionalInt(key: String): Int? {
        val raw = clean(readRaw(key)) ?: return null
        return raw.toIntOrNull() ?: error("$key must be a number")
    }

    val taxSystemCode: Int? by lazy {
        if (!receiptsEnabled) return@lazy null
        val value = readOptionalInt("TAX_SYSTEM_CODE") ?: 2
        require(value in 1..6) { "TAX_SYSTEM_CODE must be between 1 and 6" }
        value
    }     // 1-6
    val vatCode: Int? by lazy {
        if (!receiptsEnabled) return@lazy null
        val value = readOptionalInt("VAT_CODE") ?: 6
        require(value in 1..6) { "VAT_CODE must be between 1 and 6" }
        value
    }                  // 1..6 (6=НДС не облагается)
    val paymentSubject: String by lazy { clean(readRaw("PAYMENT_SUBJECT")) ?: "service" }    // service / commodity / ...
    val paymentMode: String by lazy { clean(readRaw("PAYMENT_MODE")) ?: "full_prepayment" }  // full_prepayment / ...
    val requirePhoneForReceipt: Boolean by lazy {
        if (!receiptsEnabled) false else readOptionalBoolean("REQUIRE_PHONE_FOR_RECEIPT") ?: true
    }
    val requireEmailForReceipt: Boolean by lazy {
        if (!receiptsEnabled) false else readOptionalBoolean("REQUIRE_EMAIL_FOR_RECEIPT") ?: true
    }

    // Paywall (используется в paywall-сообщениях)
    val PAYWALL_TEXT: String
        get() = "Лимит бесплатных сообщений исчерпан. " +
                "Оформи подписку и создавай рецепты без ограничений! 699 рублей в месяц. " +
                "Подписаться: /premium"

    // Fallback на случай проблем с LLM
    const val FALLBACK_REPLY = "Похоже, я устал… Давай продолжим чуть позже?"
}

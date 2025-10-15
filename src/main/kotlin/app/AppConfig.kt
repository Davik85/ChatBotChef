package app

import io.github.cdimascio.dotenv.dotenv

object AppConfig {
    private val env = dotenv { ignoreIfMissing = true }
    private fun readRaw(key: String): String? = System.getenv(key) ?: env[key]

    private fun clean(s: String?): String? {
        if (s == null) return null
        var v = s.trim().trim('"', '\'')
        v = v.replace("\r", "").replace("\n", "")
        v = v.replace(Regex("[\\u0000-\\u001F\\u007F\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]"), "")
        return if (v.isBlank()) null else v
    }
    private fun requireOrError(name: String, raw: String?, validator: (String) -> Boolean): String {
        val v = clean(raw)
        require(!v.isNullOrBlank()) { "$name is required" }
        require(validator(v)) { "$name has invalid format" }
        return v!!
    }

    // Валидаторы
    private val tgTokenRegex = Regex("""^\d+:[A-Za-z0-9_-]{30,}$""")
    private fun isValidTgToken(s: String) = tgTokenRegex.matches(s)
    private val openAiRegex = Regex("""^(sk-[A-Za-z0-9_-]{10,}|sk-proj-[A-Za-z0-9_-]{10,})$""")
    private fun isValidOpenAi(s: String) = openAiRegex.matches(s)

    // Базовый конфиг
    val telegramToken: String by lazy {
        requireOrError("TELEGRAM_TOKEN", readRaw("TELEGRAM_TOKEN"), ::isValidTgToken)
    }
    val openAiApiKey: String by lazy {
        requireOrError("OPENAI_API_KEY", readRaw("OPENAI_API_KEY"), ::isValidOpenAi)
    }
    val openAiOrg: String? by lazy { clean(readRaw("OPENAI_ORG")) }
    val openAiProject: String? by lazy { clean(readRaw("OPENAI_PROJECT")) }

    // ===== Telegram Payments (ЮKassa) =====
    // provider_token — выдаёт @BotFather после подключения ЮKassa
    val providerToken: String by lazy { clean(readRaw("PROVIDER_TOKEN")) ?: error("PROVIDER_TOKEN is required for Telegram Payments") }

    // Параметры подписки
    val premiumPriceRub: Int by lazy { clean(readRaw("PREMIUM_PRICE_RUB"))?.toIntOrNull() ?: 700 }
    val premiumDays: Int by lazy { clean(readRaw("PREMIUM_DAYS"))?.toIntOrNull() ?: 30 }

    // Чек 54-ФЗ (будет в provider_data.receipt)
    // Система налогообложения: 1–6 (ОСН=1, УСН доход=2, УСН дох-расх=3, ЕНВД=4*, ЕСХН=5, патент=6). Обычно УСН — 2 или 3.
    val taxSystemCode: Int by lazy { clean(readRaw("TAX_SYSTEM_CODE"))?.toIntOrNull() ?: 2 }
    // НДС (vat_code): 1 — 20%, 2 — 10%, 3 — 0%, 4 — 20/120, 5 — 10/110, 6 — без НДС
    val vatCode: Int by lazy { clean(readRaw("VAT_CODE"))?.toIntOrNull() ?: 6 }
    // Предмет/способ расчёта для услуги
    val paymentSubject: String by lazy { clean(readRaw("PAYMENT_SUBJECT")) ?: "service" }
    val paymentMode: String by lazy { clean(readRaw("PAYMENT_MODE")) ?: "full_prepayment" }
    // Что попросить у пользователя для чека (Телеграм подставит в customer.*)
    val requirePhoneForReceipt: Boolean by lazy { (clean(readRaw("REQUIRE_PHONE_FOR_RECEIPT")) ?: "true").equals("true", true) }
    val requireEmailForReceipt: Boolean by lazy { (clean(readRaw("REQUIRE_EMAIL_FOR_RECEIPT")) ?: "false").equals("true", true) }

    // Прочее
    const val TELEGRAM_BASE = "https://api.telegram.org"
    const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
    const val OPENAI_MAX_TOKENS = 1800

    const val MAX_REPLY_CHARS = 1800
    const val FREE_DAILY_MSG_LIMIT = 3

    val SUBSCRIBE_URL: String by lazy { clean(readRaw("SUBSCRIBE_URL")) ?: "https://example.com/pay" }

    val PAYWALL_TEXT: String get() =
        "Лимит бесплатных сообщений исчерпан. " +
                "Оформи подписку и создавай рецепты без ограничений! 700рублей в месяц. "

    const val FALLBACK_REPLY = "Похоже, я устал… Давай продолжим чуть позже?"
}

package app.pay

import app.AppConfig
import app.db.PaymentRepo
import app.db.PaymentRepo.PaymentRecord
import app.db.PaymentRepo.Status
import app.web.dto.TgPreCheckoutQuery
import app.web.dto.TgSuccessfulPayment

object PaymentService {
    private const val SUPPORTED_CURRENCY = "RUB"

    // Сообщения ДЛЯ ПОЛЬЗОВАТЕЛЯ (локализованы)
    private const val GENERIC_ERROR =
        "Платеж временно недоступен. Попробуйте ещё раз позже."
    private const val DUPLICATE_ERROR =
        "Этот счёт уже оплачен. Ожидайте подтверждения."
    private fun currencyError(expected: String) =
        "Платежи доступны только в валюте $expected."

    val paymentsAvailable: Boolean
        get() = AppConfig.paymentsEnabled

    // Тоже пользовательское сообщение (например, если кнопка оплаты недоступна)
    val paymentsDisabledMessage: String
        get() = "Платежи временно недоступны. Попробуйте позже или напишите нам, если нужен доступ."

    fun newInvoicePayload(chatId: Long): String =
        "premium_${chatId}_${System.currentTimeMillis()}"

    private fun expectedAmountMinor(): Int =
        AppConfig.premiumPriceRub.coerceAtLeast(0) * 100

    /** Регистрируем инвойс в локальной БД перед sendInvoice */
    fun registerInvoice(payload: String, chatId: Long) {
        val amount = expectedAmountMinor()
        PaymentRepo.upsertInvoice(payload, chatId, amount, SUPPORTED_CURRENCY)
    }

    /** Результат валидации для pre_checkout_query */
    data class ValidationResult(val ok: Boolean, val errorMessage: String? = null)

    /**
     * Проверяем pre_checkout_query: принадлежность инвойса, валюту и сумму.
     * Если что-то не так — возвращаем (ok=false, errorMessage=русский текст),
     * который Telegram покажет пользователю в алерте.
     */
    fun validatePreCheckout(query: TgPreCheckoutQuery): ValidationResult {
        if (!paymentsAvailable) {
            return ValidationResult(false, paymentsDisabledMessage)
        }

        val payload = query.invoice_payload.orEmpty()
        if (payload.isBlank()) {
            println("PAYMENT-WARN: empty payload in preCheckout for user ${query.from.id}")
            return ValidationResult(false, GENERIC_ERROR)
        }

        val record = PaymentRepo.findByPayload(payload)
        if (record == null) {
            println("PAYMENT-WARN: unknown payload $payload in preCheckout")
            return ValidationResult(false, GENERIC_ERROR)
        }

        if (record.userId != query.from.id) {
            PaymentRepo.markFailure(payload, "user_mismatch")
            println("PAYMENT-WARN: user mismatch for payload $payload: expected ${record.userId}, got ${query.from.id}")
            return ValidationResult(false, GENERIC_ERROR)
        }

        if (record.status == Status.PAID) {
            println("PAYMENT-INFO: duplicate payment attempt for $payload")
            return ValidationResult(false, DUPLICATE_ERROR)
        }

        val expectedCurrency = record.currency.ifBlank { SUPPORTED_CURRENCY }
        if (query.currency != expectedCurrency) {
            PaymentRepo.markFailure(payload, "currency_${query.currency}")
            println("PAYMENT-WARN: invalid currency ${query.currency} for payload $payload (expected $expectedCurrency)")
            return ValidationResult(false, currencyError(expectedCurrency))
        }

        val expectedAmount = record.amountMinor.takeIf { it > 0 } ?: expectedAmountMinor()
        if (query.total_amount != expectedAmount) {
            PaymentRepo.markFailure(payload, "amount_${query.total_amount}")
            println("PAYMENT-WARN: amount mismatch for $payload: expected $expectedAmount, got ${query.total_amount}")
            return ValidationResult(false, GENERIC_ERROR)
        }

        PaymentRepo.markPrecheck(payload)
        return ValidationResult(true, null)
    }

    /**
     * Обрабатываем успешную оплату (message.successful_payment).
     * Здесь сообщений пользователю нет — их отправляет верхний уровень после grant премиума.
     */
    fun handleSuccessfulPayment(chatId: Long, payment: TgSuccessfulPayment): Boolean {
        val payload = payment.invoice_payload.orEmpty()
        if (payload.isBlank()) {
            println("PAYMENT-WARN: successful payment without payload for chat $chatId")
            return false
        }

        val record = PaymentRepo.findByPayload(payload)
        if (record == null) {
            println("PAYMENT-WARN: successful payment for unknown payload $payload")
            return false
        }

        if (!validateAmounts(record, payment)) {
            PaymentRepo.markFailure(payload, "success_amount_mismatch")
            return false
        }

        val updated = PaymentRepo.markPaid(
            payload = payload,
            amountMinor = payment.total_amount,
            currency = payment.currency,
            telegramChargeId = payment.telegram_payment_charge_id,
            providerChargeId = payment.provider_payment_charge_id
        )

        if (!updated) {
            println("PAYMENT-WARN: failed to mark paid for payload $payload")
        }

        return updated
    }

    /** Логируем и отмечаем неуспех — пользовательские тексты не шлём отсюда. */
    fun markInvoiceFailure(payload: String?, reason: String) {
        if (payload.isNullOrBlank()) return
        PaymentRepo.markFailure(payload, reason)
        println("PAYMENT-WARN: $reason for payload $payload")
    }

    // --- внутренние проверки (только логи) ---
    private fun validateAmounts(record: PaymentRecord, payment: TgSuccessfulPayment): Boolean {
        if (payment.currency != record.currency) {
            println("PAYMENT-WARN: success currency mismatch for ${record.payload}: expected ${record.currency}, got ${payment.currency}")
            return false
        }
        if (payment.total_amount != record.amountMinor) {
            println("PAYMENT-WARN: success amount mismatch for ${record.payload}: expected ${record.amountMinor}, got ${payment.total_amount}")
            return false
        }
        return true
    }
}

package app.pay

import app.AppConfig
import java.util.Locale

object ReceiptBuilder {
    /**
     * Возвращает provider_data для Telegram Payments (ЮKassa).
     * Телеграм сам добавит в receipt.customer phone/email, если мы попросим их флагами в sendInvoice.
     */
    fun providerDataForSubscription(amountRub: Int, title: String): Map<String, Any> {
        val value = String.format(Locale.US, "%.2f", amountRub.toDouble())
        val item = mapOf(
            "description" to title.take(128),
            "quantity" to "1.00",
            "amount" to mapOf("value" to value, "currency" to "RUB"),
            "vat_code" to AppConfig.vatCode,
            "payment_mode" to AppConfig.paymentMode,
            "payment_subject" to AppConfig.paymentSubject
        )
        val receipt = mutableMapOf<String, Any>(
            "items" to listOf(item),
            "tax_system_code" to AppConfig.taxSystemCode
        )
        // customer.* не указываем — Телеграм подставит phone/email при need_* флагах
        return mapOf("receipt" to receipt)
    }
}

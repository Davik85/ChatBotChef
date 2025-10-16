package app.db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

object PaymentRepo {

    enum class Status(val code: String) {
        INVOICE("invoice"),
        PRECHECK("precheck"),
        PAID("paid"),
        FAILED("failed")
    }

    data class PaymentRecord(
        val payload: String,
        val userId: Long,
        val amountMinor: Int,
        val currency: String,
        val status: Status,
        val failureReason: String?,
        val telegramChargeId: String?,
        val providerChargeId: String?
    )

    private fun now() = System.currentTimeMillis()

    fun upsertInvoice(payload: String, userId: Long, amountMinor: Int, currency: String) = transaction {
        val ts = now()
        val inserted = Payments.insertIgnore {
            it[Payments.payload] = payload
            it[Payments.user_id] = userId
            it[Payments.amount_minor] = amountMinor
            it[Payments.currency] = currency
            it[Payments.status] = Status.INVOICE.code
            it[Payments.created_at] = ts
            it[Payments.updated_at] = ts
            it[Payments.failure_reason] = null
            it[Payments.telegram_charge_id] = null
            it[Payments.provider_charge_id] = null
        }
        if (inserted == 0) {
            Payments.update({ Payments.payload eq payload }) {
                it[Payments.user_id] = userId
                it[Payments.amount_minor] = amountMinor
                it[Payments.currency] = currency
                it[Payments.status] = Status.INVOICE.code
                it[Payments.updated_at] = ts
                it[Payments.failure_reason] = null
            }
        }
    }

    fun findByPayload(payload: String): PaymentRecord? = transaction {
        Payments
            .select { Payments.payload eq payload }
            .limit(1)
            .map {
                PaymentRecord(
                    payload = it[Payments.payload],
                    userId = it[Payments.user_id],
                    amountMinor = it[Payments.amount_minor],
                    currency = it[Payments.currency],
                    status = Status.values().firstOrNull { st -> st.code == it[Payments.status] } ?: Status.INVOICE,
                    failureReason = it[Payments.failure_reason],
                    telegramChargeId = it[Payments.telegram_charge_id],
                    providerChargeId = it[Payments.provider_charge_id]
                )
            }
            .firstOrNull()
    }

    fun markPrecheck(payload: String) = setStatus(payload, Status.PRECHECK)

    fun markPaid(
        payload: String,
        amountMinor: Int,
        currency: String,
        telegramChargeId: String?,
        providerChargeId: String?
    ): Boolean = transaction {
        val updated = Payments.update({ Payments.payload eq payload }) {
            it[Payments.status] = Status.PAID.code
            it[Payments.amount_minor] = amountMinor
            it[Payments.currency] = currency
            it[Payments.updated_at] = now()
            it[Payments.failure_reason] = null
            it[Payments.telegram_charge_id] = telegramChargeId
            it[Payments.provider_charge_id] = providerChargeId
        }
        updated > 0
    }

    fun markFailure(payload: String, reason: String) = transaction {
        val updated = Payments.update({ Payments.payload eq payload }) {
            it[Payments.status] = Status.FAILED.code
            it[Payments.failure_reason] = reason.take(500)
            it[Payments.updated_at] = now()
        }
        updated
    }

    private fun setStatus(payload: String, status: Status) = transaction {
        Payments.update({ Payments.payload eq payload }) {
            it[Payments.status] = status.code
            it[Payments.updated_at] = now()
            if (status != Status.FAILED) it[Payments.failure_reason] = null
        }
    }
}

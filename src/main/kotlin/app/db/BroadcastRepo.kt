package app.db

import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.and

object BroadcastRepo {

    enum class RecipientStatus(val code: String) {
        PENDING("pending"),
        SENT("sent"),
        FAILED("failed"),
        SKIPPED_BLOCKED("skipped_blocked")
    }

    fun createBroadcast(
        createdBy: Long,
        text: String?,
        mediaType: String?,
        mediaFileId: String?
    ): Long = transaction {
        val normalizedText = text?.take(4000)
        val normalizedMediaType = mediaType?.takeIf { it.isNotBlank() }?.lowercase()
        val normalizedMediaId = mediaFileId?.take(512)
        val id = Broadcasts.insertAndGetId { row ->
            row[Broadcasts.createdAt] = System.currentTimeMillis()
            row[Broadcasts.createdBy] = createdBy
            row[Broadcasts.text] = normalizedText
            row[Broadcasts.mediaType] = normalizedMediaType
            row[Broadcasts.mediaFileId] = normalizedMediaId
        }
        id.value
    }

    fun registerRecipients(broadcastId: Long, userIds: List<Long>) {
        if (userIds.isEmpty()) return
        val now = System.currentTimeMillis()
        transaction {
            BroadcastRecipients.batchInsert(userIds) { userId ->
                this[BroadcastRecipients.broadcastId] = EntityID(broadcastId, Broadcasts)
                this[BroadcastRecipients.userId] = userId
                this[BroadcastRecipients.status] = RecipientStatus.PENDING.code
                this[BroadcastRecipients.errorCode] = null
                this[BroadcastRecipients.errorMessage] = null
                this[BroadcastRecipients.updatedAt] = now
            }
        }
    }

    fun markRecipientStatus(
        broadcastId: Long,
        userId: Long,
        status: RecipientStatus,
        errorCode: Int?,
        errorMessage: String?
    ) {
        val sanitizedMessage = errorMessage
            ?.replace("\n", " ")
            ?.replace("\r", " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(200)
        val now = System.currentTimeMillis()
        transaction {
            val updated = BroadcastRecipients.update({
                (BroadcastRecipients.broadcastId eq EntityID(broadcastId, Broadcasts)) and
                    (BroadcastRecipients.userId eq userId)
            }) { row ->
                row[BroadcastRecipients.status] = status.code
                row[BroadcastRecipients.errorCode] = errorCode
                row[BroadcastRecipients.errorMessage] = sanitizedMessage
                row[BroadcastRecipients.updatedAt] = now
            }
            if (updated == 0) {
                BroadcastRecipients.insertAndGetId { row ->
                    row[BroadcastRecipients.broadcastId] = EntityID(broadcastId, Broadcasts)
                    row[BroadcastRecipients.userId] = userId
                    row[BroadcastRecipients.status] = status.code
                    row[BroadcastRecipients.errorCode] = errorCode
                    row[BroadcastRecipients.errorMessage] = sanitizedMessage
                    row[BroadcastRecipients.updatedAt] = now
                }
            }
        }
    }
}

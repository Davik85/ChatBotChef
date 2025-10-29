package app.db

import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction

object AdminAuditRepo {
    fun record(adminId: Long, action: String, target: String? = null, meta: String? = null) {
        if (adminId <= 0L) return
        val trimmedAction = action.trim().take(64)
        if (trimmedAction.isEmpty()) return
        val safeTarget = target?.trim()?.take(120)
        val safeMeta = meta?.trim()?.take(240)
        runCatching {
            transaction {
                AdminAudit.insert {
                    it[AdminAudit.admin_id] = adminId
                    it[AdminAudit.action] = trimmedAction
                    it[AdminAudit.target] = safeTarget
                    it[AdminAudit.meta] = safeMeta
                    it[AdminAudit.ts] = System.currentTimeMillis()
                }
            }
        }.onFailure {
            println("ADMIN-AUDIT-ERR: action=$trimmedAction admin=$adminId reason=${it.message}")
        }
    }
}

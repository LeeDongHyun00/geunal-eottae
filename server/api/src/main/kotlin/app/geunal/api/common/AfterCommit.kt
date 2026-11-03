package app.geunal.api.common

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Runs [block] once the surrounding transaction commits, or at once outside a transaction. For what must not happen
 * for a write that rolls back: realtime signals, calls to Kakao and Apple.
 */
fun afterCommit(block: () -> Unit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) return block()
    TransactionSynchronizationManager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit() = block()
        },
    )
}

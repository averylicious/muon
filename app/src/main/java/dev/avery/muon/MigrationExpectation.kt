package dev.avery.muon

import java.io.IOException

/** One bounded consent receipt, never a queue, native handle or permission to delete/adopt bytes.
 * Production starts expect no prior attempt; recovery must match the exact record shown to the
 * user. Unchecked is retained only for lower-level synchronous callers which own their own policy.
 * Journal.begin rechecks inside its transaction, after the publication's pre-allocation check.
 */
internal sealed interface MigrationExpectation {
    data object Unchecked:MigrationExpectation
    data object Fresh:MigrationExpectation
    data class Retry(val record:MigrationRecord):MigrationExpectation

    fun check(key:String,current:MigrationRecord?) {
        when(this) {
            Unchecked -> Unit
            Fresh -> if(current!=null) throw IOException("A conversion attempt already exists; review it before retrying")
            is Retry -> if(record.ticket.allocation.key!=key || record.phase==MigrationPhase.Ready || current!=record)
                throw IOException("The interrupted conversion changed; refresh before retrying")
        }
    }
}

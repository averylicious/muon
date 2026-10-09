package dev.avery.muon

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/** One explicitly requested resource, one worker slot and one scalar status; no batch/history,
 * restart replay, source cleanup or background retry. Executor is owned by the root startup frame.
 * Cancellation/deadline are cooperative; blocked I/O must still close before this slot can drain.
 */
internal class PartitionMigrationWork(private val executor:Executor,
    private val migrate:(CacheMigrationControl,String)->MigrationRecord,
    private val available:()->Boolean,private val nanoTime:()->Long=System::nanoTime) {
    private class Attempt(val key:String,val control:CacheMigrationControl) { var uncertainSubmission=false }
    private var active:Attempt?=null
    private var terminal=MigrationWorkProgress(MigrationWorkPhase.Waiting)
    private var terminalKey:String?=null
    @get:Synchronized val progress:MigrationWorkProgress get()=
        active?.let { if(it.uncertainSubmission) MigrationWorkProgress(MigrationWorkPhase.Uncertain,it.control.progress.copyBytes) else it.control.progress } ?: terminal
    @get:Synchronized val key:String? get()=active?.key ?: terminalKey
    @get:Synchronized val busy:Boolean get()=active!=null
    /** Caller owes the user's explicit opt-in. False refuses before scheduling/claiming/mutating;
     * success is submission only, never a Ready receipt. The actual storage owner rechecks authority.
     */
    @Synchronized fun start(key:String,timeoutMillis:Long,explicitConsent:Boolean):Boolean {
        if(!explicitConsent || active!=null || key.length.toLong()*2>MIGRATION_KEY_BYTES || !available()) return false
        val control=CacheMigrationControl(timeoutMillis,acceptedNanos=nanoTime(),nanoTime=nanoTime)
        val attempt=Attempt(key,control); active=attempt
        try { executor.execute { run(attempt) } }
        catch(_:RejectedExecutionException) {
            if(active===attempt) { active=null; terminalKey=key; terminal=MigrationWorkProgress(MigrationWorkPhase.Failed) }
            return false // Owned standard executor refused before scheduling.
        } catch(_:Throwable) {
            // An arbitrary executor can throw after submission. Keep the slot, cancel cooperatively
            // and do not reinterpret an unknown acceptance as permission to start a second writer.
            if(active===attempt) { attempt.uncertainSubmission=true; control.cancel() }
            return false
        }
        return true
    }
    @Synchronized fun cancel():Boolean {
        val attempt=active ?: return false
        attempt.control.cancel(); return true
    }
    private fun run(attempt:Attempt) {
        var result=MigrationWorkProgress(MigrationWorkPhase.Failed)
        try {
            migrate(attempt.control,attempt.key)
            // Only the actual owner's durable publication establishes Ready.
            result=attempt.control.progress
        } catch(failure:Throwable) {
            val observed=attempt.control.progress
            result=when {
                failure is MigrationIoUncertain || failure is PartitionOwnershipUncertain ->
                    MigrationWorkProgress(MigrationWorkPhase.Uncertain,observed.copyBytes)
                observed.phase in setOf(MigrationWorkPhase.Cancelled,MigrationWorkPhase.Expired,MigrationWorkPhase.Failed,MigrationWorkPhase.Uncertain) -> observed
                else -> MigrationWorkProgress(MigrationWorkPhase.Failed,observed.copyBytes)
            }
        } finally { synchronized(this) {
            if(active===attempt) {
                terminalKey=attempt.key; terminal=result
                if(result.phase==MigrationWorkPhase.Uncertain || attempt.uncertainSubmission)
                    attempt.uncertainSubmission=true // Root/session owns unknown native/file handles; no retry here.
                else active=null
            }
        } }
    }
}

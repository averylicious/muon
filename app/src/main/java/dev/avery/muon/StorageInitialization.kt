package dev.avery.muon

import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

internal enum class StorageAvailability { NotStarted, Preparing, Binding, Ready, Failed }

/** One process attempt: prepare native/filesystem owners off-main, bind managers on main.
 * Waiting callers own only their wait, not the startup job; cancelling a screen/service cannot
 * cancel preparation and permit a second owner. Failed/partially returned owners stay retained in
 * the supplied StorageStartup instances until process teardown. No reset or fallback API.
 */
internal class StorageInitialization<P:Any,T:Any>(private val scope:CoroutineScope,
    private val main:CoroutineDispatcher,private val io:CoroutineDispatcher,
    private val prepare:()->P,private val bind:(P)->T) {
    private val mutable=MutableStateFlow(StorageAvailability.NotStarted)
    val availability:StateFlow<StorageAvailability> = mutable
    // Main dispatcher only, installed before execution starts (including immediate dispatch).
    private var attempt:Deferred<T>?=null
    suspend fun await():T=withContext(main) {
        val existing=attempt
        val work=existing ?: scope.async(main,start=CoroutineStart.LAZY) {
            try {
                mutable.value=StorageAvailability.Preparing
                val prepared=withContext(io) { prepare() }
                mutable.value=StorageAvailability.Binding
                bind(prepared).also { mutable.value=StorageAvailability.Ready }
            } catch(failure:Throwable) {
                mutable.value=StorageAvailability.Failed
                throw failure
            }
        }.also { attempt=it; it.start() }
        work.await()
    }
    fun unavailable():IOException=IOException(when(availability.value) {
        StorageAvailability.Failed -> "Saved storage couldn't open. Existing copies were kept; fully restart Muon before retrying."
        else -> "Saved storage is still opening. Wait until it is ready before trying again."
    })
}

package dev.avery.muon

import java.io.IOException

/** One process owner, selected BEFORE any shelf's native cache is opened. A failed constructor
 * must not be retried with new caches/workers over possibly live old participants. Retained handles
 * are not cleanup authority: their async work/actual close must be proved before a future shutdown
 * can release them. No reset, waiting queue, fallback backend or live service-manager replacement.
 */
internal class StorageStartup<T : Any>(private val capacity: Int = 16) {
    init { require(capacity in 1..16) }
    private enum class Phase { New, Opening, Open, Uncertain }
    private var phase = Phase.New
    private var opened: T? = null
    private val handles = arrayOfNulls<Any>(capacity)
    private var count = 0
    @get:Synchronized internal val retained: Int get() = count

    /** Register each real returned resource before opening its dependent participant. If allocation
     * throws, its partial internal state is unknown; the process owner refuses every later retry.
     * The factory must not independently destroy an owner whose worker/close is still uncertain.
     */
    inner class Opening internal constructor() {
        fun <R : Any> own(factory: () -> R): R = synchronized(this@StorageStartup) {
            check(phase == Phase.Opening) { "Storage startup is no longer opening" }
            if (count == handles.size) throw IOException("Storage startup ownership budget full")
            val slot = count++
            handles[slot] = factory // Keep the attempted construction closure if it fails partway.
            factory().also { handles[slot] = it }
        }
    }

    @Synchronized fun open(factory: (Opening) -> T): T {
        when (phase) {
            Phase.Open -> return requireNotNull(opened)
            Phase.Opening -> throw IOException("Storage startup is already active")
            Phase.Uncertain -> throw IOException("Saved storage startup failed; fully restart Muon before retrying")
            Phase.New -> Unit
        }
        phase = Phase.Opening
        return try {
            factory(Opening()).also { opened = it; phase = Phase.Open }
        } catch (failure: Throwable) {
            phase = Phase.Uncertain
            throw failure
        }
    }
}

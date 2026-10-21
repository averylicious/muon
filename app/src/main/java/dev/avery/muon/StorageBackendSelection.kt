package dev.avery.muon

import android.content.SharedPreferences
import java.io.IOException

/** Process startup reads this choice before allocating providers, caches or service managers.
 * No legacy fallback, live swap, automatic opt-in or preference repair. Explicit opt-in must
 * commit this exact choice before a full process restart, never toggle
 * an existing owner. StorageStartup retains the outcome even if preferences subsequently change.
 */
/** Explicit opt-in offered by Canary (debug) and Stable (release); benchmark stays disabled.
 * Preferences cannot override the compiled channel gate. Normal startup is still Legacy until an
 * exact persisted opt-in choice exists. */
internal const val PARTITION_OPT_IN_AVAILABLE=BuildConfig.PARTITION_OPT_IN_AVAILABLE

internal enum class StorageBackendSelection {
    Legacy, PartitionV1;

    fun <T> open(legacy:()->T, partition:(()->T)?=null):T = when(this) {
        Legacy -> legacy()
        PartitionV1 -> (partition ?: throw IOException(
            "Partition storage was selected, but production integration is not available; legacy storage was not opened"))()
    }

    companion object {
        internal const val KEY="storageBackendV1"
        fun read(prefs:SharedPreferences):StorageBackendSelection {
            val value=try { prefs.getString(KEY,null) } catch(_:ClassCastException) {
                throw IOException("Saved storage backend choice has an unsupported type")
            }
            return when(value) {
                null,"legacy" -> Legacy
                "partition-v1" -> PartitionV1
                else -> throw IOException("Saved storage backend choice is unsupported; no backend was opened")
            }
        }
    }
}

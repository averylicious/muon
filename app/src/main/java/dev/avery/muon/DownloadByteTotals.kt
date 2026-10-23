package dev.avery.muon

import android.content.Context
import java.io.Closeable
import java.io.File

/** Exact incremental accounting; production keeps per-ID sizes on derived private disk. */
internal class DownloadByteTotals private constructor(private val disk: DownloadByteLedger?) : Closeable {
    constructor() : this(null) // Existing pure fixture seam.
    constructor(context: Context) : this(DownloadByteLedger(File(context.noBackupFilesDir,"download-byte-totals-v1.db")))
    private val sizes = if (disk == null) HashMap<String,Long>() else null
    private var memoryTotal = 0L
    val known: Boolean get() = disk?.known ?: true
    val total: Long get() = disk?.total ?: memoryTotal
    fun put(id: String, bytes: Long) {
        if (disk != null) disk.put(id,bytes)
        else memoryTotal += bytes - (requireNotNull(sizes).put(id,bytes) ?: 0L)
    }
    fun remove(id: String) {
        if (disk != null) disk.remove(id)
        else requireNotNull(sizes).remove(id)?.let { memoryTotal -= it }
    }
    override fun close() { disk?.close() }
}

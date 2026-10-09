package dev.avery.muon

import java.io.IOException

/** Exact possibly-live native owner. Failure before a factory returns is NOT evidence that no
 * resource exists. Keep this bounded owner and the caller's storage permit until process teardown;
 * do not retry cleanup, adopt a replacement or make an exclusive operation appear safe. */
internal class PartitionOwnershipUncertain(val owner:Any,cause:Throwable):
    IOException(cause.message ?: "Partition native ownership remains uncertain",cause)

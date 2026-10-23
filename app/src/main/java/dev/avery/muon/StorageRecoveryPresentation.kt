package dev.avery.muon

/** Fixed UI slots only; no journal entries, paths or controller ownership are retained here. */
internal enum class StorageRecoverySection { PhoneMoves,PhoneDeletes,CardMoves,CardDeletes,PhoneOptimization,CardOptimization }
internal enum class StorageRecoveryPresentation { Empty,Checking,Review,Working,Unavailable,Uncertain }

/** Absence is established only by a successful, exhausted first page. A filtered empty page can
 * still lead to pending work, and a later empty page must retain the way back to earlier entries.
 * Working means this section's own active attempt; unrelated storage work belongs to the banner. */
internal fun storageRecoveryPresentation(pageLoaded:Boolean,firstPage:Boolean,hasEntries:Boolean,hasMore:Boolean,
    working:Boolean=false,progress:Boolean=false,unavailable:Boolean=false,selected:Boolean=false,
    admissionFailed:Boolean=false,uncertain:Boolean=false):StorageRecoveryPresentation = when {
    uncertain -> StorageRecoveryPresentation.Uncertain
    unavailable || admissionFailed -> StorageRecoveryPresentation.Unavailable
    working -> StorageRecoveryPresentation.Working
    progress || selected || hasEntries || hasMore || !firstPage -> StorageRecoveryPresentation.Review
    !pageLoaded -> StorageRecoveryPresentation.Checking
    else -> StorageRecoveryPresentation.Empty
}

/** Global work keeps the banner reachable without turning every section into a working panel. */
internal fun storageRecoveryAttention(states:Collection<StorageRecoveryPresentation>,working:Boolean=false):StorageRecoveryPresentation =
    listOf(StorageRecoveryPresentation.Uncertain,StorageRecoveryPresentation.Unavailable,
        StorageRecoveryPresentation.Working,StorageRecoveryPresentation.Review,StorageRecoveryPresentation.Checking)
        .firstOrNull {it in states || working && it==StorageRecoveryPresentation.Working} ?: StorageRecoveryPresentation.Empty

internal fun storageRecoveryDetailsVisible(presentation:StorageRecoveryPresentation):Boolean =
    presentation!=StorageRecoveryPresentation.Empty && presentation!=StorageRecoveryPresentation.Checking

/** A successful terminal result does not need recovery. Nothing here acknowledges or clears the
 * controller's stored result, and Ready is still progress while its exact attempt remains active. */
internal fun storageMigrationNeedsReview(phase:MigrationWorkPhase?,active:Boolean):Boolean =
    active || phase!=null && phase!=MigrationWorkPhase.Ready

package dev.avery.muon

/** One current control and one fixed terminal result per Store, never a request/progress history.
 * Cancellation is cooperative; the actual worker retains exclusion until it acknowledges exit. */
internal class PartitionMigrationAttempt(val key:String,val sourceCard:Boolean,val control:CacheMigrationControl)
internal data class PartitionMigrationStatus(val key:String,val sourceCard:Boolean,val progress:MigrationWorkProgress)

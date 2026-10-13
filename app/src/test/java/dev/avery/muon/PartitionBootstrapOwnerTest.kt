package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionBootstrapOwnerTest {
    @get:Rule val folders=TemporaryFolder()
    private fun <T> worker(action:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {return try {executor.submit<T> {action()}.get(10,TimeUnit.SECONDS)}
            catch(failure:java.util.concurrent.ExecutionException) {throw requireNotNull(failure.cause)}}
        finally {executor.shutdownNow();assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
    private fun target()=File(folders.newFolder(),"partition")
    private fun validate(folder:File)=worker {
        val catalog=CachePartitionCatalog(folder,create=false);val migration=CacheMigrationJournal(folder,create=false)
        val save=PartitionSaveJournal(folder,create=false);val retirement=LegacyRetirementJournal(folder,false)
        val move=PartitionSavedMoveJournal(folder,false);val delete=PartitionSavedDeleteJournal(folder,false)
        try {retirement.open();move.open();delete.open()
            assertTrue(migration.page().isEmpty());assertTrue(save.page().isEmpty());assertTrue(retirement.page().isEmpty())
            assertTrue(move.page().isEmpty());assertTrue(delete.page().isEmpty())
        } finally {for(owner in listOf<Closeable>(delete,move,retirement,save,migration,catalog)) owner.close()}
    }
    @Test fun actualClosedSixJournalSetIsPublishedWithoutPartialFinalRootOrNativeResource() {
        val target=target();val barrier=SavedStorageBarrier();var calls=0
        val owner=PartitionBootstrapOwner(target,barrier,{true},move={from,to ->
            calls++;assertFalse(to.exists());assertEquals(PARTITION_BOOTSTRAP_JOURNALS.toSet(),from.list()!!.toSet())
            Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);Unit
        })
        assertEquals(target,worker {owner.publish()});assertEquals(1,calls);assertTrue(barrier.quiescent)
        assertEquals(PARTITION_BOOTSTRAP_JOURNALS.toSet(),target.list()!!.toSet());validate(target)
        assertThrows(IOException::class.java) {worker {owner.publish()}};assertEquals(1,calls)
    }
    @Test fun priorInterruptedTemporaryFoldersArePreservedAndNeverAdoptedOrRepaired() {
        val target=target();val partial=File(target.parentFile,".partition.setup-abandoned").apply {assertTrue(mkdir())}
        val original=File(partial,"migration-journal-v1.db").apply {writeBytes(byteArrayOf(9,7,5))}
        val owner=PartitionBootstrapOwner(target,SavedStorageBarrier(),{true})
        worker {owner.publish()};validate(target);assertArrayEquals(byteArrayOf(9,7,5),original.readBytes())
        assertEquals(listOf("migration-journal-v1.db"),partial.list()!!.toList())
    }
    @Test fun incompleteAttemptBudgetRefusesWithoutDiscardingHistoryOrCreatingAFinalRoot() {
        val target=target()
        repeat(4) {File(target.parentFile,".partition.setup-$it").apply {assertTrue(mkdir())}}
        val owner=PartitionBootstrapOwner(target,SavedStorageBarrier(),{true})
        assertThrows(PartitionOwnershipUncertain::class.java) {worker {owner.publish()}}
        assertFalse(target.exists());assertEquals(4,target.parentFile!!.list()!!.size)
    }
    @Test fun unknownRenameAfterActualPublicationRetainsOwnerButNextProcessCanValidateCompleteRoot() {
        val target=target();val barrier=SavedStorageBarrier();var moves=0
        val owner=PartitionBootstrapOwner(target,barrier,{true},move={from,to ->
            moves++;Files.move(from.toPath(),to.toPath(),StandardCopyOption.ATOMIC_MOVE);throw IOException("Unknown rename return")
        })
        assertThrows(PartitionOwnershipUncertain::class.java) {worker {owner.publish()}}
        assertFalse(barrier.allowsShared);assertFalse(barrier.quiescent);assertTrue(target.isDirectory);validate(target)
        assertThrows(IOException::class.java) {worker {owner.publish()}};assertEquals(1,moves)
    }
    @Test fun unknownActualJournalDatabaseCloseRetainsThatConnectionAndNeverPublishes() {
        val target=target();val barrier=SavedStorageBarrier();var raw:SQLiteDatabase?=null;var closes=0
        var owner:PartitionBootstrapOwner?=null;var injected=false
        val made=PartitionBootstrapOwner(target,barrier,{
            if(!injected && owner!=null) {
                val state=PartitionBootstrapOwner::class.java.getDeclaredField("startup").apply {isAccessible=true}.get(owner) as StorageStartup<*>
                if(state.retained==6) {
                    val handles=StorageStartup::class.java.getDeclaredField("handles").apply {isAccessible=true}.get(state) as Array<*>
                    val catalog=handles[0] as CachePartitionCatalog
                    CachePartitionCatalog::class.java.getDeclaredField("closeDatabase").apply {isAccessible=true}.set(catalog,
                        {database:SQLiteDatabase ->raw=database;closes++;throw IOException("Unknown actual bootstrap SQL close")})
                    injected=true
                }
            };true
        });owner=made
        try {
            assertThrows(PartitionOwnershipUncertain::class.java) {worker {made.publish()}}
            assertTrue(injected);assertEquals(1,closes);assertTrue(requireNotNull(raw).isOpen);assertFalse(target.exists())
            assertFalse(barrier.allowsShared);assertFalse(barrier.quiescent)
            assertThrows(IOException::class.java) {worker {made.publish()}};assertEquals(1,closes)
        } finally {raw?.close()} // Known injected close left actual connection open; fixture only.
    }
    @Test fun unknownActualDirectoryCloseRetainsItsHandleAndStopsBeforeSqlAllocation() {
        val target=target();val barrier=SavedStorageBarrier();var raw:Closeable?=null;var closes=0
        val io=MigrationIoOwnership(closeHandle={raw=it;closes++;throw IOException("Unknown actual setup directory close")})
        val owner=PartitionBootstrapOwner(target,barrier,{true},io)
        try {
            assertThrows(PartitionOwnershipUncertain::class.java) {worker {owner.publish()}}
            assertEquals(1,closes);assertFalse(io.quiescent);assertFalse(target.exists());assertFalse(barrier.allowsShared)
            assertThrows(IOException::class.java) {worker {owner.publish()}};assertEquals(1,closes)
        } finally {raw?.close()}
    }
}

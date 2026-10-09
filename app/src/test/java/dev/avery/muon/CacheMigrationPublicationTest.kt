@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class CacheMigrationPublicationTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=ArrayList<SimpleCache>()
    @After fun close() { try { caches.asReversed().forEach { it.release() } } finally { database.close() } }
    private fun cache(file:File=folders.newFolder())=SimpleCache(file,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
    private fun seed(cache:Cache,key:String,position:Long,bytes:ByteArray) {
        val hole=requireNotNull(cache.startReadWriteNonBlocking(key,position,bytes.size.toLong()))
        try { val file=cache.startFile(key,position,bytes.size.toLong()); file.writeBytes(bytes); cache.commitFile(file,bytes.size.toLong()) }
        finally { cache.releaseHoleSpan(hole) }
    }
    private fun originals(cache:Cache,key:String)=cache.getCachedSpans(key).associate { requireNotNull(it.file) to requireNotNull(it.file).readBytes() }
    private fun preserved(originals:Map<File,ByteArray>)=originals.forEach { (file,bytes) -> assertArrayEquals(bytes,file.readBytes()) }

    @Test fun nativeCloseReopenFullComparisonPublishesOneReadyRouteAndPreservesAllSourceBytes() {
        val source=cache(); val key="saved/partial"; seed(source,key,0,byteArrayOf(1,2,3)); seed(source,key,90,byteArrayOf(4,5))
        source.applyContentMetadataMutations(key,ContentMetadataMutations().set("future",byteArrayOf(0,7)).set("exo_len",2L))
        val original=originals(source,key); val root=folders.newFolder(); var opened=0
        val ready:MigrationRecord
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            val runner=CacheMigrationPublication(catalog,journal,{ opened++; cache(it) })
            ready=runner.migrate(source,key,{})
            assertEquals(2,opened); assertEquals(MigrationPhase.Ready,ready.phase)
            assertEquals(MigrationCopyEvidence(5,2),ready.evidence); assertEquals(ready,journal.ready(key))
            val target=cache(catalog.directory(ready.ticket.allocation))
            assertEquals(ready.targetUid,target.uid)
            assertEquals(ready.evidence,CacheMigrationPreparation.verify(source,target,key,{}))
            assertThrows(IOException::class.java) { runner.migrate(source,key,{}) }
            assertEquals(2,opened)
        } }
        CacheMigrationJournal(root).use { assertEquals(ready,it.ready(key)) }
        preserved(original)
    }
    @Test fun cancellationAfterVerificationRetainsBothCopiesAndRetryUsesANewDirectory() {
        val source=cache(); val key="saved"; seed(source,key,0,ByteArray(100_000) { 19 }); val original=originals(source,key)
        val root=folders.newFolder()
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            val runner=CacheMigrationPublication(catalog,journal,::cache)
            assertThrows(IOException::class.java) { runner.migrate(source,key,{
                if(journal.find(key)?.phase==MigrationPhase.Verified) throw IOException("Cancelled before publication")
            }) }
            val uncertain=requireNotNull(journal.find(key)); assertEquals(MigrationPhase.Uncertain,uncertain.phase); assertNull(journal.ready(key))
            val kept=cache(catalog.directory(uncertain.ticket.allocation)); val partial=originals(kept,key); kept.release()
            val ready=runner.migrate(source,key,{})
            assertNotEquals(uncertain.ticket.allocation.directory,ready.ticket.allocation.directory)
            preserved(partial); preserved(original)
        } }
    }
    @Test fun bytesChangedAfterFirstCleanCloseAreDetectedBeforePublication() {
        val source=cache(); val key="saved"; seed(source,key,0,byteArrayOf(1,2,3,4)); val original=originals(source,key)
        val root=folders.newFolder(); var calls=0
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            val runner=CacheMigrationPublication(catalog,journal,{ file ->
                val target=cache(file); calls++
                if(calls==2) requireNotNull(target.getCachedSpans(key).first().file).writeBytes(byteArrayOf(9,2,3,4))
                target
            })
            assertThrows(IOException::class.java) { runner.migrate(source,key,{}) }
            assertEquals(2,calls); assertNull(journal.ready(key)); assertEquals(MigrationPhase.Uncertain,journal.find(key)?.phase)
            preserved(original)
        } }
    }
    @Test fun unknownCloseStopsNewAdmissionWithoutReopeningOrClosingTheOriginal() {
        val source=cache(); val key="saved"; seed(source,key,0,byteArrayOf(4,5)); val original=originals(source,key)
        val root=folders.newFolder(); var calls=0
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            val runner=CacheMigrationPublication(catalog,journal,{ file ->
                calls++; val native=cache(file)
                object:Cache by native { override fun release() { throw IOException("Unknown native close") } }
            })
            assertThrows(IOException::class.java) { runner.migrate(source,key,{}) }
            assertThrows(IOException::class.java) { runner.migrate(source,"other",{}) }
            assertEquals(1,calls); assertNull(journal.ready(key)); preserved(original)
        } }
        val aliasRoot=folders.newFolder()
        CachePartitionCatalog(aliasRoot).use { catalog -> CacheMigrationJournal(aliasRoot).use { journal ->
            val runner=CacheMigrationPublication(catalog,journal,{ source })
            assertThrows(IOException::class.java) { runner.migrate(source,key,{}) }
            assertTrue(source.isCached(key,0,2)); preserved(original)
        } }
    }
    @Test fun interruptedJournalAfterVerifiedReceiptCannotPromoteOnRestartAndFreshRetryPreservesOldTarget() {
        val source=cache(); val key="saved"; seed(source,key,0,byteArrayOf(7,8)); val original=originals(source,key)
        val root=folders.newFolder(); lateinit var pending:MigrationTicket; var stopped=false
        CachePartitionCatalog(root).use { catalog ->
            val journal=CacheMigrationJournal(root)
            val runner=CacheMigrationPublication(catalog,journal,::cache)
            assertThrows(Exception::class.java) { runner.migrate(source,key,{
                val row=journal.find(key)
                if(!stopped && row?.phase==MigrationPhase.Verified) { stopped=true; pending=row.ticket; journal.close() }
            }) }
            CacheMigrationJournal(root).use { recovered ->
                assertEquals(MigrationPhase.Verified,recovered.find(key)?.phase); assertNull(recovered.ready(key))
                assertThrows(IOException::class.java) { recovered.publish(pending) }
                val old=cache(catalog.directory(pending.allocation)); val oldBytes=originals(old,key); old.release()
                val ready=CacheMigrationPublication(catalog,recovered,::cache).migrate(source,key,{})
                assertNotEquals(pending.allocation.directory,ready.ticket.allocation.directory)
                preserved(oldBytes); preserved(original)
            }
        }
    }
    @Test fun metadataOnlyNativeCleanupCannotTurnAnIncompleteReplacementIntoAReadyRoute() {
        val source=cache(); val key="metadata-only"
        source.applyContentMetadataMutations(key,ContentMetadataMutations().set("future",byteArrayOf(4,7,9)))
        val root=folders.newFolder()
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            val runner=CacheMigrationPublication(catalog,journal,::cache)
            assertThrows(IOException::class.java) { runner.migrate(source,key,{}) }
            assertNull(journal.ready(key)); assertEquals(MigrationPhase.Uncertain,journal.find(key)?.phase)
            assertArrayEquals(byteArrayOf(4,7,9),source.getContentMetadata(key).get("future",null as ByteArray?))
            assertTrue(source.getCachedSpans(key).isEmpty())
        } }
    }

    @Test fun lateSourceMetadataChangeOrAppearingForeignFolderDoesNotPublishOrAdopt() {
        val source=cache(); val key="saved"; seed(source,key,0,byteArrayOf(1,2)); val original=originals(source,key)
        val root=folders.newFolder(); var calls=0
        CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            val runner=CacheMigrationPublication(catalog,journal,{ file ->
                calls++
                if(calls==2) source.applyContentMetadataMutations(key,ContentMetadataMutations().set("changed",1L))
                cache(file)
            })
            assertThrows(IOException::class.java) { runner.migrate(source,key,{}) }; assertNull(journal.ready(key)); preserved(original)
        } }
        val other=folders.newFolder(); var once=false; var opens=0; lateinit var foreign:File
        CachePartitionCatalog(other).use { catalog -> CacheMigrationJournal(other).use { journal ->
            val runner=CacheMigrationPublication(catalog,journal,{ opens++; cache(it) })
            assertThrows(IOException::class.java) { runner.migrate(source,key,{
                val row=journal.find(key)
                if(!once && row!=null) {
                    once=true; foreign=File(catalog.directory(row.ticket.allocation),"foreign").apply { parentFile.mkdirs(); writeBytes(byteArrayOf(99)) }
                }
            }) }
            assertEquals(0,opens); assertNull(journal.ready(key)); assertArrayEquals(byteArrayOf(99),foreign.readBytes()); preserved(original)
        } }
    }
}

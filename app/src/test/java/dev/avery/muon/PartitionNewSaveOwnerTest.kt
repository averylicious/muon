@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.SimpleCache
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionNewSaveOwnerTest {
    @get:Rule val folders=TemporaryFolder()
    private val fixtures=ArrayList<Fixture>(); private val caches=ArrayList<Cache>()
    private val key="saved/new"
    private inner class Fixture(val budget:PartitionNativeBudget=PartitionNativeBudget()) {
        val root=folders.newFolder(); val catalog=CachePartitionCatalog(root); val migration=CacheMigrationJournal(root)
        var saves=PartitionSaveJournal(root,create=true); var volume:String?="card-one"
        fun owner()=PartitionNativeOwner(catalog,migration,"card-one",{volume},budget=budget,saves=saves)
        fun ticket(name:String=key)=saves.begin(catalog.reserve(name))
    }
    private fun fixture(budget:PartitionNativeBudget=PartitionNativeBudget())=Fixture(budget).also { fixtures+=it }
    private fun retained(cache:Cache)=cache.also { caches+=it }
    private fun write(cache:Cache) {
        val hole=requireNotNull(cache.startReadWriteNonBlocking(key,0,3))
        try { val file=cache.startFile(key,0,3); file.writeBytes(byteArrayOf(1,4,9)); cache.commitFile(file,3) }
        finally { cache.releaseHoleSpan(hole) }
        cache.applyContentMetadataMutations(key,ContentMetadataMutations().set("future",byteArrayOf(8,0,7)))
    }
    private fun audio(f:Fixture,ticket:PartitionSaveTicket)=File(f.catalog.directory(ticket.allocation),"bytes").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".exo") }.associateWith { it.readBytes() }
    private fun kept(files:Map<File,ByteArray>)=files.forEach { (file,bytes) -> assertArrayEquals(bytes,file.readBytes()) }
    @After fun close() {
        // Disposable test teardown only; production has no force-close/reset for uncertain owners.
        caches.asReversed().forEach { cache -> try { cache.release() } catch(_:Throwable) {
            val h=cache.javaClass.getDeclaredField("h").apply { isAccessible=true }.get(cache)
            for(name in listOf("native","metadata","database")) {
                val value=h.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(h)
                try { when(value) { is SimpleCache -> value.release(); is PartitionContentMetadata -> value.close(); is SQLiteDatabase -> value.close() } } catch(_:Throwable) {}
            }
        } }
        fixtures.asReversed().forEach { runCatching { it.saves.close() }; it.migration.close(); it.catalog.close() }
    }
    @Test fun newSaveClosesAndReopensWithDurableUidAndMetadataWithoutMigrationReady() {
        val f=fixture(); val ticket=f.ticket(); val owner=f.owner(); val cache=retained(owner.openNewSave(ticket)); write(cache)
        val uid=cache.uid; assertEquals(PartitionSavePhase.Open,f.saves.find(key)?.phase); assertEquals(uid,f.saves.find(key)?.uid)
        assertNull(f.migration.find(key)); assertThrows(IOException::class.java) { owner.openReady(key) }
        cache.release(); assertEquals(PartitionSavePhase.Closed,f.saves.find(key)?.phase); assertEquals(0,f.budget.resident)
        val original=audio(f,ticket); f.saves.close(); f.saves=PartitionSaveJournal(f.root)
        val reopened=retained(f.owner().openSaved(key)); assertEquals(uid,reopened.uid)
        assertEquals(3L,reopened.cacheSpace); assertArrayEquals(byteArrayOf(8,0,7),reopened.getContentMetadata(key).get("future",null as ByteArray?))
        reopened.release(); assertEquals(PartitionSavePhase.Closed,f.saves.find(key)?.phase); kept(original)
    }
    @Test fun persistedOpeningOrOpenNeverAuthorizesAdoptionByAReplacementOwner() {
        for(state in listOf(PartitionSavePhase.Opening,PartitionSavePhase.Open)) {
            val f=fixture(); val ticket=f.ticket(); f.saves.opening(ticket)
            if(state==PartitionSavePhase.Open) f.saves.opened(ticket,7)
            f.saves.close(); f.saves=PartitionSaveJournal(f.root)
            assertThrows(IOException::class.java) { f.owner().openSaved(key) }
            assertThrows(IOException::class.java) { f.owner().openNewSave(ticket) }
            assertEquals(state,f.saves.find(key)?.phase); assertFalse(f.catalog.directory(ticket.allocation).exists()); assertEquals(0,f.budget.resident)
        }
    }
    @Test fun busyBudgetLeavesCleanSavedRouteRetryableAndDoesNotFabricateUncertainty() {
        val budget=PartitionNativeBudget(1); val first=fixture(budget); val second=fixture(budget)
        val a=first.ticket(); retained(first.owner().openNewSave(a)).release()
        val b=retained(second.owner().openNewSave(second.ticket()))
        assertThrows(PartitionCacheBusy::class.java) { first.owner().openSaved(key) }
        assertEquals(PartitionSavePhase.Closed,first.saves.find(key)?.phase); assertEquals(1,budget.resident)
        b.release(); retained(first.owner().openSaved(key)).release(); assertEquals(0,budget.resident)
    }
    @Test fun volumeLossOnClosePersistsUncertainAndKeepsNativeResidencyAndOriginalBytes() {
        val f=fixture(); val ticket=f.ticket(); val cache=retained(f.owner().openNewSave(ticket)); write(cache); val original=audio(f,ticket)
        f.volume=null; assertThrows(IOException::class.java) { cache.release() }
        assertEquals(PartitionSavePhase.Uncertain,f.saves.find(key)?.phase); assertEquals(1,f.budget.resident)
        f.volume="card-one"; assertThrows(IOException::class.java) { f.owner().openSaved(key) }; kept(original)
    }
    @Test fun preexistingFreshTargetAndStaleTicketRefuseWithoutOverwritingEitherAllocation() {
        val f=fixture(); val old=f.ticket(); val dir=f.catalog.directory(old.allocation).apply { mkdirs() }
        val original=File(dir,"original").apply { writeBytes(byteArrayOf(7,3)) }
        assertThrows(IOException::class.java) { f.owner().openNewSave(old) }
        assertEquals(PartitionSavePhase.Uncertain,f.saves.find(key)?.phase); assertEquals(0,f.budget.resident)
        val next=f.saves.begin(f.catalog.reserveFresh(key),old.token)
        assertThrows(IOException::class.java) { f.owner().openNewSave(old) }
        retained(f.owner().openNewSave(next)).release(); assertArrayEquals(byteArrayOf(7,3),original.readBytes())
    }
    @Test fun migrationClaimCannotBeOpenedAsNewSaveAndJournalFailureCannotGrantCleanRestart() {
        val collision=fixture(); val claim=collision.catalog.reserve(key); collision.migration.begin(claim,77,null)
        val ticket=collision.saves.begin(claim)
        assertThrows(IOException::class.java) { collision.owner().openNewSave(ticket) }
        assertFalse(collision.catalog.directory(claim).exists()); assertEquals(0,collision.budget.resident)
        val f=fixture(); val t=f.ticket(); val cache=retained(f.owner().openNewSave(t)); write(cache); val original=audio(f,t)
        f.saves.close(); assertThrows(Exception::class.java) { cache.release() }
        assertEquals(1,f.budget.resident); f.saves=PartitionSaveJournal(f.root)
        assertEquals(PartitionSavePhase.Open,f.saves.find(key)?.phase)
        assertThrows(IOException::class.java) { f.owner().openSaved(key) }; kept(original)
    }
}

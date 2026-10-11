@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.database.VersionTable
import androidx.media3.datasource.DataSpec
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
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionNativeOwnerTest {
    @get:Rule val folders=TemporaryFolder()
    private val legacyDatabase by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val natives=ArrayList<SimpleCache>()
    private val owned=ArrayList<Cache>()
    private val fixtures=ArrayList<Fixture>()
    private val key="saved/audio"
    private inner class Fixture(val budget:PartitionNativeBudget,val limits:PartitionResourceLimits) {
        val root=folders.newFolder()
        val catalog=CachePartitionCatalog(root)
        val journal=CacheMigrationJournal(root)
        var volume:String?="card-one"
        val owner=PartitionNativeOwner(catalog,journal,"card-one",{volume},limits,budget)
    }
    private fun fixture(budget:PartitionNativeBudget=PartitionNativeBudget(),limits:PartitionResourceLimits=PartitionResourceLimits())=
        Fixture(budget,limits).also { fixtures+=it }
    @After fun close() {
        // Quarantined production handles deliberately cannot be retried. Test-only teardown closes
        // disposable fixture handles by reflection AFTER all preservation assertions, never a recovery API.
        owned.asReversed().forEach { cache -> try { cache.release() } catch(_:Throwable) {
            val field=cache.javaClass.getDeclaredField("h").apply { isAccessible=true }
            val h=field.get(cache)
            for(name in listOf("native","metadata","database")) {
                val value=h.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(h)
                when(value) { is SimpleCache -> value.release(); is PartitionContentMetadata -> value.close(); is SQLiteDatabase -> value.close() }
            }
        } }
        natives.asReversed().forEach { it.release() }
        fixtures.asReversed().forEach { it.journal.close(); it.catalog.close() }
        legacyDatabase.close()
    }
    private fun source(bytes:ByteArray?=byteArrayOf(1,2,3),name:String=key):SimpleCache {
        val native=SimpleCache(folders.newFolder(),NoOpCacheEvictor(),legacyDatabase).also { natives+=it; it.checkInitialization() }
        if(bytes!=null) {
            val hole=requireNotNull(native.startReadWriteNonBlocking(name,0,bytes.size.toLong()))
            try { val file=native.startFile(name,0,bytes.size.toLong()); file.writeBytes(bytes); native.commitFile(file,bytes.size.toLong()) }
            finally { native.releaseHoleSpan(hole) }
        }
        native.applyContentMetadataMutations(name,ContentMetadataMutations().set("future",byteArrayOf(9,0,7)))
        return native
    }
    private fun publish(f:Fixture,source:Cache=source(),name:String=key):MigrationRecord =
        CacheMigrationPublication(f.catalog,f.journal,f.owner.migrationTarget(name)).migrate(source,name,{})
    private fun route(f:Fixture,name:String=key)=f.owner.openReady(name).also { owned+=it }
    private fun directory(f:Fixture)=f.catalog.directory(requireNotNull(f.journal.ready(key)).ticket.allocation)
    private fun audio(root:File)=File(root,"bytes").walkTopDown().filter { it.isFile && it.name.endsWith(".exo") }.associateWith { it.readBytes() }
    private fun kept(files:Map<File,ByteArray>)=files.forEach { (file,bytes) -> assertArrayEquals(bytes,file.readBytes()) }
    private fun sql(file:File,block:(SQLiteDatabase)->Unit) {
        val db=SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE)
        try { block(db) } finally { db.close() }
    }
    @Test fun realFactoryPublishesOnlyAfterCleanReopenAndKeepsEveryOriginalByteAndMetadata() {
        val f=fixture(); val src=source(); val original=src.getCachedSpans(key).associate { requireNotNull(it.file) to requireNotNull(it.file).readBytes() }
        val ready=publish(f,src); assertEquals(MigrationPhase.Ready,ready.phase); assertEquals(0,f.budget.resident)
        val dir=directory(f); assertTrue(File(dir,"index/native-v1.db").isFile); assertTrue(File(dir,"metadata/content-v1.db").isFile)
        assertFalse(File(dir,"bytes/content-v1.db").exists())
        val cache=route(f); assertEquals(ready.targetUid,cache.uid); assertEquals(1,f.budget.resident)
        assertEquals(ready.evidence,CacheMigrationPreparation.verify(src,cache,key,{})); cache.release(); cache.release()
        assertEquals(0,f.budget.resident); kept(original)
    }
    @Test fun metadataOnlyReadyRouteSurvivesRealNativeAndSidecarCloseReopen() {
        val f=fixture(); val src=source(null); val ready=publish(f,src)
        assertEquals(MigrationCopyEvidence(0,0),ready.evidence); assertEquals(0,f.budget.resident)
        val cache=route(f); assertTrue(cache.getCachedSpans(key).isEmpty())
        assertArrayEquals(byteArrayOf(9,0,7),cache.getContentMetadata(key).get("future",null as ByteArray?))
        assertEquals(ready.evidence,CacheMigrationPreparation.verify(src,cache,key,{})); cache.release()
        assertArrayEquals(byteArrayOf(9,0,7),route(f).getContentMetadata(key).get("future",null as ByteArray?))
    }
    @Test fun readyReaderPinsGlobalNativeResidencyThroughEofUntilSourceCloses() {
        val f=fixture(); publish(f)
        val pool=PartitionCacheLeases({ route(f,it) },capacity=1)
        val reader=PartitionSavedSource(pool).createDataSource()
        reader.open(DataSpec.Builder().setUri(Uri.parse("https://unused.invalid/audio")).setKey(key).setLength(3).build())
        val bytes=ByteArray(3); assertEquals(3,reader.read(bytes,0,3)); assertArrayEquals(byteArrayOf(1,2,3),bytes)
        assertEquals(-1,reader.read(bytes,0,3)); pool.close()
        assertEquals(1,pool.resident); assertEquals(1,f.budget.resident)
        reader.close(); assertEquals(0,pool.resident); assertEquals(0,f.budget.resident)
    }
    @Test fun reservationAndInterruptedUnreadyCopiesNeverBecomeReadyRoutesOrAdoptedTargets() {
        val f=fixture(); val allocation=f.catalog.reserve(key); f.journal.begin(allocation,123,null)
        assertThrows(IOException::class.java) { route(f) }; assertEquals(0,f.budget.resident)
        val factory=f.owner.migrationTarget(key); val target=factory(f.catalog.directory(allocation)).also { owned+=it }
        target.applyContentMetadataMutations(key,ContentMetadataMutations().set("kept",3L)); target.release()
        val dir=f.catalog.directory(allocation); val before=dir.walkTopDown().filter { it.isFile }.associateWith { it.length() }
        assertThrows(IOException::class.java) { f.owner.migrationTarget(key)(dir) }
        assertNull(f.journal.ready(key)); assertEquals(0,f.budget.resident)
        before.forEach { (file,length) -> assertEquals(length,file.length()) }
        // Only the same current-process adapter can reopen its known cleanly closed attempt.
        factory(dir).also { owned+=it }.release(); assertEquals(0,f.budget.resident)
    }
    @Test fun wrongMigrationPathRefusesBeforeAnyNativeCreationOrPermitLeak() {
        val f=fixture(); val allocation=f.catalog.reserve(key); f.journal.begin(allocation,123,null)
        assertThrows(IOException::class.java) { f.owner.migrationTarget(key)(folders.newFolder()) }
        assertFalse(f.catalog.directory(allocation).exists()); assertEquals(0,f.budget.resident)
    }
    @Test fun oneSharedBudgetCountsTwoOwnersAndOnlyCleanCloseReturnsCapacity() {
        val budget=PartitionNativeBudget(1); val first=fixture(budget); val second=fixture(budget)
        publish(first); publish(second)
        val cache=route(first); assertEquals(1,budget.resident)
        assertThrows(PartitionCacheBusy::class.java) { route(second) }
        assertEquals(1,budget.resident); cache.release(); assertEquals(0,budget.resident)
        route(second).release(); assertEquals(0,budget.resident)
    }
    @Test fun missingUidDatabaseOrSidecarNeverCreatesReplacementOrPrunesOriginalSpans() {
        for(missing in listOf("uid","index","metadata")) {
            val f=fixture(); publish(f); val dir=directory(f); val original=audio(dir)
            val file=when(missing) {
                "uid" -> File(dir,"bytes/${java.lang.Long.toHexString(requireNotNull(f.journal.ready(key)?.targetUid))}.uid")
                "index" -> File(dir,"index/native-v1.db")
                else -> File(dir,"metadata/content-v1.db")
            }
            assertTrue(file.delete()); assertThrows(IOException::class.java) { route(f) }
            assertFalse(file.exists()); assertFalse(SimpleCache.isCacheFolderLocked(File(dir,"bytes")))
            assertEquals(0,f.budget.resident); kept(original)
        }
    }
    @Test fun incompatibleNativeVersionAndOversizedLogicalMetadataRefuseBeforeNativeInitialization() {
        val f=fixture(limits=PartitionResourceLimits(metadataBytes=20)); publish(f)
        val dir=directory(f); val original=audio(dir); val uid=requireNotNull(f.journal.ready(key)?.targetUid)
        sql(File(dir,"index/native-v1.db")) { VersionTable.setVersion(it,VersionTable.FEATURE_CACHE_CONTENT_METADATA,java.lang.Long.toHexString(uid),99) }
        assertThrows(IOException::class.java) { route(f) }; assertEquals(0,f.budget.resident); kept(original)
        sql(File(dir,"index/native-v1.db")) {
            assertEquals(99,VersionTable.getVersion(it,VersionTable.FEATURE_CACHE_CONTENT_METADATA,java.lang.Long.toHexString(uid)))
            VersionTable.setVersion(it,VersionTable.FEATURE_CACHE_CONTENT_METADATA,java.lang.Long.toHexString(uid),1)
        }
        PartitionContentMetadata(File(dir,"metadata"),uid,key).use { store ->
            store.write(store.read().copyWithMutationsApplied(ContentMetadataMutations().set("big",ByteArray(30))))
        }
        assertThrows(IOException::class.java) { route(f) }; assertEquals(0,f.budget.resident); kept(original)
        assertFalse(SimpleCache.isCacheFolderLocked(File(dir,"bytes")))
    }
    @Test fun changedVolumeRefusesReadsAndCloseKeepsNativePermitAndFilesQuarantined() {
        val f=fixture(); publish(f); val cache=route(f); val dir=directory(f); val original=audio(dir)
        f.volume="different-card"
        assertThrows(IOException::class.java) { cache.cacheSpace }
        assertThrows(IOException::class.java) { cache.release() }
        assertEquals(1,f.budget.resident); assertTrue(SimpleCache.isCacheFolderLocked(File(dir,"bytes"))); kept(original)
        f.volume="card-one"
        assertThrows(IOException::class.java) { cache.release() }
        assertThrows(IOException::class.java) { route(f) }; assertEquals(1,f.budget.resident); kept(original)
    }
    @Test fun staleFileBeforeCloseIsRefusedBeforeNativeReleaseCanPruneIt() {
        val f=fixture(); publish(f); val cache=route(f); val dir=directory(f)
        val file=audio(dir).keys.single(); file.writeBytes(byteArrayOf(7,8))
        assertThrows(IOException::class.java) { cache.release() }
        assertArrayEquals(byteArrayOf(7,8),file.readBytes()); assertTrue(SimpleCache.isCacheFolderLocked(File(dir,"bytes")))
        assertEquals(1,f.budget.resident); assertThrows(IOException::class.java) { cache.release() }
    }
    @Test fun foreignByteFolderEntryAndSymlinkedMetadataRefuseWithoutDeletingAnything() {
        val f=fixture(); publish(f); val dir=directory(f); val original=audio(dir)
        val extra=File(dir,"bytes/foreign").apply { writeBytes(byteArrayOf(5)) }
        assertThrows(IOException::class.java) { route(f) }; assertEquals(0,f.budget.resident); kept(original)
        assertArrayEquals(byteArrayOf(5),extra.readBytes()); assertTrue(extra.delete())
        val meta=File(dir,"metadata"); val renamed=File(dir,"old-metadata"); assertTrue(meta.renameTo(renamed))
        Files.createSymbolicLink(meta.toPath(),renamed.toPath())
        assertThrows(IOException::class.java) { route(f) }; assertEquals(0,f.budget.resident); kept(original)
        assertTrue(Files.isSymbolicLink(meta.toPath())); assertTrue(File(renamed,"content-v1.db").isFile)
    }
    @Test fun aReplacementReservationCannotOverrideThePreviouslyVerifiedReadyAllocation() {
        val f=fixture(); val ready=publish(f); val replacement=f.catalog.reserveFresh(key)
        assertNotEquals(ready.ticket.allocation,replacement); assertFalse(f.catalog.directory(replacement).exists())
        val cache=route(f); assertEquals(ready.targetUid,cache.uid); assertEquals(3L,cache.cacheSpace); cache.release()
        assertEquals(ready,f.journal.ready(key)); assertEquals(0,f.budget.resident)
    }
}

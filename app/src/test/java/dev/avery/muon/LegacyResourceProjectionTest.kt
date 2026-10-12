@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.database.VersionTable
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.DirectoryStream
import java.nio.file.Path
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LegacyResourceProjectionTest {
    @get:Rule val folders=TemporaryFolder()
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val caches=mutableListOf<SimpleCache>()
    private val reads=mutableListOf<SQLiteDatabase>()
    private val payload=byteArrayOf(2,4,6,8)
    private fun cache(root:File=folders.newFolder())=SimpleCache(root,NoOpCacheEvictor(),database).also { caches+=it; it.checkInitialization() }
    private fun seed(cache:Cache,key:String,position:Long=0):File {
        val hole=requireNotNull(cache.startReadWrite(key,position,payload.size.toLong()))
        try { val file=cache.startFile(key,position,payload.size.toLong()); file.writeBytes(payload); cache.commitFile(file,payload.size.toLong()); return file }
        finally { cache.releaseHoleSpan(hole) }
    }
    private inner class Fixture(count:Int=1) {
        val root=folders.newFolder(); val source=cache(root); val uid=source.uid
        val table="ExoPlayerCacheIndex"+java.lang.Long.toHexString(uid)
        val original=seed(source,"selected")
        init {
            source.applyContentMetadataMutations("selected",ContentMetadataMutations().set("unknown_future",byteArrayOf(9,7)))
            for(i in 1 until count) seed(source,"other/$i")
            source.release()
        }
        fun db()=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        fun project(checkpoint:()->Unit={})=LegacyResourceProjection.read(root,uid,"selected",db(),checkpoint)
    }
    @After fun close() { try { reads.asReversed().forEach { it.close() }; caches.asReversed().forEach { it.release() } } finally { database.close() } }
    private fun <T> worker(read:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {return executor.submit<T> {read()}.get(30,java.util.concurrent.TimeUnit.SECONDS)}
        catch(failure:java.util.concurrent.ExecutionException) {throw requireNotNull(failure.cause)}
        finally {executor.shutdownNow(); check(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS))}
    }
    @Test fun retirementIntentNeedsConsentAndSurvivesRestartWithoutReplacingItsTombstone() {
        val f=Fixture(); val manifest=worker {LegacyRetirementManifest.capture(f.root,f.uid,"selected",f.db(),{})}
        val root=folders.newFolder(); val first=LegacyRetirementJournal(root,true)
        val pending=worker {
            first.open()
            assertThrows(IOException::class.java) {first.begin(manifest,false)}
            assertNull(first.find("selected"))
            val row=first.begin(manifest,true); assertTrue(first.holdsName("selected")); first.close(); row
        }
        val next=LegacyRetirementJournal(root,false)
        try {worker {
            next.open(); assertEquals(pending,next.find("selected"))
            assertThrows(IOException::class.java) {next.begin(manifest,true)}
            assertThrows(IOException::class.java) {next.complete(pending.copy(token=java.util.UUID.randomUUID().toString()))}
            val complete=next.complete(pending); assertEquals(LegacyRetirementPhase.Complete,complete.phase)
            assertTrue(next.holdsName("selected")); assertThrows(IOException::class.java) {next.complete(pending)}
            assertEquals(complete,next.find("selected"))
        }} finally {worker {next.close()}}
        assertArrayEquals(payload,f.original.readBytes()) // Ledger transition is NOT physical deletion.
    }
    @Test fun retirementIntentUsesTwoRowPagesAndRefusesOversizedBlobBeforeHydration() {
        val f=Fixture(4); val index=f.db(); val root=folders.newFolder(); val journal=LegacyRetirementJournal(root,true)
        val names=listOf("selected","other/1","other/2")
        try {worker {
            journal.open()
            for(name in names) journal.begin(LegacyRetirementManifest.capture(f.root,f.uid,name,index,{}),true)
            val page=journal.page(); assertEquals(LEGACY_RETIREMENT_PAGE,page.size)
            val tail=journal.page(page.last().manifest.key); assertEquals(1,tail.size)
            assertEquals(names.sorted(),(page+tail).map {it.manifest.key})
            SQLiteDatabase.openDatabase(File(root,"legacy-retirements-v1.db").path,null,SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("UPDATE retirements SET manifest=zeroblob(8388608) WHERE key=?",arrayOf(savedCatalogSortKey("selected")))
            }
            assertThrows(IOException::class.java) {journal.find("selected")}; assertTrue(journal.holdsName("selected"))
        }} finally {worker {journal.close()}}
        assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun retirementJournalResumeRefusesMissingOrUnsupportedSchemaWithoutRepair() {
        val root=folders.newFolder(); val missing=LegacyRetirementJournal(root,false)
        assertFalse(File(root,"legacy-retirements-v1.db").exists()) // Constructor does no I/O.
        worker {assertThrows(IOException::class.java) {missing.open()}; missing.close()}
        assertFalse(File(root,"legacy-retirements-v1.db").exists())
        val file=File(root,"legacy-retirements-v1.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use {it.execSQL("CREATE TABLE foreign_data(value TEXT)"); it.version=2}
        val unsupported=LegacyRetirementJournal(root,false)
        worker {assertThrows(IOException::class.java) {unsupported.open()}; unsupported.close()}
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(2,it.version); assertTrue(it.rawQuery("SELECT name FROM sqlite_master WHERE name='foreign_data'",null).use {c ->c.moveToFirst()})
        }
        assertThrows(IllegalStateException::class.java) {LegacyRetirementJournal(folders.newFolder(),true).open()}
    }
    @Test fun retirementJournalRetainsActualDatabaseAfterUnknownCloseWithoutRetry() {
        val root=folders.newFolder(); var actual:SQLiteDatabase?=null; var attempts=0
        val journal=LegacyRetirementJournal(root,true,
            openDatabase={file,_ -> SQLiteDatabase.openOrCreateDatabase(file,null).also {actual=it}},
            closeDatabase={attempts++; throw IOException("injected unknown DB close")})
        try {worker {
            journal.open(); assertThrows(IOException::class.java) {journal.close()}
            assertThrows(IOException::class.java) {journal.close()}; assertThrows(IOException::class.java) {journal.page()}
            assertEquals(1,attempts); assertTrue(requireNotNull(actual).isOpen)
        }} finally {actual?.close()} // Fixture knows its injected close did not reach the actual DB.
    }
    @Test fun retirementJournalRetainsUnknownCursorAndDoesNotCloseDatabaseOverIt() {
        val root=folders.newFolder(); var actual:SQLiteDatabase?=null; var child:java.io.Closeable?=null
        var childAttempts=0; var dbAttempts=0
        val journal=LegacyRetirementJournal(root,true,
            openDatabase={file,_ ->SQLiteDatabase.openOrCreateDatabase(file,null).also {actual=it}},
            closeDatabase={dbAttempts++; it.close()},
            closeIo={child=it; childAttempts++; throw IOException("injected unknown cursor close")})
        worker {
            try {
                assertThrows(IOException::class.java) {journal.open()}
                assertThrows(IOException::class.java) {journal.close()}; assertThrows(IOException::class.java) {journal.open()}
                assertEquals(1,childAttempts); assertEquals(0,dbAttempts); assertTrue(requireNotNull(actual).isOpen)
            } finally {
                child?.close()
                actual?.let {if(it.inTransaction()) it.endTransaction(); it.close()}
            } // Injected fixture cleanup stays on the transaction's owner thread; no production reset.
        }
    }
    @Test fun retirementManifestRoundTripsOnlySelectedFilesWithoutNativeReopenOrMutation() {
        val f=Fixture(40); val index=f.db()
        val manifest=worker {LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})}
        assertEquals(1,manifest.spans.size); assertEquals(4L,manifest.spans.single().length)
        val encoded=manifest.encode(); assertTrue(encoded.size<LEGACY_RETIREMENT_MANIFEST_BYTES)
        val restored=LegacyRetirementManifest.decode(encoded); assertEquals(manifest,restored)
        worker {restored.verifyRemaining(f.root,index,false,{})}
        assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(f.root))
        assertThrows(IOException::class.java) {LegacyRetirementManifest.decode(encoded+byteArrayOf(0))}
        assertThrows(IOException::class.java) {LegacyRetirementManifest.decode(ByteArray(LEGACY_RETIREMENT_MANIFEST_BYTES+1))}
    }
    @Test fun retirementManifestDetectsSameLengthChangedBytesAndChangedMetadataWithoutCleanup() {
        val f=Fixture(); val index=f.db()
        val manifest=worker {LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})}
        f.original.writeBytes(byteArrayOf(9,9,9,9))
        assertThrows(IOException::class.java) {worker {manifest.verifyRemaining(f.root,index,false,{})}}
        assertArrayEquals(byteArrayOf(9,9,9,9),f.original.readBytes())
        f.original.writeBytes(payload)
        database.writableDatabase.execSQL("UPDATE ${f.table} SET metadata=zeroblob(8388608) WHERE key='selected'")
        assertThrows(IOException::class.java) {worker {manifest.verifyRemaining(f.root,index,false,{})}}
        assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun retirementManifestDistinguishesPendingMissingFileFromFreshAuthorityAndRejectsRedirectedShard() {
        val f=Fixture(); val index=f.db()
        val manifest=worker {LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})}
        assertTrue(f.original.delete()) // Disposable fixture simulates a crash after an authorized unlink.
        assertThrows(IOException::class.java) {worker {manifest.verifyRemaining(f.root,index,false,{})}}
        worker {manifest.verifyRemaining(f.root,index,true,{})}
        val shard=requireNotNull(f.original.parentFile); assertTrue(shard.delete())
        java.nio.file.Files.createSymbolicLink(shard.toPath(),folders.newFolder().toPath())
        assertThrows(IOException::class.java) {worker {manifest.verifyRemaining(f.root,index,true,{})}}
        assertFalse(SimpleCache.isCacheFolderLocked(f.root))
    }
    @Test fun retirementManifestRefusesUnrecordedIndexedSpansBeforeAnyFileCanBeRetired() {
        val f=Fixture(); val index=f.db()
        val manifest=worker {LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})}
        val table="ExoPlayerCacheFileMetadata"+java.lang.Long.toHexString(f.uid)
        database.writableDatabase.execSQL("INSERT INTO $table VALUES(?,?,?)",arrayOf("${manifest.contentId}.9.99.v3.exo",4L,99L))
        assertThrows(IOException::class.java) {worker {manifest.verifyRemaining(f.root,index,true,{})}}
        assertArrayEquals(payload,f.original.readBytes())
        assertThrows(IllegalStateException::class.java) {LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})}
    }
    @Test fun nativeHundredsOfResourcesProjectOnlySelectedRecordWithoutReopeningSource() {
        val f=Fixture(200); val before=f.original.readBytes(); val selected=f.project()
        assertEquals(setOf("selected"),selected.keys); assertEquals(1,selected.getCachedSpans("selected").size)
        assertEquals(4L,selected.cacheSpace); assertFalse(SimpleCache.isCacheFolderLocked(f.root))
        assertArrayEquals(byteArrayOf(9,7),selected.getContentMetadata("selected").get("unknown_future",null as ByteArray?))
        assertThrows(IOException::class.java) { selected.getContentMetadata("other/1") }
        assertThrows(IOException::class.java) { selected.removeResource("selected") }
        assertThrows(IOException::class.java) { selected.release() }
        assertArrayEquals(before,f.original.readBytes())
    }
    @Test fun supportedMigrationCopiesPartialBytesBeyondDeclaredLengthAndUnknownMetadata() {
        val f=Fixture(); val root=folders.newFolder()
        val source=cache(root) // Independent fixture for two ranges; original source stays closed.
        seed(source,"partial",0); seed(source,"partial",16)
        source.applyContentMetadataMutations("partial",ContentMetadataMutations.setContentLength(
            ContentMetadataMutations().set("unknown_future",byteArrayOf(9,7)),2))
        val uid=source.uid; source.release()
        val read=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        val projected=LegacyResourceProjection.read(root,uid,"partial",read,{})
        val target=cache()
        val evidence=CacheMigrationPreparation.copy(projected,target,"partial",{})
        assertEquals(MigrationCopyEvidence(8,2),evidence)
        assertEquals(listOf(MigrationRange(0,4),MigrationRange(16,4)),CacheMigrationPreparation.ranges(projected,"partial"))
        assertEquals(4L,projected.getCachedLength("partial",0,100)); assertEquals(-12L,projected.getCachedLength("partial",4,100))
        assertEquals(2L,projected.getCachedBytes("partial",3,14))
        assertThrows(IOException::class.java) { projected.startReadWriteNonBlocking("partial",4,4) }
        assertArrayEquals(byteArrayOf(9,7),target.getContentMetadata("partial").get("unknown_future",null as ByteArray?))
        assertArrayEquals(payload,f.original.readBytes()); assertFalse(SimpleCache.isCacheFolderLocked(root))
    }
    @Test fun oversizedPersistedMetadataRefusesBeforeCursorWindowAndDoesNotRepairRow() {
        val f=Fixture()
        database.writableDatabase.execSQL("UPDATE ${f.table} SET metadata=zeroblob(8388608) WHERE key='selected'")
        assertThrows(IOException::class.java) { f.project() }
        database.readableDatabase.rawQuery("SELECT length(metadata) FROM ${f.table} WHERE key='selected'",null).use {
            assertTrue(it.moveToFirst()); assertEquals(8388608L,it.getLong(0))
        }
        assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun unknownVersionAndForeignFilesRefuseWithoutNativeInitializationOrDeletion() {
        val f=Fixture(); val hex=java.lang.Long.toHexString(f.uid)
        VersionTable.setVersion(database.writableDatabase,VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex,2)
        assertThrows(IOException::class.java) { f.project() }
        assertEquals(2,VersionTable.getVersion(database.readableDatabase,VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex))
        VersionTable.setVersion(database.writableDatabase,VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex,1)
        val foreign=File(f.root,"retained-unknown").apply { writeBytes(byteArrayOf(5,6)) }
        assertThrows(IOException::class.java) { f.project() }
        assertArrayEquals(byteArrayOf(5,6),foreign.readBytes()); assertArrayEquals(payload,f.original.readBytes())
        assertFalse(SimpleCache.isCacheFolderLocked(f.root))
    }
    @Test fun oversizedVersionAndSpanScalarPayloadsRefuseBeforeCursorWindow() {
        val f=Fixture(); val hex=java.lang.Long.toHexString(f.uid)
        database.writableDatabase.execSQL("UPDATE ExoPlayerVersions SET version=zeroblob(8388608) WHERE feature=? AND instance_uid=?",
            arrayOf(VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex))
        assertThrows(IOException::class.java) { f.project() }
        VersionTable.setVersion(database.writableDatabase,VersionTable.FEATURE_CACHE_CONTENT_METADATA,hex,1)
        database.writableDatabase.execSQL("UPDATE ExoPlayerCacheFileMetadata$hex SET length=zeroblob(8388608)")
        assertThrows(IOException::class.java) { f.project() }
        assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun malformedFieldLengthsAndDuplicateKeysRefuseWithoutAllocationOrRepair() {
        val f=Fixture()
        val bytes=ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { it.writeInt(1); it.writeUTF("bad"); it.writeInt(Int.MAX_VALUE) } }.toByteArray()
        database.writableDatabase.execSQL("UPDATE ${f.table} SET metadata=? WHERE key='selected'",arrayOf(bytes))
        assertThrows(IOException::class.java) { f.project() }
        database.writableDatabase.execSQL("UPDATE ${f.table} SET metadata=? WHERE key='selected'",arrayOf(ByteArray(4)))
        database.writableDatabase.execSQL("INSERT INTO ${f.table}(id,key,metadata) VALUES(2147483646,'selected',?)",arrayOf(ByteArray(4)))
        assertThrows(IOException::class.java) { f.project() }; assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun realFragmentationOverBudgetAndSymlinksRefusePreservingAllOriginalSpans() {
        val root=folders.newFolder(); val source=cache(root); val uid=source.uid
        val original=(0..MIGRATION_RANGES).map { seed(source,"fragmented",it*8L) }
        source.release()
        val read=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        assertThrows(IOException::class.java) { LegacyResourceProjection.read(root,uid,"fragmented",read,{}) }
        assertTrue(original.all { it.readBytes().contentEquals(payload) })
        val f=Fixture(); val slot=(0..9).first { !File(f.root,it.toString()).exists() }
        val link=File(f.root,slot.toString())
        Files.createSymbolicLink(link.toPath(),folders.newFolder().toPath())
        try { assertThrows(IOException::class.java) { f.project() } } finally { Files.delete(link.toPath()) }
        assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun unknownDirectoryCloseRetainsExclusiveGateAndRefusesBeforeTargetReservation() {
        val f=Fixture(); val gate=SavedStorageBarrier(); val raw=mutableListOf<DirectoryStream<Path>>()
        val ownership=MigrationIoOwnership(directories={ path ->
            val actual=Files.newDirectoryStream(path).also(raw::add)
            object:DirectoryStream<Path> by actual { override fun close():Unit=throw IOException("Injected unknown directory close") }
        })
        val root=folders.newFolder()
        try { CachePartitionCatalog(root).use { catalog -> CacheMigrationJournal(root).use { journal ->
            var opens=0; val publisher=CacheMigrationPublication(catalog,journal,{ opens++; cache(it) })
            val failure=assertThrows(MigrationIoUncertain::class.java) {
                BarrierCacheMigration(gate).runProjected(CacheMigrationControl(1000,nanoTime={0L}),"selected",publisher,{Long.MAX_VALUE},{}) { checked ->
                    LegacyResourceProjection.read(f.root,f.uid,"selected",f.db(),checked,ownership)
                }
            }
            assertFalse(failure.ownership.quiescent); assertEquals(1,gate.active)
            assertThrows(IOException::class.java) { gate.shared() }
            assertEquals(0,opens); assertEquals(0L,catalog.count()); assertNull(journal.find("selected"))
            assertArrayEquals(payload,f.original.readBytes())
        } } } finally {
            // Test-only actual disposable handles; never retry the uncertain production wrapper.
            raw.asReversed().forEach { it.close() }
        }
    }
    @Test fun activeNativeAndWritableDatabaseAreRefusedAndBorrowedCheckpointRemainsEffective() {
        val f=Fixture(); var available=true
        val projected=f.project { if(!available) throw IOException("Borrowed source barrier lost") }
        available=false
        assertThrows(IOException::class.java) { projected.getCachedLength("selected",0,100) }
        assertThrows(IOException::class.java) { LegacyResourceProjection.read(f.root,f.uid,"selected",database.writableDatabase,{}) }
        val reopened=cache(f.root)
        assertThrows(IOException::class.java) { f.project() }
        reopened.release(); assertArrayEquals(payload,f.original.readBytes())
    }
    @Test fun pendingFilesCleanupRemovesOnlyManifestSpansAndRecoveryKeepsJournalPending() {
        val root=folders.newFolder(); val source=cache(root); val uid=source.uid
        val first=seed(source,"selected"); val second=seed(source,"selected",4); val other=seed(source,"other")
        source.release()
        val index=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        val journal=LegacyRetirementJournal(folders.newFolder(),true)
        try {worker {
            val manifest=LegacyRetirementManifest.capture(root,uid,"selected",index,{})
            journal.open(); val pending=journal.begin(manifest,true)
            assertEquals(LegacyRetirementFileResult(8,0),LegacyRetirementFiles().run(pending,root,index,{}))
            assertFalse(first.exists()); assertFalse(second.exists()); assertArrayEquals(payload,other.readBytes())
            assertEquals(pending,journal.find("selected")); assertTrue(journal.holdsName("selected"))
            assertEquals(LegacyRetirementFileResult(0,8),LegacyRetirementFiles().run(pending,root,index,{}))
            assertEquals(pending,journal.find("selected")); assertFalse(SimpleCache.isCacheFolderLocked(root))
        }} finally {worker {journal.close()}}
    }
    @Test fun unknownUnlinkRetainsAttemptAndExactPendingAllowsNewProcessStyleRecovery() {
        val root=folders.newFolder(); val source=cache(root); val uid=source.uid
        val first=seed(source,"selected"); val second=seed(source,"selected",4); val other=seed(source,"other")
        source.release()
        val index=SQLiteDatabase.openDatabase(database.readableDatabase.path,null,SQLiteDatabase.OPEN_READONLY).also(reads::add)
        val journal=LegacyRetirementJournal(folders.newFolder(),true)
        try {worker {
            val manifest=LegacyRetirementManifest.capture(root,uid,"selected",index,{})
            journal.open(); val pending=journal.begin(manifest,true); var calls=0
            val attempt=LegacyRetirementFiles(unlink={file -> calls++; Files.delete(file.toPath()); throw IOException("Unknown unlink return")})
            val failure=assertThrows(PartitionOwnershipUncertain::class.java) {attempt.run(pending,root,index,{})}
            assertSame(attempt,failure.owner); assertTrue(attempt.uncertain); assertEquals(1,calls)
            assertFalse(first.exists()); assertTrue(second.exists()); assertArrayEquals(payload,other.readBytes())
            assertThrows(IOException::class.java) {attempt.run(pending,root,index,{})}; assertEquals(1,calls)
            assertEquals(LegacyRetirementFileResult(4,4),LegacyRetirementFiles().run(pending,root,index,{}))
            assertFalse(second.exists()); assertEquals(pending,journal.find("selected"))
        }} finally {worker {journal.close()}}
    }
    @Test fun changedOriginalBytesRefuseCleanupBeforeAnyUnlink() {
        val f=Fixture(3); val index=f.db(); val journal=LegacyRetirementJournal(folders.newFolder(),true)
        try {worker {
            val manifest=LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})
            journal.open(); val pending=journal.begin(manifest,true); var calls=0
            f.original.writeBytes(byteArrayOf(9,9,9,9))
            val attempt=LegacyRetirementFiles(unlink={calls++})
            assertThrows(IOException::class.java) {attempt.run(pending,f.root,index,{})}
            assertFalse(attempt.uncertain); assertEquals(0,calls); assertTrue(f.original.exists())
            assertEquals(pending,journal.find("selected"))
        }} finally {worker {journal.close()}}
    }
    @Test fun unknownCleanupReaderCloseRetainsActualHandleAndNeverUnlinks() {
        val f=Fixture(); val index=f.db(); val journal=LegacyRetirementJournal(folders.newFolder(),true)
        var retained:java.io.Closeable?=null
        try {worker {
            val manifest=LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})
            journal.open(); val pending=journal.begin(manifest,true); var calls=0
            val ownership=MigrationIoOwnership(closeHandle={handle ->
                if(handle is java.io.RandomAccessFile) {retained=handle; throw IOException("Unknown reader close")}
                handle.close()
            })
            val attempt=LegacyRetirementFiles(unlink={calls++},ownership=ownership)
            val failure=assertThrows(PartitionOwnershipUncertain::class.java) {attempt.run(pending,f.root,index,{})}
            assertSame(attempt,failure.owner); assertFalse(ownership.quiescent); assertTrue(attempt.uncertain)
            assertNotNull(retained); assertEquals(0,calls); assertArrayEquals(payload,f.original.readBytes())
        }} finally {retained?.close(); worker {journal.close()}}
    }
    @Test fun unknownCleanupCursorCloseRetainsActualCursorAndDatabaseBeforeUnlink() {
        val f=Fixture(); val index=f.db(); val journal=LegacyRetirementJournal(folders.newFolder(),true)
        var retained:android.database.Cursor?=null
        try {worker {
            val manifest=LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})
            journal.open(); val pending=journal.begin(manifest,true); var calls=0
            val ownership=MigrationIoOwnership(closeHandle={handle ->
                if(handle is android.database.Cursor) {retained=handle; throw IOException("Unknown cursor close")}
                handle.close()
            })
            val attempt=LegacyRetirementFiles(unlink={calls++},ownership=ownership)
            assertThrows(PartitionOwnershipUncertain::class.java) {attempt.run(pending,f.root,index,{})}
            assertFalse(ownership.quiescent); assertTrue(attempt.uncertain); assertTrue(index.isOpen)
            assertNotNull(retained); assertFalse(requireNotNull(retained).isClosed)
            assertEquals(0,calls); assertArrayEquals(payload,f.original.readBytes())
        }} finally {retained?.close(); worker {journal.close()}}
    }

    @Test fun retirementManifestBindsExactCompletedIdentityAndPreservesUnboundV1Recovery() {
        val f=Fixture(); val index=f.db()
        val manifest=worker {LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})}
        val request=androidx.media3.exoplayer.offline.DownloadRequest.Builder("selected",android.net.Uri.parse("http://127.0.0.1:7814/api1/fileopus/42"))
            .setCustomCacheKey("selected").setData(byteArrayOf(7)).build()
        val completed=requireNotNull(PartitionSaveCompletion.fromOriginal(androidx.media3.exoplayer.offline.Download(request,
            androidx.media3.exoplayer.offline.Download.STATE_COMPLETED,1,2,4,0,0)))
        val bound=manifest.withCompletedDownload(completed)
        assertEquals(completed.retirementIdentity(),bound.completedDownloadIdentity)
        assertEquals(bound,LegacyRetirementManifest.decode(bound.encode()))
        assertNull(manifest.completedDownloadIdentity)
        // v1 has the same fields before v2's optional-completion tail; no row deletion authority.
        val v1=manifest.encode().dropLast(4).toByteArray().also {it[3]=1}
        assertEquals(manifest,LegacyRetirementManifest.decode(v1))
        val v2=bound.encode().dropLast(3).toByteArray().also {it[3]=2}
        assertEquals(bound,LegacyRetirementManifest.decode(v2))
        // A different key cannot be bound even when other scalar completion values match.
        val other=androidx.media3.exoplayer.offline.DownloadRequest.Builder("other",request.uri).setCustomCacheKey("other").build()
        val wrong=requireNotNull(PartitionSaveCompletion.fromOriginal(androidx.media3.exoplayer.offline.Download(other,
            androidx.media3.exoplayer.offline.Download.STATE_COMPLETED,1,2,4,0,0)))
        assertThrows(IOException::class.java) {manifest.withCompletedDownload(wrong)}
        assertArrayEquals(payload,f.original.readBytes())
    }

    @Test fun originalPendingRecoveryPagesSkipCompleteWithoutLosingDeleteIntents() {
        val f=Fixture(4); val index=f.db(); val journal=LegacyRetirementJournal(folders.newFolder(),true)
        worker {journal.open(); try {
            val names=listOf("other/1","other/2","selected")
            for(name in names) {
                val record=journal.begin(LegacyRetirementManifest.capture(f.root,f.uid,name,index,{}),true)
                if(name=="other/1") journal.complete(record)
            }
            assertEquals(listOf("other/2","selected"),journal.pendingPage().map {it.manifest.key})
            assertTrue(journal.pendingPage("selected").isEmpty())
            assertEquals(listOf("other/1","other/2"),journal.page().map {it.manifest.key})
        } finally {journal.close()}}
    }

    @Test fun retainedManifestHasDistinctAuthorityAndV4StillDecodesWithoutIt() {
        val f=Fixture(); val index=f.db()
        val manifest=worker {LegacyRetirementManifest.capture(f.root,f.uid,"selected",index,{})}
        val request=androidx.media3.exoplayer.offline.DownloadRequest.Builder("selected",android.net.Uri.parse("http://127.0.0.1:7814/42"))
            .setCustomCacheKey("selected").build()
        val retained=requireNotNull(PartitionRetainedOriginal.from(androidx.media3.exoplayer.offline.Download(request,
            androidx.media3.exoplayer.offline.Download.STATE_STOPPED,1,2,-1,RETAINED_STOP_REASON,0)))
        val bound=manifest.withRetainedDownload(retained)
        assertEquals(retained.identity,bound.retainedDownloadIdentity);assertNull(bound.completedDownloadIdentity)
        assertEquals(bound,LegacyRetirementManifest.decode(bound.encode()))
        val completed=requireNotNull(PartitionSaveCompletion.fromOriginal(androidx.media3.exoplayer.offline.Download(request,
            androidx.media3.exoplayer.offline.Download.STATE_COMPLETED,1,2,4,0,0)))
        assertThrows(IOException::class.java) {bound.withCompletedDownload(completed)}
        assertThrows(IOException::class.java) {manifest.withCompletedDownload(completed).withRetainedDownload(retained)}
        val v4=manifest.encode().dropLast(1).toByteArray().also {it[3]=4}
        assertEquals(manifest,LegacyRetirementManifest.decode(v4));assertNull(LegacyRetirementManifest.decode(v4).retainedDownloadIdentity)
        assertArrayEquals(payload,f.original.readBytes())
    }

}

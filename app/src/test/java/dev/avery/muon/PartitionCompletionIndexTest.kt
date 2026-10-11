@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.database.VersionTable
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import java.io.IOException
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Native SQLite guards run before CursorWindow materializes oversized fixture fields. No original
 * files or production index migrations; the public pinned DefaultDownloadIndex creates fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PartitionCompletionIndexTest {
    private val database by lazy { StandaloneDatabaseProvider(RuntimeEnvironment.getApplication()) }
    private val db get()=database.writableDatabase
    private val key="saved/fixture"
    private val table="ExoPlayerDownloadstest"
    private fun request(data:ByteArray=byteArrayOf(1))=DownloadRequest.Builder(key,
        Uri.parse("http://127.0.0.1:7814/api1/fileopus/1")).setCustomCacheKey(key).setData(data).build()
    private fun row(request:DownloadRequest=request())=Download(request,Download.STATE_COMPLETED,1,2,5,0,0)
    private fun seed(request:DownloadRequest=request()):Pair<DefaultDownloadIndex,PartitionCompletionIndex> {
        val index=DefaultDownloadIndex(database,"test");index.putDownload(row(request))
        return index to PartitionCompletionIndex(database,"test")
    }
    @After fun close()=database.close()
    private fun count()=db.rawQuery("SELECT COUNT(*) FROM $table",null).use { it.moveToFirst();it.getLong(0) }
    @Test fun actualPinnedIndexRowProducesTheSameFixedReceiptAndDoesNotRewriteAnyColumn() {
        val request=request(ByteArray(96) { it.toByte() });val (actual,lookup)=seed(request)
        assertEquals(PartitionSaveCompletion.from(requireNotNull(actual.getDownload(key))),lookup.find(key))
        assertNull(lookup.find("saved/missing"));assertEquals(request,actual.getDownload(key)?.request)
        val stopped=Download(request,Download.STATE_STOPPED,1,2,5,RETAINED_STOP_REASON,0)
        actual.putDownload(stopped);assertNull(lookup.find(key));assertEquals(stopped.stopReason,actual.getDownload(key)?.stopReason)
        assertEquals(1L,count())
    }
    @Test fun oversizedBlobAndTextRefuseInSqlWithoutMaterializingOrDeletingTheSavedRow() {
        val (actual,lookup)=seed()
        for(column in listOf("data","uri","custom_cache_key","mime_type","stream_keys","key_set_id")) {
            actual.putDownload(row())
            val value=if(column=="data" || column=="key_set_id") "zeroblob(8388608)" else "printf('%8388608s','x')"
            db.execSQL("UPDATE $table SET $column=$value WHERE id=?",arrayOf(key))
            assertThrows(IOException::class.java) { lookup.find(key) }
            db.rawQuery("SELECT length(CAST($column AS BLOB)) FROM $table WHERE id=?",arrayOf(key)).use {
                assertTrue(it.moveToFirst());assertEquals(8388608L,it.getLong(0))
            }
            assertEquals(1L,count())
        }
    }
    @Test fun supportedLargeRequestStillReturnsOnlyFixedDigestAndUnsupportedTotalParcelRefuses() {
        val large=request(ByteArray(MOVE_COMMAND_BYTES.toInt()-8192) { 1 });val (actual,lookup)=seed(large)
        val receipt=requireNotNull(lookup.find(key));assertEquals(64,receipt.requestDigest.length)
        assertEquals(PartitionSaveCompletion.from(requireNotNull(actual.getDownload(key))),receipt)
        actual.putDownload(row(request(ByteArray(MOVE_COMMAND_BYTES.toInt()))))
        assertThrows(IOException::class.java) { lookup.find(key) };assertEquals(1L,count())
    }
    @Test fun invalidScalarTypesAndOverflowingStopFailureCannotBecomeValidCompletion() {
        val (actual,lookup)=seed()
        db.execSQL("UPDATE $table SET content_length=printf('%8388608s','x')")
        assertThrows(IOException::class.java) { lookup.find(key) }
        for(column in listOf("stop_reason","failure_reason")) {
            actual.putDownload(row());db.execSQL("UPDATE $table SET $column=4294967296")
            assertThrows(IOException::class.java) { lookup.find(key) };assertEquals(1L,count())
        }
    }
    @Test fun streamDrmAndAdaptiveMimeRowsCannotEnterTheFullProgressiveRoute() {
        val (actual,lookup)=seed()
        for(update in listOf("stream_keys='0.0.0'","key_set_id=zeroblob(1)","mime_type='application/x-mpegURL'")) {
            actual.putDownload(row());db.execSQL("UPDATE $table SET $update")
            assertThrows(IOException::class.java) { lookup.find(key) };assertEquals(1L,count())
        }
    }
    @Test fun unknownVersionAndChangedSchemaRefuseWithoutMigratingOrDroppingOldRecords() {
        val (_,lookup)=seed();VersionTable.setVersion(db,VersionTable.FEATURE_OFFLINE,"test",4)
        assertThrows(IOException::class.java) { lookup.find(key) };assertEquals(1L,count())
        assertEquals(4,VersionTable.getVersion(db,VersionTable.FEATURE_OFFLINE,"test"))
        VersionTable.setVersion(db,VersionTable.FEATURE_OFFLINE,"test",3)
        db.execSQL("ALTER TABLE $table ADD COLUMN unexpected TEXT")
        assertThrows(IOException::class.java) { lookup.find(key) };assertEquals(1L,count())
        db.rawQuery("SELECT COUNT(*) FROM pragma_table_info(?)",arrayOf(table)).use { it.moveToFirst();assertEquals(16L,it.getLong(0)) }
    }
    @Test fun uninitializedIndexIsNotCreatedAndInvalidLookupNamesCannotChangeSql() {
        val lookup=PartitionCompletionIndex(database,"missing")
        fun tables()=db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE name GLOB 'ExoPlayer*'",null).use { it.moveToFirst();it.getLong(0) }
        val before=tables();assertThrows(IOException::class.java) { lookup.find(key) };assertEquals(before,tables())
        assertThrows(IllegalArgumentException::class.java) { PartitionCompletionIndex(database,"test;DROP TABLE") }
        assertThrows(IllegalArgumentException::class.java) { PartitionCompletionIndex(database,"a".repeat(65)) }
        assertThrows(IOException::class.java) { lookup.find("saved/"+"x".repeat(MIGRATION_KEY_BYTES)) }
        assertEquals(before,tables())
    }
}

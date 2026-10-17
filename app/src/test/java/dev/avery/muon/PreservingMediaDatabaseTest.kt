@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.database.sqlite.SQLiteException
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PreservingMediaDatabaseTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun request()=DownloadRequest.Builder("saved/original",Uri.parse("http://127.0.0.1:7814/audio"))
        .setCustomCacheKey("saved/original").build()
    @Test fun actualSupportedHelperCreatesAndReopensWithoutChangingExistingDownloadRecord() {
        val first=PreservingMediaDatabase(app)
        try {DefaultDownloadIndex(first).putDownload(Download(request(),Download.STATE_COMPLETED,1,2,4,0,0))}
        finally {first.close()}
        val next=PreservingMediaDatabase(app)
        try {assertEquals(1,next.readableDatabase.version);assertEquals(Download.STATE_COMPLETED,DefaultDownloadIndex(next).getDownload(request().id)!!.state)}
        finally {next.close()}
    }
    @Test fun actualNewerDatabaseCannotReachPinnedDestructiveDowngradeOrEraseOversizedSavedData() {
        val seed=StandaloneDatabaseProvider(app)
        try {
            DefaultDownloadIndex(seed).putDownload(Download(request(),Download.STATE_COMPLETED,1,2,4,0,0))
            seed.writableDatabase.execSQL("UPDATE ExoPlayerDownloads SET data=zeroblob(8388608)")
            seed.writableDatabase.version=2
        } finally {seed.close()}
        val actual=PreservingMediaDatabase(app)
        try {
            assertThrows(SQLiteException::class.java) {actual.writableDatabase}
            assertThrows(SQLiteException::class.java) {actual.readableDatabase}
        } finally {actual.close()}
        android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath(StandaloneDatabaseProvider.DATABASE_NAME).path,
            null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {db ->
            assertEquals(2,db.version)
            db.rawQuery("SELECT id,state,length(data) FROM ExoPlayerDownloads",null).use {
                assertTrue(it.moveToFirst());assertEquals(request().id,it.getString(0));assertEquals(Download.STATE_COMPLETED,it.getInt(1))
                assertEquals(8388608L,it.getLong(2));assertFalse(it.moveToNext())
            }
        }
    }
}

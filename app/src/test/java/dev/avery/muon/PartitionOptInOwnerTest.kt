@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.database.VersionTable
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import org.junit.Assert.*
import org.junit.Before
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
class PartitionOptInOwnerTest {
    @get:Rule val folders=TemporaryFolder()
    private val app get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.getSharedPreferences("partition-optin-test",Context.MODE_PRIVATE)
    @Before fun clear() {assertTrue(prefs.edit().clear().commit())}
    private fun plan():PartitionStorePlan {
        val legacy=folders.newFolder();File(legacy,"kept-audio").writeBytes(byteArrayOf(1,3,5))
        return PartitionStorePlan(PartitionRootPlan(File(folders.newFolder(),"partition"),legacy,"phone",{"phone"},PartitionShelfStart.Fresh))
    }
    private fun original(plan:PartitionStorePlan)=File(plan.phone.legacyDirectory,"kept-audio").readBytes()
    @Test fun acceptedChoiceStopsAllFutureAdmissionAndNeverCreatesANativeRootOrChangesOriginals() {
        val plan=plan();val gate=SavedStorageBarrier();val database=StandaloneDatabaseProvider(app)
        try {
            val owner=PartitionOptInOwner(gate,PartitionStartupSettings(prefs),PartitionCompletionIndex(database,"card"));var checks=0
            val choice=owner.request(plan,true) {checks++}
            assertEquals(2,checks);assertEquals(PartitionOptInPhase.RestartRequired,owner.phase);assertTrue(owner.admissionStopped)
            assertEquals(choice,PartitionStartupSettings(prefs).read());assertEquals(PartitionStartupPhase.Planned,choice.phase)
            assertFalse(gate.allowsShared);assertTrue(gate.quiescent)
            assertThrows(IOException::class.java) {gate.shared()};assertThrows(IOException::class.java) {gate.exclusive()}
            assertThrows(IOException::class.java) {owner.request(plan,true) {throw AssertionError("Replayed opt-in")}}
            assertFalse(plan.phone.directory.exists());assertArrayEquals(byteArrayOf(1,3,5),original(plan))
        } finally {database.close()}
    }
    @Test fun activeReaderOrMissingConsentRefusesWithoutWritingChoiceOrStoppingCurrentStorage() {
        val plan=plan();val gate=SavedStorageBarrier();val database=StandaloneDatabaseProvider(app)
        try {
            for(consent in listOf(false,true)) {
                val reader=gate.shared();val owner=PartitionOptInOwner(gate,PartitionStartupSettings(prefs),PartitionCompletionIndex(database,"card"))
                try {assertThrows(IOException::class.java) {owner.request(plan,consent) {throw AssertionError("Busy reader cannot reach source checks")}}}
                finally {reader.close()}
                assertEquals(PartitionOptInPhase.Refused,owner.phase);assertFalse(owner.admissionStopped);assertTrue(gate.allowsShared)
                assertEquals(StorageBackendSelection.Legacy,StorageBackendSelection.read(prefs));assertFalse(plan.phone.directory.exists())
            }
        } finally {database.close()}
    }
    @Test fun absentCardWithAnyRetainedStateRefusesBeforeChoiceWithoutHydratingOversizedPayload() {
        val plan=plan();val gate=SavedStorageBarrier();val database=StandaloneDatabaseProvider(app)
        try {
            val request=DownloadRequest.Builder("old-card",Uri.parse("http://127.0.0.1:7814/old")).setCustomCacheKey("old-card").build()
            DefaultDownloadIndex(database,"card").putDownload(Download(request,Download.STATE_STOPPED,1,2,4,7,0))
            database.writableDatabase.execSQL("UPDATE ExoPlayerDownloadscard SET data=zeroblob(8388608)")
            val owner=PartitionOptInOwner(gate,PartitionStartupSettings(prefs),PartitionCompletionIndex(database,"card"))
            assertThrows(IOException::class.java) {owner.request(plan,true) {}}
            assertEquals(PartitionOptInPhase.Refused,owner.phase);assertTrue(gate.allowsShared);assertTrue(gate.quiescent)
            assertEquals(StorageBackendSelection.Legacy,StorageBackendSelection.read(prefs));assertFalse(plan.phone.directory.exists())
            database.readableDatabase.rawQuery("SELECT state,length(data) FROM ExoPlayerDownloadscard",null).use {
                assertTrue(it.moveToFirst());assertEquals(Download.STATE_STOPPED,it.getInt(0));assertEquals(8388608,it.getInt(1))
            }
        } finally {database.close()}
    }
    @Test fun uncertainPreferenceCommitKeepsActualOwnerAndStopsAdmissionEvenIfMemoryChanged() {
        val plan=plan();val gate=SavedStorageBarrier();val database=StandaloneDatabaseProvider(app)
        val failing=object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val actual=prefs.edit()
                return object:SharedPreferences.Editor by actual {
                    override fun putString(key:String,value:String?):SharedPreferences.Editor {actual.putString(key,value);return this}
                    override fun commit():Boolean {actual.commit();return false}
                }
            }
        }
        try {
            val owner=PartitionOptInOwner(gate,PartitionStartupSettings(failing),PartitionCompletionIndex(database,"card"))
            assertThrows(IOException::class.java) {owner.request(plan,true) {}}
            assertEquals(PartitionOptInPhase.Uncertain,owner.phase);assertTrue(owner.admissionStopped);assertFalse(gate.allowsShared)
            assertTrue(gate.quiescent);assertTrue(prefs.contains(PartitionStartupSettings.KEY));assertFalse(plan.phone.directory.exists())
            assertThrows(IOException::class.java) {owner.request(plan,true) {throw AssertionError("Unknown write retried")}}
            assertArrayEquals(byteArrayOf(1,3,5),original(plan))
        } finally {database.close()}
    }
    @Test fun existingRootChangedSourceAndUnsupportedCardSchemaRemainKnownRefusals() {
        for(reason in listOf("existing","changed","schema")) {
            val plan=plan();val gate=SavedStorageBarrier();val database=StandaloneDatabaseProvider(app)
            try {
                if(reason=="existing") assertTrue(plan.phone.directory.mkdir())
                if(reason=="schema") VersionTable.setVersion(database.writableDatabase,VersionTable.FEATURE_OFFLINE,"card",77)
                val owner=PartitionOptInOwner(gate,PartitionStartupSettings(prefs),PartitionCompletionIndex(database,"card"))
                assertThrows(IOException::class.java) {owner.request(plan,true) {if(reason=="changed") throw IOException("Original owner replaced")}}
                assertEquals(PartitionOptInPhase.Refused,owner.phase);assertTrue(gate.allowsShared);assertTrue(gate.quiescent)
                assertEquals(StorageBackendSelection.Legacy,StorageBackendSelection.read(prefs));assertFalse(prefs.contains(PartitionStartupSettings.KEY))
                assertArrayEquals(byteArrayOf(1,3,5),original(plan))
            } finally {database.close()}
        }
    }
    @Test fun absentCardWithEmptySupportedIndexStillProtectsUnindexedNativeAudio() {
        val plan=plan();val gate=SavedStorageBarrier();val database=StandaloneDatabaseProvider(app)
        try {
            DefaultDownloadIndex(database,"card").getDownloads().use {assertFalse(it.moveToNext())}
            val owner=PartitionOptInOwner(gate,PartitionStartupSettings(prefs),PartitionCompletionIndex(database,"card"))
            assertThrows(IOException::class.java) {owner.request(plan,true) {}}
            assertEquals(PartitionOptInPhase.Refused,owner.phase);assertTrue(gate.allowsShared)
            assertFalse(prefs.contains(PartitionStartupSettings.KEY));assertFalse(plan.phone.directory.exists())
        } finally {database.close()}
    }
    @Test fun unknownActualCardCursorCloseQuarantinesExactOptInOwnerBeforePreferenceWrite() {
        val plan=plan();val gate=SavedStorageBarrier();val database=StandaloneDatabaseProvider(app)
        var child:java.io.Closeable?=null;var closes=0
        val index=PartitionCompletionIndex(database,"card",MigrationIoOwnership(closeHandle={
            child=it;closes++;throw IOException("Unknown opt-in card cursor close")
        }))
        val owner=PartitionOptInOwner(gate,PartitionStartupSettings(prefs),index)
        try {
            assertThrows(MigrationIoUncertain::class.java) {owner.request(plan,true) {}}
            assertEquals(PartitionOptInPhase.Uncertain,owner.phase);assertFalse(owner.admissionStopped)
            assertFalse(gate.allowsShared);assertFalse(gate.quiescent);assertEquals(1,gate.active)
            val slots=SavedStorageBarrier::class.java.getDeclaredField("slots").apply {isAccessible=true}.get(gate) as Array<*>
            val lease=slots.filterNotNull().single()
            assertSame(owner,lease.javaClass.getDeclaredField("retained").apply {isAccessible=true}.get(lease))
            assertEquals(StorageBackendSelection.Legacy,StorageBackendSelection.read(prefs));assertFalse(prefs.contains(PartitionStartupSettings.KEY))
            assertThrows(IOException::class.java) {owner.request(plan,true) {throw AssertionError("Unknown I/O retried")}}
            assertEquals(1,closes);assertFalse(plan.phone.directory.exists())
        } finally {child?.close();database.close()} // Known injected callback did not close raw cursor.
    }

}

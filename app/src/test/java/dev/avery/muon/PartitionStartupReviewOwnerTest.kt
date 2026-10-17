@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
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
class PartitionStartupReviewOwnerTest {
    @get:Rule val folders=TemporaryFolder()
    private val app get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.getSharedPreferences("partition-startup-review",Context.MODE_PRIVATE)
    @Before fun clear() {assertTrue(prefs.edit().clear().commit())}
    private inner class Fixture(card:Boolean=false) {
        val provider=StandaloneDatabaseProvider(app)
        val phone=folders.newFolder();val cardDirectory=if(card) folders.newFolder() else null
        val originals=mutableListOf<File>();val uid:Long;val databaseFile:File
        val plan:PartitionStorePlan
        init {
            fun seed(directory:File):Long {
                val cache=SimpleCache(directory,NoOpCacheEvictor(),provider)
                try {
                    cache.checkInitialization();val hole=requireNotNull(cache.startReadWrite("old",0,4))
                    try {val f=cache.startFile("old",0,4);f.writeBytes(byteArrayOf(2,4,6,8));cache.commitFile(f,4);originals+=f}
                    finally {cache.releaseHoleSpan(hole)}
                    cache.applyContentMetadataMutations("old",ContentMetadataMutations.setContentLength(ContentMetadataMutations(),4))
                    return cache.uid
                } finally {cache.release()}
            }
            uid=seed(phone);cardDirectory?.let {seed(it)}
            databaseFile=File(provider.readableDatabase.path)
            plan=PartitionStorePlan(PartitionRootPlan(File(folders.newFolder(),"partition"),phone,"phone",{"phone"},PartitionShelfStart.Fresh),
                cardDirectory?.let {PartitionRootPlan(File(folders.newFolder(),"partition"),it,"card",{"card"},PartitionShelfStart.Fresh)})
            provider.close()
        }
        fun unchanged() {
            assertFalse(plan.phone.directory.exists());plan.card?.let {assertFalse(it.directory.exists())}
            assertFalse(SimpleCache.isCacheFolderLocked(phone));cardDirectory?.let {assertFalse(SimpleCache.isCacheFolderLocked(it))}
            originals.forEach {assertArrayEquals(byteArrayOf(2,4,6,8),it.readBytes())}
        }
        fun alter(sql:String)=SQLiteDatabase.openDatabase(databaseFile.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL(sql)}
        fun chosen()=StorageBackendSelection.read(prefs)
    }
    @Test fun reviewWritesExactChoiceAfterReadOnlyChecksWithoutHydratingHugeMetadataOrOpeningNativeCaches() {
        val f=Fixture(card=true)
        f.alter("UPDATE ExoPlayerCacheIndex${java.lang.Long.toHexString(f.uid)} SET metadata=zeroblob(8388608)")
        val gate=StorageStartupAdmission();assertTrue(gate.review());val owner=PartitionStartupReviewOwner(gate)
        val choice=owner.request(f.plan,f.databaseFile,true) {PartitionStartupSettings(prefs)}
        assertEquals(PartitionOptInPhase.RestartRequired,owner.phase);assertEquals(choice,PartitionStartupSettings(prefs).read())
        assertEquals("phone",choice.phoneVolume);assertEquals("card",choice.cardVolume);assertEquals(PartitionStartupPhase.Planned,choice.phase)
        assertThrows(IOException::class.java) {gate.normal()};f.unchanged()
        SQLiteDatabase.openDatabase(f.databaseFile.path,null,SQLiteDatabase.OPEN_READONLY).use {db ->
            db.rawQuery("SELECT length(metadata) FROM ExoPlayerCacheIndex${java.lang.Long.toHexString(f.uid)}",null).use {
                assertTrue(it.moveToFirst());assertEquals(8388608,it.getInt(0))
            }
        }
    }
    @Test fun missingConsentOrNormalModeRefusesBeforeReadingOrWritingOriginalStorage() {
        val f=Fixture()
        for(review in listOf(false,true)) {
            val gate=StorageStartupAdmission();if(review) gate.review() else gate.normal()
            val owner=PartitionStartupReviewOwner(gate)
            assertThrows(IOException::class.java) {owner.request(f.plan,f.databaseFile,!review) {throw AssertionError("No settings allocated")}}
            assertEquals(PartitionOptInPhase.Refused,owner.phase);assertEquals(StorageBackendSelection.Legacy,f.chosen());f.unchanged()
        }
    }
    @Test fun emptyInitializedCardShelfStillRequiresTheOriginalCardBeforeFirstOptIn() {
        val f=Fixture();val db=StandaloneDatabaseProvider(app)
        try {DefaultDownloadIndex(db,"card").getDownloads().use {assertFalse(it.moveToNext())}} finally {db.close()}
        val gate=StorageStartupAdmission();gate.review();val owner=PartitionStartupReviewOwner(gate)
        assertThrows(IOException::class.java) {owner.request(f.plan,f.databaseFile,true) {PartitionStartupSettings(prefs)}}
        assertEquals(StorageBackendSelection.Legacy,f.chosen());assertFalse(prefs.contains(PartitionStartupSettings.KEY));f.unchanged()
    }
    @Test fun missingUidAndExistingPartitionRootDoNotGrantFreshRepairOrPreferenceMutation() {
        for(reason in listOf("uid","root")) {
            val f=Fixture();val marker=File(f.phone,java.lang.Long.toHexString(f.uid)+".uid")
            if(reason=="uid") assertTrue(marker.delete()) else assertTrue(f.plan.phone.directory.mkdir())
            val gate=StorageStartupAdmission();gate.review();val owner=PartitionStartupReviewOwner(gate)
            assertThrows(IOException::class.java) {owner.request(f.plan,f.databaseFile,true) {PartitionStartupSettings(prefs)}}
            assertEquals(StorageBackendSelection.Legacy,f.chosen());assertFalse(prefs.contains(PartitionStartupSettings.KEY))
            if(reason=="root") assertTrue(f.plan.phone.directory.delete())
            f.unchanged();if(reason=="uid") assertFalse(marker.exists())
        }
    }
    @Test fun existingNativeOwnerRefusesAndNeverReleasesAnotherOwnersCache() {
        val f=Fixture();val provider=StandaloneDatabaseProvider(app);val native=SimpleCache(f.phone,NoOpCacheEvictor(),provider)
        try {
            native.checkInitialization();val gate=StorageStartupAdmission();gate.review();val owner=PartitionStartupReviewOwner(gate)
            assertThrows(IOException::class.java) {owner.request(f.plan,f.databaseFile,true) {PartitionStartupSettings(prefs)}}
            assertTrue(SimpleCache.isCacheFolderLocked(f.phone));assertEquals(StorageBackendSelection.Legacy,f.chosen())
        } finally {native.release();provider.close()}
        f.unchanged()
    }
    @Test fun unknownPreferenceCommitRetainsActualSettingsAndBlocksNormalStartupEvenIfMemoryChanged() {
        val f=Fixture();val gate=StorageStartupAdmission();gate.review();val owner=PartitionStartupReviewOwner(gate)
        val settings=PartitionStartupSettings(object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val actual=prefs.edit()
                return object:SharedPreferences.Editor by actual {
                    override fun putString(key:String,value:String?):SharedPreferences.Editor {actual.putString(key,value);return this}
                    override fun commit():Boolean {actual.commit();return false}
                }
            }
        })
        assertThrows(IOException::class.java) {owner.request(f.plan,f.databaseFile,true) {settings}}
        assertEquals(PartitionOptInPhase.Uncertain,owner.phase);assertTrue(prefs.contains(PartitionStartupSettings.KEY))
        assertThrows(IOException::class.java) {gate.normal()}
        assertThrows(IOException::class.java) {owner.request(f.plan,f.databaseFile,true) {throw AssertionError("No retry")}}
        val startup=PartitionStartupReviewOwner::class.java.getDeclaredField("startup").apply {isAccessible=true}.get(owner) as StorageStartup<*>
        val handles=StorageStartup::class.java.getDeclaredField("handles").apply {isAccessible=true}.get(startup) as Array<*>
        assertSame(settings,handles[0]);f.unchanged()
    }
}

package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.util.Base64
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class PartitionStartupSettingsTest {
    @get:Rule val folders=TemporaryFolder()
    private val prefs get()=RuntimeEnvironment.getApplication().getSharedPreferences("partition-startup-test",Context.MODE_PRIVATE)
    private val phoneRoot by lazy {File(folders.newFolder(),"partition")}
    private val phoneLegacy by lazy {folders.newFolder()}
    private val cardRoot by lazy {File(folders.newFolder(),"partition")}
    private val cardLegacy by lazy {folders.newFolder()}
    private fun plan(start:PartitionShelfStart=PartitionShelfStart.Fresh,card:Boolean=true,phoneVolume:String="phone")=
        PartitionStorePlan(PartitionRootPlan(phoneRoot,phoneLegacy,phoneVolume,{phoneVolume},start),
            if(card) PartitionRootPlan(cardRoot,cardLegacy,"card",{"card"},start) else null)
    @Before fun clear() {assertTrue(prefs.edit().clear().commit())}
    @Test fun firstOptInPersistsBoundedAuthorityWithoutCreatingAnyRootAndRefusesReplacement() {
        val settings=PartitionStartupSettings(prefs);val plan=plan()
        assertThrows(IOException::class.java) {settings.request(plan,false)}
        assertEquals(StorageBackendSelection.Legacy,StorageBackendSelection.read(prefs));assertFalse(prefs.contains(PartitionStartupSettings.KEY))
        val choice=settings.request(plan,true)
        assertEquals(PartitionStartupPhase.Planned,choice.phase);assertEquals(PartitionShelfStart.Fresh,choice.start)
        assertEquals(choice,PartitionStartupSettings(prefs).read());assertEquals(StorageBackendSelection.PartitionV1,StorageBackendSelection.read(prefs))
        assertTrue(prefs.getString(PartitionStartupSettings.KEY,null)!!.length<=PartitionStartupSettings.MAX_CHOICE_CHARS)
        assertFalse(phoneRoot.exists());assertFalse(cardRoot.exists())
        assertThrows(IOException::class.java) {settings.request(plan,true)}
        assertThrows(IOException::class.java) {settings.prepared(choice)};assertEquals(choice,settings.read())
    }
    @Test fun interruptedFreshNeedsExplicitResumeAndReadyRestartsNeverRetryFresh() {
        val settings=PartitionStartupSettings(prefs);val planned=settings.request(plan(),true)
        val attempt=settings.begin(planned,plan());assertEquals(PartitionStartupPhase.Preparing,attempt.phase)
        val next=PartitionStartupSettings(prefs);assertEquals(attempt,next.read())
        assertThrows(IOException::class.java) {next.begin(attempt,plan())}
        assertThrows(IOException::class.java) {next.reviewResume(attempt,false)};assertEquals(attempt,next.read())
        val reviewed=next.reviewResume(attempt,true);assertEquals(PartitionShelfStart.ReviewedSetup,reviewed.start)
        val resumed=next.begin(reviewed,plan(PartitionShelfStart.ReviewedSetup));val ready=next.prepared(resumed)
        assertEquals(PartitionStartupPhase.Ready,ready.phase);assertEquals(PartitionShelfStart.Resume,ready.start)
        assertEquals(ready,PartitionStartupSettings(prefs).begin(ready,plan(PartitionShelfStart.Resume)))
        assertThrows(IOException::class.java) {next.begin(ready,plan())}
        assertFalse(phoneRoot.exists());assertFalse(cardRoot.exists())
    }
    @Test fun staleChoiceChangedVolumeOrMissingCardRefuseBeforeChangingAuthority() {
        val settings=PartitionStartupSettings(prefs);val choice=settings.request(plan(),true)
        assertThrows(IOException::class.java) {settings.begin(choice.copy(token="00000000-0000-0000-0000-000000000001"),plan())}
        assertThrows(IOException::class.java) {settings.begin(choice,plan(phoneVolume="replacement"))}
        assertThrows(IOException::class.java) {settings.begin(choice,plan(card=false))}
        assertEquals(choice,settings.read());assertFalse(phoneRoot.exists());assertFalse(cardRoot.exists())
        prefs.edit().putString(StorageBackendSelection.KEY,"legacy").commit()
        assertThrows(IOException::class.java) {settings.begin(choice,plan())}
        assertEquals("legacy",prefs.getString(StorageBackendSelection.KEY,null))
    }
    @Test fun malformedOversizedWrongTypeVersionAndTrailingDataNeverBecomeAStartupDefault() {
        val settings=PartitionStartupSettings(prefs);settings.request(plan(card=false),true)
        val valid=prefs.getString(PartitionStartupSettings.KEY,null)!!;val raw=Base64.getDecoder().decode(valid)
        val future=raw.clone().also {it[3]=77};val invalidStart=raw.clone().also {it[it.lastIndex]=127}
        for(value in listOf("bad", "x".repeat(PartitionStartupSettings.MAX_CHOICE_CHARS+1),
            Base64.getEncoder().encodeToString(future),Base64.getEncoder().encodeToString(invalidStart),
            Base64.getEncoder().encodeToString(raw+byteArrayOf(0)))) {
            prefs.edit().putString(PartitionStartupSettings.KEY,value).commit()
            assertThrows(IOException::class.java) {PartitionStartupSettings(prefs).read()}
            assertEquals(value,prefs.getString(PartitionStartupSettings.KEY,null))
        }
        prefs.edit().putInt(PartitionStartupSettings.KEY,1).commit()
        assertThrows(IOException::class.java) {settings.read()};assertEquals(1,prefs.getInt(PartitionStartupSettings.KEY,0))
        prefs.edit().remove(PartitionStartupSettings.KEY).commit()
        assertThrows(IOException::class.java) {settings.read()}
        assertEquals(StorageBackendSelection.PartitionV1,StorageBackendSelection.read(prefs));assertFalse(phoneRoot.exists())
    }
    @Test fun unconfirmedCommitPoisonsActualSettingsOwnerEvenIfMemoryAlreadyChanged() {
        val failing=object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val actual=prefs.edit()
                return object:SharedPreferences.Editor by actual {
                    override fun putString(key:String,value:String?):SharedPreferences.Editor {actual.putString(key,value);return this}
                    override fun commit():Boolean {actual.commit();return false}
                }
            }
        }
        val settings=PartitionStartupSettings(failing)
        assertThrows(IOException::class.java) {settings.request(plan(card=false),true)}
        assertTrue(prefs.contains(PartitionStartupSettings.KEY)) // Reported success, not a memory map, is authority.
        assertThrows(IOException::class.java) {settings.read()}
        assertThrows(IOException::class.java) {settings.request(plan(card=false),true)}
        assertFalse(phoneRoot.exists())
    }
    @Test fun onlyReadyReceiptMayKeepAnAbsentCardWithoutRewritingItsAuthority() {
        val settings=PartitionStartupSettings(prefs);val selected=settings.request(plan(),true)
        val missing=plan(PartitionShelfStart.Resume,card=false).copy(missingCardVolume="card")
        assertThrows(IOException::class.java) {settings.begin(selected,missing)}
        val preparing=settings.begin(selected,plan())
        assertThrows(IOException::class.java) {settings.begin(preparing,missing)}
        val ready=settings.prepared(preparing);val bytes=prefs.getString(PartitionStartupSettings.KEY,null)
        assertEquals(ready,settings.begin(ready,missing));assertEquals(ready,settings.prepared(ready))
        assertEquals(bytes,prefs.getString(PartitionStartupSettings.KEY,null));assertEquals("card",settings.read().cardVolume)
        assertThrows(IOException::class.java) {settings.begin(ready,missing.copy(missingCardVolume="replacement"))}
        assertThrows(IOException::class.java) {settings.begin(ready,missing.copy(card=plan(PartitionShelfStart.Resume).card))}
        assertThrows(IOException::class.java) {missing.copy(phone=missing.phone.copy(start=PartitionShelfStart.Fresh)).validate()}
        assertFalse(phoneRoot.exists());assertFalse(cardRoot.exists())
    }

}

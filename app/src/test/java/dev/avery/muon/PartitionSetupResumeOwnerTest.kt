package dev.avery.muon

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class PartitionSetupResumeOwnerTest {
    @get:Rule val folders=TemporaryFolder()
    private val prefs get()=RuntimeEnvironment.getApplication().getSharedPreferences("setup-review-test",Context.MODE_PRIVATE)
    @Before fun clear() {assertTrue(prefs.edit().clear().commit())}
    private fun plan()=PartitionStorePlan(PartitionRootPlan(File(folders.newFolder(),"partition"),folders.newFolder(),"phone",{"phone"},PartitionShelfStart.Fresh))
    private fun startup(owner:PartitionSetupResumeOwner)=PartitionSetupResumeOwner::class.java
        .getDeclaredField("startup").apply {isAccessible=true}.get(owner) as StorageStartup<*>
    @Test fun reviewedInterruptedChoicePreservesIdentityAndSavesResumeWithoutOpeningAnyRoot() {
        val plan=plan();val settings=PartitionStartupSettings(prefs);val pending=settings.begin(settings.request(plan,true),plan)
        val owner=PartitionSetupResumeOwner();val reviewed=owner.request(true) {settings}
        assertEquals(pending.token,reviewed.token);assertEquals(pending.phoneVolume,reviewed.phoneVolume)
        assertEquals(PartitionStartupPhase.Planned,reviewed.phase);assertEquals(PartitionShelfStart.ReviewedSetup,reviewed.start)
        assertEquals(reviewed,settings.read());assertEquals(PartitionSetupReviewPhase.RestartRequired,owner.phase)
        assertEquals(1,startup(owner).retained);assertFalse(plan.phone.directory.exists())
        assertThrows(IOException::class.java) {owner.request(true) {throw AssertionError("Review replayed")}}
    }
    @Test fun reviewRequiresConsentAndDoesNotAdoptPlannedFreshOrRewriteReady() {
        val noConsent=PartitionSetupResumeOwner()
        assertThrows(IOException::class.java) {noConsent.request(false) {throw AssertionError("No consent")}}
        assertEquals(0,startup(noConsent).retained);assertEquals(PartitionSetupReviewPhase.Refused,noConsent.phase)
        val plan=plan();val settings=PartitionStartupSettings(prefs);val planned=settings.request(plan,true)
        val invalid=PartitionSetupResumeOwner()
        assertThrows(IOException::class.java) {invalid.request(true) {settings}}
        assertEquals(planned,settings.read());assertFalse(plan.phone.directory.exists())
        val ready=settings.prepared(settings.begin(planned,plan));val another=PartitionSetupResumeOwner()
        assertThrows(IOException::class.java) {another.request(true) {settings}}
        assertEquals(ready,settings.read());assertFalse(plan.phone.directory.exists())
    }
    @Test fun unknownCommitRetainsActualSettingsBeforeWriteAndDoesNotRetryAfterMemoryChange() {
        val plan=plan();val original=PartitionStartupSettings(prefs);original.begin(original.request(plan,true),plan)
        val failing=object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val actual=prefs.edit()
                return object:SharedPreferences.Editor by actual {
                    override fun putString(key:String,value:String?):SharedPreferences.Editor {actual.putString(key,value);return this}
                    override fun commit():Boolean {actual.commit();return false}
                }
            }
        }
        val settings=PartitionStartupSettings(failing);val owner=PartitionSetupResumeOwner()
        assertThrows(IOException::class.java) {owner.request(true) {settings}}
        assertEquals(PartitionSetupReviewPhase.Uncertain,owner.phase);assertEquals(1,startup(owner).retained)
        val handles=StorageStartup::class.java.getDeclaredField("handles").apply {isAccessible=true}.get(startup(owner)) as Array<*>
        assertSame(settings,handles[0]);assertThrows(IOException::class.java) {settings.read()}
        assertThrows(IOException::class.java) {owner.request(true) {throw AssertionError("Unknown write retried")}}
        assertEquals(PartitionShelfStart.ReviewedSetup,PartitionStartupSettings(prefs).read().start);assertFalse(plan.phone.directory.exists())
    }
}

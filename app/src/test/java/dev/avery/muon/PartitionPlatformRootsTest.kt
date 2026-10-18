package dev.avery.muon

import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class PartitionPlatformRootsTest {
    @get:Rule val folders=TemporaryFolder()
    private fun <T> worker(read:()->T):T {
        val executor=Executors.newSingleThreadExecutor()
        try {return try {executor.submit<T> {read()}.get(10,TimeUnit.SECONDS)}
            catch(failure:java.util.concurrent.ExecutionException) {throw requireNotNull(failure.cause)}}
        finally {executor.shutdownNow();check(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
    private inner class Fixture {
        val phone=folders.newFolder();val primary=folders.newFolder();val card=folders.newFolder();val other=folders.newFolder()
        var phoneId="private:fixture";var cardId:String?="removable:fixture";var otherId:String?="removable:other"
        var dirs:Array<File?> = arrayOf(primary,card)
        val resolver=PartitionPlatformRoots({phone},{dirs},{phoneId},{if(it==card) cardId else if(it==other) otherId else null})
        fun choice(plan:PartitionStorePlan,start:PartitionShelfStart=PartitionShelfStart.Resume)=PartitionStartupChoice(
            UUID.randomUUID().toString(),PartitionStartupPhase.Ready,plan.phone.volume,plan.card?.volume,start)
    }
    @Test fun resolvesOnlyTrustedFoldersAndRechecksCurrentVolumeWithoutCreatingRoots() {
        val f=Fixture();assertThrows(IllegalStateException::class.java) {f.resolver.fresh(f.card)}
        val plan=worker {f.resolver.fresh(f.card)}
        assertEquals(File(f.phone,"downloads-partition-v1"),plan.phone.directory)
        assertEquals(File(f.card,"downloads"),plan.card!!.legacyDirectory)
        assertFalse(plan.phone.directory.exists());assertFalse(plan.card!!.directory.exists())
        assertEquals(PartitionShelfStart.Fresh,plan.phone.start);assertEquals(f.cardId,plan.card!!.currentVolume())
        f.cardId="removable:replacement";assertThrows(IOException::class.java) {plan.validate()}
        f.cardId=null;assertNull(plan.card!!.currentVolume())
        assertFalse(plan.phone.directory.exists());assertFalse(plan.card!!.directory.exists())
    }
    @Test fun diagnosticTimingRetainsLiveRootRefusalEvenWhenTheSinkFails() {
        val f=Fixture();var rootReads=0;var volumeReads=0;var routeReads=0
        val timing=requireNotNull(StorageRootTiming.begin(true,java.util.concurrent.atomic.AtomicInteger(32),{0L},{e ->
            when(e.phase) {
                StorageRootTiming.Phase.MIXED_ROUTE ->routeReads++
                StorageRootTiming.Phase.PHONE_ROOT,StorageRootTiming.Phase.CARD_ROOT ->rootReads++
                StorageRootTiming.Phase.PHONE_VOLUME,StorageRootTiming.Phase.CARD_VOLUME,StorageRootTiming.Phase.LEGACY_CARD_VOLUME,StorageRootTiming.Phase.OWNED_CARD_VOLUME ->volumeReads++
            }
            throw IOException("Diagnostic sink unavailable")
        }))
        val resolver=PartitionPlatformRoots({f.phone},{f.dirs},{f.phoneId},{f.cardId},timing)
        val plan=worker {resolver.fresh(f.card)}
        assertEquals(2,rootReads);assertEquals(2,volumeReads);assertEquals(0,routeReads)
        f.cardId="removable:changed";assertThrows(IOException::class.java) {plan.validate()}
        f.cardId=null;assertNull(plan.card!!.currentVolume())
        assertFalse(plan.phone.directory.exists());assertFalse(plan.card!!.directory.exists())
    }

    @Test fun resumeUsesExactUuidAcrossPlatformOrderingAndKeepsMissingCardIdentity() {
        val f=Fixture();val selected=worker {f.resolver.fresh(f.card)};val choice=f.choice(selected)
        f.dirs=arrayOf(f.primary,f.other,f.card)
        val resumed=worker {f.resolver.resume(choice)};assertEquals(selected.card!!.directory,resumed.card!!.directory)
        assertEquals(PartitionShelfStart.Resume,resumed.phone.start)
        f.dirs=arrayOf(f.primary,f.other)
        val absent=worker {f.resolver.resume(choice)}
        assertNull(absent.card);assertEquals("removable:fixture",absent.missingCardVolume)
        assertEquals("removable:fixture",choice.cardVolume)
        f.dirs=arrayOf(f.primary,f.card);f.cardId="removable:replacement"
        val replacement=worker {f.resolver.resume(choice)}
        assertNull(replacement.card);assertEquals(choice.cardVolume,replacement.missingCardVolume)
        assertFalse(File(f.card,"downloads-partition-v1").exists())
        f.cardId=choice.cardVolume
        val returned=worker {f.resolver.resume(choice)}
        assertNotNull(returned.card);assertNull(returned.missingCardVolume)
    }
    @Test fun freshRefusesNewUnownedCardAndChangedOrAmbiguousSelection() {
        val f=Fixture()
        assertThrows(IOException::class.java) {worker {f.resolver.fresh(null)}}
        assertThrows(IOException::class.java) {worker {f.resolver.fresh(f.other)}}
        f.dirs=arrayOf(f.primary,f.card,f.other);f.otherId=f.cardId
        assertThrows(IOException::class.java) {worker {f.resolver.fresh(f.card)}}
        f.dirs=arrayOf(f.primary,f.card,f.card)
        assertThrows(IOException::class.java) {worker {f.resolver.fresh(f.card)}}
    }
    @Test fun phoneOnlyReceiptDoesNotAdoptNewCardAndChangedPrivateIdentityRefuses() {
        val f=Fixture();f.dirs=arrayOf(f.primary)
        val selected=worker {f.resolver.fresh(null)};assertNull(selected.card)
        val choice=f.choice(selected);f.dirs=arrayOf(f.primary,f.card)
        assertNull(worker {f.resolver.resume(choice)}.card)
        f.phoneId="private:replacement"
        assertThrows(IOException::class.java) {worker {f.resolver.resume(choice)}}
    }
    @Test fun pathAliasesInvalidVolumeAndExcessivePlatformFoldersRefuseWithoutAllocation() {
        val f=Fixture()
        val outside=folders.newFolder();java.nio.file.Files.createSymbolicLink(File(f.phone,"downloads-partition-v1").toPath(),outside.toPath())
        assertThrows(IOException::class.java) {worker {f.resolver.fresh(f.card)}}
        assertTrue(outside.listFiles()!!.isEmpty());assertTrue(File(f.phone,"downloads-partition-v1").delete())
        f.phoneId="\u0000bad";assertThrows(IOException::class.java) {worker {f.resolver.fresh(f.card)}}
        f.phoneId="private:fixture";f.dirs=Array(9) {null}
        assertThrows(IOException::class.java) {worker {f.resolver.fresh(null)}}
        assertFalse(File(f.phone,"downloads-partition-v1").exists())
    }
    @Test fun absentCardCannotCompleteFreshOrInterruptedSetupAndAmbiguityStillRefuses() {
        val f=Fixture();val plan=worker {f.resolver.fresh(f.card)};val ready=f.choice(plan)
        f.dirs=arrayOf(f.primary)
        for(choice in listOf(ready.copy(phase=PartitionStartupPhase.Planned,start=PartitionShelfStart.Fresh),
            ready.copy(phase=PartitionStartupPhase.Preparing),ready.copy(phase=PartitionStartupPhase.Planned)))
            assertThrows(IOException::class.java) {worker {f.resolver.resume(choice)}}
        f.dirs=arrayOf(f.primary,f.card,f.other);f.otherId=f.cardId
        assertThrows(IOException::class.java) {worker {f.resolver.resume(ready)}}
        assertFalse(File(f.phone,"downloads-partition-v1").exists())
    }

    @Test fun legacyObservationRereadsVolumeAndUsesSeparateTimingWithoutGrantingRootAuthority() {
        val f=Fixture();var calls=0
        val events=mutableListOf<StorageRootTiming.Event>()
        val timing=requireNotNull(StorageRootTiming.begin(true,java.util.concurrent.atomic.AtomicInteger(32),{0L},events::add))
        val resolver=PartitionPlatformRoots({f.phone},{f.dirs},{f.phoneId},{calls++;f.cardId},timing)
        val plan=worker {resolver.fresh(f.card)};val card=requireNotNull(plan.card)
        val before=calls
        repeat(3) {assertEquals(f.cardId,card.legacyCurrentVolume())}
        assertEquals(before+3,calls)
        assertEquals(1,events.count {it.phase==StorageRootTiming.Phase.LEGACY_CARD_VOLUME})
        f.cardId=null;assertNull(card.legacyCurrentVolume())
        f.cardId="removable:changed";assertEquals(f.cardId,card.legacyCurrentVolume())
        assertThrows(IOException::class.java) {plan.validate()}
        f.cardId="invalid\u0000";assertNull(card.legacyCurrentVolume())
        assertFalse(card.directory.exists())
    }
    @Test fun customRootPlansRetainFullVolumeCallbackUnlessActualResolverSuppliesLegacyObservation() {
        val f=Fixture();var calls=0
        val root=PartitionRootPlan(File(f.phone,"partition"),File(f.phone,"downloads"),f.phoneId,
            {calls++;f.phoneId},PartitionShelfStart.Fresh)
        assertEquals(f.phoneId,root.legacyCurrentVolume());assertEquals(1,calls)
        f.phoneId="changed";assertEquals(f.phoneId,root.legacyCurrentVolume());assertEquals(2,calls)
        val copied=root.copy(currentVolume={"copied-callback"})
        assertEquals("copied-callback",copied.legacyCurrentVolume());assertEquals(2,calls)
    }

    @Test fun ownedObservationIsFreshAndSeparateWhileGenericPlansFollowCurrentCallback() {
        val f=Fixture();var calls=0
        val events=mutableListOf<StorageRootTiming.Event>()
        val timing=requireNotNull(StorageRootTiming.begin(true,java.util.concurrent.atomic.AtomicInteger(32),{0L},events::add))
        val resolver=PartitionPlatformRoots({f.phone},{f.dirs},{f.phoneId},{calls++;f.cardId},timing)
        val plan=worker {resolver.fresh(f.card)};val card=requireNotNull(plan.card)
        val before=calls
        repeat(3) {assertEquals(f.cardId,card.ownedCurrentVolume())}
        assertEquals(before+3,calls)
        assertEquals(1,events.count {it.phase==StorageRootTiming.Phase.OWNED_CARD_VOLUME})
        f.cardId=null;assertNull(card.ownedCurrentVolume())
        f.cardId="removable:replacement";assertEquals(f.cardId,card.ownedCurrentVolume())
        f.cardId="invalid\u0000";assertNull(card.ownedCurrentVolume())
        assertFalse(card.directory.exists()) // Volume observation creates/adopts no root.
        var genericCalls=0
        val generic=PartitionRootPlan(f.phone,f.card,"fixture",{genericCalls++;"fixture"},PartitionShelfStart.Resume)
        assertEquals("fixture",generic.ownedCurrentVolume());assertEquals(1,genericCalls)
        val copied=generic.copy(currentVolume={"changed"})
        assertEquals("changed",copied.ownedCurrentVolume());assertEquals(1,genericCalls)
    }

}

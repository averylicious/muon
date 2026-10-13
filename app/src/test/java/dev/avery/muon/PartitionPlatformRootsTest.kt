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
    @Test fun resumeUsesExactUuidAcrossPlatformOrderingAndKeepsMissingCardIdentity() {
        val f=Fixture();val selected=worker {f.resolver.fresh(f.card)};val choice=f.choice(selected)
        f.dirs=arrayOf(f.primary,f.other,f.card)
        val resumed=worker {f.resolver.resume(choice)};assertEquals(selected.card!!.directory,resumed.card!!.directory)
        assertEquals(PartitionShelfStart.Resume,resumed.phone.start)
        f.dirs=arrayOf(f.primary,f.other)
        assertThrows(IOException::class.java) {worker {f.resolver.resume(choice)}}
        assertEquals("removable:fixture",choice.cardVolume)
        f.dirs=arrayOf(f.primary,f.card);f.cardId="removable:replacement"
        assertThrows(IOException::class.java) {worker {f.resolver.resume(choice)}}
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
}

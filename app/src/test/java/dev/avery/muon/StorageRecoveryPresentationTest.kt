package dev.avery.muon

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageRecoveryPresentationTest {
    private fun empty()=storageRecoveryPresentation(pageLoaded=true,firstPage=true,hasEntries=false,hasMore=false)

    @Test fun firstVisitStaysNeutralUntilAnExhaustedSuccessfulPageProvesAbsence() {
        assertEquals(StorageRecoveryPresentation.Checking,
            storageRecoveryPresentation(pageLoaded=false,firstPage=true,hasEntries=false,hasMore=false))
        assertEquals(StorageRecoveryPresentation.Empty,empty())
    }

    @Test fun filteredEmptyPagesAndLaterPagesKeepRecoveryAndNavigationReachable() {
        assertEquals(StorageRecoveryPresentation.Review,
            storageRecoveryPresentation(pageLoaded=true,firstPage=true,hasEntries=false,hasMore=true))
        assertEquals(StorageRecoveryPresentation.Review,
            storageRecoveryPresentation(pageLoaded=true,firstPage=false,hasEntries=false,hasMore=false))
        assertEquals(StorageRecoveryPresentation.Review,
            storageRecoveryPresentation(pageLoaded=false,firstPage=false,hasEntries=false,hasMore=false))
        assertEquals(StorageRecoveryPresentation.Review,
            storageRecoveryPresentation(pageLoaded=true,firstPage=true,hasEntries=true,hasMore=false))
    }

    @Test fun emptyPageCannotHideActiveWorkProgressOrAnOpenReview() {
        assertEquals(StorageRecoveryPresentation.Working,
            storageRecoveryPresentation(true,true,false,false,working=true))
        assertEquals(StorageRecoveryPresentation.Review,
            storageRecoveryPresentation(true,true,false,false,progress=true))
        assertEquals(StorageRecoveryPresentation.Review,
            storageRecoveryPresentation(true,true,false,false,selected=true))
    }

    @Test fun failedReadsAdmissionAndUncertainOwnershipNeverBecomeEmpty() {
        for(loaded in listOf(false,true)) {
            assertEquals(StorageRecoveryPresentation.Unavailable,
                storageRecoveryPresentation(loaded,true,false,false,unavailable=true))
            assertEquals(StorageRecoveryPresentation.Unavailable,
                storageRecoveryPresentation(loaded,true,false,false,admissionFailed=true))
            assertEquals(StorageRecoveryPresentation.Uncertain,
                storageRecoveryPresentation(loaded,true,false,false,uncertain=true))
        }
    }

    @Test fun oneBannerKeepsAttentionVisibleWhileOtherPagesAreEmptyOrLoading() {
        val quiet=listOf(StorageRecoveryPresentation.Empty,StorageRecoveryPresentation.Checking)
        assertEquals(StorageRecoveryPresentation.Checking,storageRecoveryAttention(quiet))
        for(attention in listOf(StorageRecoveryPresentation.Review,StorageRecoveryPresentation.Working,
            StorageRecoveryPresentation.Unavailable,StorageRecoveryPresentation.Uncertain)) {
            assertEquals(attention,storageRecoveryAttention(quiet+attention))
            assertEquals(attention,storageRecoveryAttention(listOf(attention)+quiet))
        }
        assertEquals(StorageRecoveryPresentation.Uncertain,storageRecoveryAttention(StorageRecoveryPresentation.entries))
        assertEquals(StorageRecoveryPresentation.Empty,storageRecoveryAttention(listOf(empty(),empty())))
    }

    @Test fun workBoundaryReturnsToCheckingUntilFreshAbsenceIsKnown() {
        assertEquals(StorageRecoveryPresentation.Working,
            storageRecoveryPresentation(false,true,false,false,working=true))
        assertEquals(StorageRecoveryPresentation.Checking,
            storageRecoveryPresentation(false,true,false,false))
        assertEquals(StorageRecoveryPresentation.Empty,empty())
    }

    @Test fun completedReadyDoesNotLeaveAttentionButActiveAndNonSuccessOutcomesDo() {
        assertEquals(false,storageMigrationNeedsReview(null,false))
        assertEquals(false,storageMigrationNeedsReview(MigrationWorkPhase.Ready,false))
        assertEquals(true,storageMigrationNeedsReview(MigrationWorkPhase.Ready,true))
        for(phase in MigrationWorkPhase.entries.filter {it!=MigrationWorkPhase.Ready})
            assertEquals(true,storageMigrationNeedsReview(phase,false))
        assertEquals(StorageRecoveryPresentation.Empty,
            storageRecoveryPresentation(true,true,false,false,
                progress=storageMigrationNeedsReview(MigrationWorkPhase.Ready,false)))
        assertEquals(StorageRecoveryPresentation.Review,
            storageRecoveryPresentation(true,true,false,false,
                progress=storageMigrationNeedsReview(MigrationWorkPhase.Cancelled,false)))
    }

    @Test fun oneActiveOptimizationDoesNotExposeUnrelatedEmptyOrUnknownPanels() {
        val unknown=storageRecoveryPresentation(false,true,false,false)
        val active=storageRecoveryPresentation(false,true,false,false,working=true)
        val sections=listOf(empty(),empty(),empty(),empty(),unknown,active)
        assertEquals(StorageRecoveryPresentation.Working,storageRecoveryAttention(sections,working=true))
        assertEquals(listOf(active),sections.filter(::storageRecoveryDetailsVisible))
        assertEquals(false,storageRecoveryDetailsVisible(unknown))
        // Once global work drains, an unrelated unknown first page remains a neutral check.
        assertEquals(StorageRecoveryPresentation.Checking,storageRecoveryAttention(listOf(empty(),unknown)))
    }

    @Test fun globalWorkKeepsOnlyTheBannerWhileOwnPendingAndErrorsRemainReviewable() {
        val unknown=storageRecoveryPresentation(false,true,false,false)
        assertEquals(StorageRecoveryPresentation.Working,
            storageRecoveryAttention(listOf(empty(),unknown),working=true))
        assertEquals(0,listOf(empty(),unknown).count(::storageRecoveryDetailsVisible))
        val pending=storageRecoveryPresentation(true,true,false,true)
        val unavailable=storageRecoveryPresentation(false,true,false,false,unavailable=true)
        assertEquals(true,storageRecoveryDetailsVisible(pending))
        assertEquals(true,storageRecoveryDetailsVisible(unavailable))
        assertEquals(StorageRecoveryPresentation.Unavailable,
            storageRecoveryAttention(listOf(empty(),pending,unavailable),working=true))
    }
}

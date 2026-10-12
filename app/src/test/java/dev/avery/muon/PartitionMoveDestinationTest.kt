@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class PartitionMoveDestinationTest {
    private val payload=byteArrayOf(2,4,6,8)
    private val spec=DataSpec.Builder().setUri("muon-saved:test").setKey("saved/digest").setLength(4).build()
    private fun <T> worker(work:()->T):T {
        val executor=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {return try {executor.submit<T> {work()}.get(10,TimeUnit.SECONDS)}
            catch(error:java.util.concurrent.ExecutionException) {throw requireNotNull(error.cause)} }
        finally {executor.shutdownNow(); assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS))}
    }
    private fun destination()=PartitionMoveDestination("phone","(dev=1,ino=2)",PartitionSaveTicket(
        CachePartitionAllocation("saved/digest","00000000-0000-0000-0000-000000000001"),
        "00000000-0000-0000-0000-000000000002"),3,"a".repeat(64),4,"b".repeat(64),"c".repeat(64))
    @Test fun boundedDestinationRoundtripAndMalformedOrNonfreshIdentityRefusal() {
        val expected=destination(); val bytes=ByteArrayOutputStream()
        DataOutputStream(bytes).use {expected.write(it)}
        assertEquals(expected,DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {PartitionMoveDestination.read(it)})
        for(wrong in listOf(expected.copy(volume=""),expected.copy(uid=-1),expected.copy(length=0),
            expected.copy(audioDigest="C".repeat(64)),expected.copy(rootIdentity="x".repeat(257)),
            expected.copy(ticket=expected.ticket.copy(allocation=expected.ticket.allocation.copy(key="old"))),
            expected.copy(ticket=expected.ticket.copy(token="bad"))))
            assertThrows(IOException::class.java) {wrong.validate()}
    }
    @Test fun metadataDigestIsOrderedAndPreservesUnknownFieldsWithoutAmbiguousConcatenation() {
        fun metadata(m:ContentMetadataMutations)=DefaultContentMetadata.EMPTY.copyWithMutationsApplied(m)
        val a=metadata(ContentMetadataMutations().set("one",byteArrayOf(1)).set("two",byteArrayOf(2)))
        val b=metadata(ContentMetadataMutations().set("two",byteArrayOf(2)).set("one",byteArrayOf(1)))
        assertEquals(partitionMoveMetadataDigest(a),partitionMoveMetadataDigest(b))
        assertNotEquals(partitionMoveMetadataDigest(a),partitionMoveMetadataDigest(a.copyWithMutationsApplied(
            ContentMetadataMutations().set("unknown",byteArrayOf(3)))))
        assertThrows(IOException::class.java) {partitionMoveMetadataDigest(metadata(ContentMetadataMutations()
            .set("huge",ByteArray(MIGRATION_METADATA_BYTES+1))))}
    }
    @Test fun savedDigestReturnsOnlyAfterActualCloseAndRejectsShortSource() {
        var closes=0; val raw=ByteArrayDataSource(payload)
        val source=DataSource.Factory {object:DataSource by raw {override fun close() {closes++; raw.close()}}}
        val actual=worker {SavedAudioDigest().read(spec,source) {}}
        assertEquals(MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {"%02x".format(it)},actual)
        assertEquals(1,closes)
        assertThrows(IOException::class.java) {worker {SavedAudioDigest().read(spec,DataSource.Factory {ByteArrayDataSource(byteArrayOf(2))}) {}}}
    }
    @Test fun savedDigestUnknownCloseRetainsActualSourceAndRefusesSecondAttempt() {
        val raw=ByteArrayDataSource(payload); var closes=0
        val actual=object:DataSource by raw {override fun close():Unit {closes++; throw IOException("Unknown digest reader close")}}
        val digest=SavedAudioDigest()
        try {
            assertThrows(PartitionOwnershipUncertain::class.java) {worker {digest.read(spec,DataSource.Factory {actual}) {}}}
            assertSame(actual,SavedAudioDigest::class.java.getDeclaredField("source").apply {isAccessible=true}.get(digest))
            assertEquals(1,closes)
            assertThrows(IOException::class.java) {worker {digest.read(spec,DataSource.Factory {actual}) {}}}
            assertEquals(1,closes)
        } finally {raw.close()}
    }
}

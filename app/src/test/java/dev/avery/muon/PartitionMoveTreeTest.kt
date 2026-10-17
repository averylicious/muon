package dev.avery.muon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartitionMoveTreeTest {
    @get:Rule val folders=TemporaryFolder()
    private fun source():File=File(folders.newFolder(),java.util.UUID.randomUUID().toString()).also {root ->
        for(dir in listOf("bytes/0","index","metadata")) assertTrue(File(root,dir).mkdirs())
        File(root,"bytes/a.uid").writeBytes(byteArrayOf())
        File(root,"bytes/0/0.0.1.v3.exo").writeBytes(byteArrayOf(1,2))
        File(root,"bytes/0/0.2.1.v3.exo").writeBytes(byteArrayOf(3,4))
        File(root,"index/native-v1.db").writeBytes(byteArrayOf(5,6))
        File(root,"metadata/content-v1.db").writeBytes(byteArrayOf(7,8))
    }
    private fun capture(root:File)=PartitionMoveTree.capture(root,10,MigrationIoOwnership()) {}
    private fun persisted(tree:PartitionMoveTree):PartitionMoveTree {
        val bytes=ByteArrayOutputStream(); DataOutputStream(bytes).use {tree.write(it)}
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {PartitionMoveTree.read(it)}
    }
    @Test fun capturedAudioMustStillMatchTheBoundWholeSongBeforePending() {
        val root=source(); val tree=capture(root)
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1,2,3,4)).joinToString("") {"%02x".format(it)}
        tree.verifyAudio(root,4,digest,MigrationIoOwnership()) {}
        assertThrows(IOException::class.java) {tree.verifyAudio(root,4,"a".repeat(64),MigrationIoOwnership()) {}}
        assertThrows(IOException::class.java) {tree.requireAudioLength(5)}
        assertTrue(tree.files.all {File(root,it.path).isFile})
    }
    @Test fun persistedExactTreeRetiresAllFilesAndDedicatedRootWithoutNativeReopen() {
        val root=source(); val tree=persisted(capture(root)); var checked=0
        PartitionMoveTreeRemoval().run(tree,root) {checked++}
        assertFalse(root.exists()); assertTrue(root.parentFile.isDirectory); assertTrue(checked>tree.files.size)
    }
    @Test fun firstUnlinkHasOneFullAudioPassAndNoLiveReaderRatherThanDuplicatedPreflight() {
        val root=source();val tree=persisted(capture(root))
        val spans=tree.files.filter {it.path.endsWith(".v3.exo")}.map {File(root,it.path)}.toSet()
        val reads=mutableMapOf<File,Int>();var unlinks=0;var destinationChecks=0
        val ownership=MigrationIoOwnership(inputs={file ->
            java.io.RandomAccessFile(file,"r").also {
                if(file in spans) reads[file]=(reads[file] ?: 0)+1
            }
        })
        val removal=PartitionMoveTreeRemoval(ownership,unlink={file ->
            assertTrue("All real file/directory readers must close before unlink",ownership.quiescent)
            if(unlinks==0) {
                assertTrue(file in spans);assertEquals(spans,reads.keys)
                assertTrue("Every audio span is hashed once before the first unlink",reads.values.all {it==1})
                assertTrue("Destination must be checked before source mutation",destinationChecks>0)
            }
            Files.delete(file.toPath());unlinks++
        })
        removal.runMoved(tree,root,{}, {destinationChecks++})
        assertFalse(root.exists());assertTrue(unlinks>tree.files.size);assertTrue(ownership.quiescent)
    }
    @Test fun interruptedAfterActualFirstUnlinkResumesFromPersistedIdentity() {
        val root=source(); val tree=persisted(capture(root)); val first=File(root,"bytes/0/0.0.1.v3.exo")
        assertThrows(InterruptedIOException::class.java) {PartitionMoveTreeRemoval().run(tree,root) {
            if(!first.exists()) throw InterruptedIOException("Stop after actual first unlink")
        }}
        assertFalse(first.exists()); assertTrue(File(root,"bytes/0/0.2.1.v3.exo").isFile)
        assertTrue(File(root,"index/native-v1.db").isFile)
        PartitionMoveTreeRemoval().run(persisted(tree),root) {}; assertFalse(root.exists())
    }
    @Test fun actualDestinationRefusalBeforeSourceMutationPreservesEverything() {
        val root=source(); val tree=capture(root)
        assertThrows(IOException::class.java) {PartitionMoveTreeRemoval().run(tree,root) {throw IOException("Destination changed")}}
        assertTrue(tree.files.all {File(root,it.path).isFile})
    }
    @Test fun changedBytesAndForeignEntriesRefuseBeforeAnyDeletion() {
        val root=source(); val tree=capture(root); val audio=File(root,"bytes/0/0.0.1.v3.exo")
        audio.writeBytes(byteArrayOf(9,9))
        assertThrows(IOException::class.java) {PartitionMoveTreeRemoval().run(tree,root) {}}
        assertTrue(tree.files.all {File(root,it.path).isFile})
        val other=source(); val exact=capture(other); File(other,"metadata/foreign").writeText("Keep")
        assertThrows(IOException::class.java) {PartitionMoveTreeRemoval().run(exact,other) {}}
        assertTrue(exact.files.all {File(other,it.path).isFile})
    }
    @Test fun replacingAnAllocationOrParentCannotBeTreatedAsInterruptedCleanup() {
        val root=source(); val tree=capture(root); val displaced=File(root.parentFile,"old")
        assertTrue(root.renameTo(displaced)); assertTrue(root.mkdir())
        assertThrows(IOException::class.java) {PartitionMoveTreeRemoval().run(tree,root) {}}
        assertTrue(displaced.isDirectory); assertTrue(root.isDirectory)
        val other=source(); val exact=capture(other); val parent=other.parentFile
        val replacement=File(parent.parentFile,parent.name+"-old"); assertTrue(parent.renameTo(replacement)); assertTrue(parent.mkdir())
        assertThrows(IOException::class.java) {PartitionMoveTreeRemoval().run(exact,other) {}}
        assertTrue(File(replacement,other.name).isDirectory)
    }
    @Test fun linkForeignFileAndBudgetAreRejectedByCapture() {
        val root=source(); val audio=File(root,"bytes/0/0.0.1.v3.exo"); val target=folders.newFile(); target.writeText("Keep")
        assertTrue(audio.delete()); Files.createSymbolicLink(audio.toPath(),target.toPath())
        assertThrows(IOException::class.java) {capture(root)}; assertEquals("Keep",target.readText())
        val other=source(); File(other,"bytes/0/foreign").writeText("Keep")
        assertThrows(IOException::class.java) {capture(other)}
        val excessive=source()
        for(i in 1..MIGRATION_RANGES) File(excessive,"bytes/0/0.$i.2.v3.exo").writeText("Keep")
        assertThrows(IOException::class.java) {capture(excessive)}
    }
    @Test fun pendingCleanupRejectsLinkedSpanAndShardEvenWhenTheyReachOriginalInodes() {
        val root=source();val tree=persisted(capture(root));var unlinks=0
        fun refuse() {
            assertThrows(IOException::class.java) {PartitionMoveTreeRemoval(unlink={unlinks++;Files.delete(it.toPath())}).run(tree,root) {}}
            assertEquals(0,unlinks)
        }
        val span=File(root,"bytes/0/0.0.1.v3.exo").toPath()
        val parked=File(folders.newFolder(),"audio").toPath()
        Files.move(span,parked)
        try {Files.createSymbolicLink(span,parked);refuse()}
        finally {Files.delete(span);Files.move(parked,span)}
        val shard=File(root,"bytes/0").toPath();val oldShard=File(folders.newFolder(),"shard").toPath()
        Files.move(shard,oldShard)
        try {Files.createSymbolicLink(shard,oldShard);refuse()}
        finally {Files.delete(shard);Files.move(oldShard,shard)}
        tree.verify(root,false,MigrationIoOwnership()) {}
        assertArrayEquals(byteArrayOf(1,2),span.toFile().readBytes())
        PartitionMoveTreeRemoval().run(tree,root) {};assertFalse(root.exists())
    }
    @Test fun sameLengthReplacementAndChangedTypeAreRejectedBeforePendingCleanupUnlink() {
        val root=source();val tree=persisted(capture(root));val span=File(root,"bytes/0/0.0.1.v3.exo")
        val held=File(folders.newFolder(),"audio");Files.move(span.toPath(),held.toPath())
        try {
            Files.copy(held.toPath(),span.toPath());span.setLastModified(held.lastModified())
            var unlinks=0
            assertThrows(IOException::class.java) {PartitionMoveTreeRemoval(unlink={unlinks++;Files.delete(it.toPath())}).run(tree,root) {}}
            assertEquals(0,unlinks);assertArrayEquals(byteArrayOf(1,2),held.readBytes())
            Files.delete(span.toPath());Files.createDirectory(span.toPath())
            assertThrows(IOException::class.java) {tree.verify(root,true,MigrationIoOwnership()) {}}
        } finally {Files.delete(span.toPath());Files.move(held.toPath(),span.toPath())}
        tree.verify(root,false,MigrationIoOwnership()) {}
    }
    @Test fun unknownActualUnlinkRetainsOwnerAndCannotRetryInProcess() {
        val root=source(); val tree=capture(root); val owner=PartitionMoveTreeRemoval(unlink={throw IOException("Unknown unlink")})
        assertThrows(PartitionOwnershipUncertain::class.java) {owner.run(tree,root) {}}
        assertThrows(IOException::class.java) {owner.run(tree,root) {}}
        assertTrue(tree.files.all {File(root,it.path).isFile})
    }
    @Test fun unknownActualReaderCloseRetainsTrackerAndPreventsFurtherHandles() {
        val root=source(); var actual:java.io.Closeable?=null
        val ownership=MigrationIoOwnership(closeHandle={handle ->
            if(handle is java.io.RandomAccessFile) {actual=handle; throw IOException("Unknown actual close")}
            handle.close()
        })
        val error=assertThrows(MigrationIoUncertain::class.java) {PartitionMoveTree.capture(root,10,ownership) {}}
        assertSame(ownership,error.ownership); assertFalse(ownership.quiescent)
        assertThrows(IOException::class.java) {ownership.input(File(root,"bytes/a.uid"))}
        // Fixture knows its injection did not reach the real close; production cannot assume that.
        requireNotNull(actual).close()
    }
    @Test fun missingFilesBeforePendingAreRefusedAndEncodedCountsAreBounded() {
        val root=source(); val tree=capture(root); assertTrue(File(root,"bytes/0/0.0.1.v3.exo").delete())
        assertThrows(IOException::class.java) {tree.verify(root,false,MigrationIoOwnership()) {}}
        tree.verify(root,true,MigrationIoOwnership()) {}
        val bytes=ByteArrayOutputStream(); DataOutputStream(bytes).use {it.writeUTF("parent");it.writeInt(Int.MAX_VALUE)}
        assertThrows(IOException::class.java) {DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {PartitionMoveTree.read(it)}}
    }
}

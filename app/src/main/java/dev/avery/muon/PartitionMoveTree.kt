package dev.avery.muon

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Only a reported nonexistent path proves absence; access/I/O errors are not missing files. */
private fun moveTreePathPresent(file:File):Boolean=try {
    Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS); true
} catch(_:NoSuchFileException) {false}

internal const val PARTITION_MOVE_TREE_FILES=MIGRATION_RANGES+9
internal const val PARTITION_MOVE_TREE_DIRECTORIES=14
private const val MOVE_TREE_DATABASE_BYTES=8L*1024*1024
internal data class PartitionMoveTreeFile(val path:String,val identity:String,val length:Long,
    val modified:String,val digest:String)
internal data class PartitionMoveTreeDirectory(val path:String,val identity:String)

/** Exact CLOSED one-resource allocation, captured after all native readers/databases close.
 * This is observation, not deletion authority. A caller must persist it in an exact Pending Move,
 * own the real exclusive gate and revalidate the actual destination before every source mutation.
 * Retiring this dedicated tree avoids reopening a native index over already removed span files.
 */
internal data class PartitionMoveTree private constructor(val parentIdentity:String,
    val directories:List<PartitionMoveTreeDirectory>,val files:List<PartitionMoveTreeFile>) {
    private fun validate() {
        identity(parentIdentity)
        if(directories.size !in 4..PARTITION_MOVE_TREE_DIRECTORIES || files.size !in 3..PARTITION_MOVE_TREE_FILES ||
            directories.map {it.path}.toSet().size!=directories.size || files.map {it.path}.toSet().size!=files.size ||
            files.map {it.identity}.toSet().size!=files.size)
            throw IOException("Move tree exceeds its exact ownership budget")
        for(d in directories) {
            identity(d.identity)
            if(d.path !in listOf("","bytes","index","metadata") && !d.path.matches(Regex("bytes/[0-9]")))
                throw IOException("Move tree directory is not a supported allocation")
        }
        if(!directories.map {it.path}.containsAll(listOf("","bytes","index","metadata")))
            throw IOException("Move tree lacks its private layout")
        var spans=0
        for(f in files) {
            identity(f.identity)
            if(!allowedFile(f.path) || f.length<0 || f.modified.length !in 1..64 ||
                f.digest.length!=64 || f.digest.any {it !in '0'..'9' && it !in 'a'..'f'} ||
                directories.none {it.path==f.path.substringBeforeLast('/',"")})
                throw IOException("Move tree file has unsupported identity")
            if(f.path.endsWith(".v3.exo")) {spans++; if(f.length==0L) throw IOException("Empty Move audio span")}
            else if(f.length>MOVE_TREE_DATABASE_BYTES) throw IOException("Move tree metadata exceeds budget")
        }
        if(spans>MIGRATION_RANGES || files.count {it.path.endsWith(".uid")}!=1 ||
            files.none {it.path=="index/native-v1.db"} || files.none {it.path=="metadata/content-v1.db"})
            throw IOException("Move tree lacks exact native ownership")
    }
    internal fun requireAbsent(root:File) {
        parent(root)
        if(moveTreePathPresent(root)) throw IOException("Retired source allocation remains or reappeared")
        parent(root)
    }
    internal fun requireUid(uid:Long) {
        validate()
        if(uid<0 || files.count {it.path=="bytes/"+java.lang.Long.toHexString(uid)+".uid"}!=1)
            throw IOException("Move tree UID marker differs from bound source")
    }
    private fun audioFiles(length:Long):List<PartitionMoveTreeFile> {
        validate()
        val spans=files.filter {it.path.endsWith(".v3.exo")}.sortedBy {
            it.path.substringAfterLast('/').split('.')[1].toLongOrNull() ?: throw IOException("Move audio position unsupported")
        }
        var end=0L
        for(span in spans) {
            val position=span.path.substringAfterLast('/').split('.')[1].toLongOrNull()
            if(position!=end || span.length>Long.MAX_VALUE-end) throw IOException("Move audio coverage is not contiguous")
            end+=span.length
        }
        if(length<=0 || end!=length) throw IOException("Move audio length differs from its source binding")
        return spans
    }
    internal fun requireAudioLength(length:Long) {audioFiles(length)}
    /** Capture must match the bound full audio digest, not merely hash whatever files remain. */
    internal fun verifyAudio(root:File,length:Long,expected:String,ownership:MigrationIoOwnership,checkpoint:()->Unit) {
        val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(LOCAL_SAVED_READ_BYTES)
        for(span in audioFiles(length)) {
            checkpoint(); val file=File(root,span.path); exact(file,span)
            val reader=ownership.input(file)
            try {
                var left=span.length
                while(left>0) {
                    checkpoint(); val n=reader.read(buffer,0,minOf(left,buffer.size.toLong()).toInt())
                    if(n<=0) throw IOException("Move audio ended early")
                    digest.update(buffer,0,n); left-=n
                }
                if(reader.read()!=-1) throw IOException("Move audio grew")
            } finally {ownership.close(reader)}
            exact(file,span)
        }
        if(digest.digest().joinToString("") {"%02x".format(it)}!=expected) throw IOException("Move audio changed before Pending")
        verify(root,false,ownership,checkpoint)
    }
    internal fun write(out:DataOutputStream) {
        validate(); out.writeUTF(parentIdentity); out.writeInt(directories.size)
        for(d in directories) {out.writeUTF(d.path); out.writeUTF(d.identity)}
        out.writeInt(files.size)
        for(f in files) {out.writeUTF(f.path); out.writeUTF(f.identity); out.writeLong(f.length); out.writeUTF(f.modified); out.writeUTF(f.digest)}
    }
    /** Missing paths are admissible only after exact Pending was established by the caller. */
    internal fun verify(root:File,allowMissing:Boolean,ownership:MigrationIoOwnership,checkpoint:()->Unit) {
        validate(); parent(root)
        for(d in directories.sortedBy {it.path.length}) {
            checkpoint(); val file=if(d.path.isEmpty()) root else File(root,d.path)
            if(!moveTreePathPresent(file)) {
                if(!allowMissing) throw IOException("Move tree directory is missing")
                continue
            }
            val observed=attributes(file)
            if(!observed.isDirectory || fileIdentity(observed)!=d.identity)
                throw IOException("Move tree directory changed")
            ownership.directory(file.toPath()).use {entries ->
                var count=0
                for(entry in entries) {
                    checkpoint(); if(++count>PARTITION_MOVE_TREE_FILES+PARTITION_MOVE_TREE_DIRECTORIES)
                        throw IOException("Move tree entry budget exceeded")
                    val relative=root.toPath().relativize(entry).toString()
                    if(directories.none {it.path==relative} && files.none {it.path==relative})
                        throw IOException("Move tree has an unowned entry")
                }
            }
        }
        for(f in files) {
            checkpoint(); val file=File(root,f.path)
            // Check all existing ancestors before touching the leaf; no symlink traversal.
            for(d in directories.filter {it.path.isEmpty() || f.path.startsWith(it.path+"/")}) {
                val ancestor=if(d.path.isEmpty()) root else File(root,d.path)
                if(moveTreePathPresent(ancestor)) {
                    val observed=attributes(ancestor)
                    if(!observed.isDirectory || fileIdentity(observed)!=d.identity)
                        throw IOException("Move tree ancestor changed")
                }
            }
            if(!moveTreePathPresent(file)) {
                if(!allowMissing) throw IOException("Move tree file is missing")
                continue
            }
            exact(file,f)
            if(hash(file,f.length,ownership,checkpoint)!=f.digest) throw IOException("Move tree bytes changed")
            exact(file,f)
        }
        checkpoint(); parent(root)
    }
    internal fun parent(root:File) {
        val parent=root.parentFile ?: throw IOException("Move tree parent missing")
        val observed=attributes(parent)
        if(!observed.isDirectory || fileIdentity(observed)!=parentIdentity)
            throw IOException("Move tree parent replaced")
    }
    companion object {
        private fun allowedFile(path:String):Boolean =
            path.matches(Regex("bytes/[0-9]/[0-9]+\\.[0-9]+\\.[0-9]+\\.v3\\.exo")) ||
            path.matches(Regex("bytes/[0-9a-f]+\\.uid")) ||
            path.matches(Regex("(index/native-v1|metadata/content-v1)\\.db(-wal|-shm|-journal)?"))
        private fun identity(value:String) {
            if(value.length !in 1..256 || value.any {it=='\u0000'}) throw IOException("Move tree identity unavailable")
        }
        private fun attributes(file:File):BasicFileAttributes {
            if(file.absoluteFile!=file.canonicalFile) throw IOException("Move tree path is not exact")
            val observed=Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            if(observed.isSymbolicLink) throw IOException("Move tree path is not exact")
            return observed
        }
        // Type, identity, size and timestamp belong to the SAME fresh observation. Never carry
        // these attributes across a caller checkpoint, file read/hash or destructive operation.
        private fun fileIdentity(observed:BasicFileAttributes)=observed.fileKey()?.toString()?.also(::identity)
            ?: throw IOException("Move tree file identity unavailable")
        private fun exact(file:File,expected:PartitionMoveTreeFile) {
            val a=attributes(file)
            if(!a.isRegularFile || fileIdentity(a)!=expected.identity || a.size()!=expected.length ||
                a.lastModifiedTime().toString()!=expected.modified) throw IOException("Move tree file changed")
        }
        private fun hash(file:File,length:Long,ownership:MigrationIoOwnership,checkpoint:()->Unit):String {
            val digest=MessageDigest.getInstance("SHA-256"); val input=ownership.input(file)
            try {
                val buffer=ByteArray(LOCAL_SAVED_READ_BYTES); var left=length
                while(left>0) {
                    checkpoint(); val n=input.read(buffer,0,minOf(left,buffer.size.toLong()).toInt())
                    if(n<=0) throw IOException("Move tree file ended early")
                    digest.update(buffer,0,n); left-=n
                }
                if(input.read()!=-1) throw IOException("Move tree file grew")
            } finally {ownership.close(input)}
            return digest.digest().joinToString("") {"%02x".format(it)}
        }
        internal fun capture(root:File,uid:Long,ownership:MigrationIoOwnership,checkpoint:()->Unit):PartitionMoveTree {
            if(uid<0) throw IOException("Move tree native UID is missing")
            val parent=root.parentFile ?: throw IOException("Move tree parent is missing")
            val dirs=ArrayList<PartitionMoveTreeDirectory>(); val files=ArrayList<PartitionMoveTreeFile>()
            fun scan(directory:File,path:String) {
                checkpoint()
                if(dirs.size>=PARTITION_MOVE_TREE_DIRECTORIES) throw IOException("Move tree directory budget/type differs")
                val observed=attributes(directory)
                if(!observed.isDirectory) throw IOException("Move tree directory budget/type differs")
                dirs+=PartitionMoveTreeDirectory(path,fileIdentity(observed))
                val children=ownership.directory(directory.toPath()).use {entries ->
                    val bounded=ArrayList<File>()
                    for(entry in entries) {
                        checkpoint()
                        if(bounded.size>=PARTITION_MOVE_TREE_FILES+PARTITION_MOVE_TREE_DIRECTORIES)
                            throw IOException("Move tree entry budget exceeded")
                        bounded+=entry.toFile()
                    }
                    bounded
                } // Close the actual directory before recursion/file readers; never grow handle depth.
                for(file in children) {
                        checkpoint(); val name=if(path.isEmpty()) file.name else path+"/"+file.name
                        val a=attributes(file)
                        if(a.isDirectory) {
                            if(name !in listOf("bytes","index","metadata") && !name.matches(Regex("bytes/[0-9]")))
                                throw IOException("Move tree contains an unowned directory")
                            scan(file,name)
                        } else {
                            if(files.size>=PARTITION_MOVE_TREE_FILES || !allowedFile(name)) throw IOException("Move tree file budget/path differs")
                            if(!a.isRegularFile || (!name.endsWith(".v3.exo") && a.size()>MOVE_TREE_DATABASE_BYTES))
                                throw IOException("Move tree file type/metadata exceeds budget")
                            files+=PartitionMoveTreeFile(name,fileIdentity(a),a.size(),a.lastModifiedTime().toString(),
                                hash(file,a.size(),ownership,checkpoint)).also {exact(file,it)}
                        }
                }
            }
            // Path depth is fixed and directory enumeration closes before recursion/readers.
            scan(root,"")
            if(files.count {it.path=="bytes/"+java.lang.Long.toHexString(uid)+".uid"}!=1)
                throw IOException("Move tree UID marker differs")
            return PartitionMoveTree(fileIdentity(attributes(parent)),dirs.sortedBy {it.path},files.sortedBy {it.path}).also {
                it.validate(); it.verify(root,false,ownership,checkpoint)
            }
        }
        internal fun read(input:DataInputStream):PartitionMoveTree {
            val parent=input.readUTF(); val count=input.readInt()
            if(count !in 4..PARTITION_MOVE_TREE_DIRECTORIES) throw IOException("Move tree directory budget exceeded")
            val dirs=List(count) {PartitionMoveTreeDirectory(input.readUTF(),input.readUTF())}
            val size=input.readInt()
            if(size !in 3..PARTITION_MOVE_TREE_FILES) throw IOException("Move tree file budget exceeded")
            val files=List(size) {PartitionMoveTreeFile(input.readUTF(),input.readUTF(),input.readLong(),input.readUTF(),input.readUTF())}
            return PartitionMoveTree(parent,dirs,files).also {it.validate()}
        }
    }
}

/** Register before run. The caller supplies an exact durable Pending and actual destination/gate
 * checkpoint. Unknown mutation or actual handle close retains this owner; never retry it in-process.
 * Success proves only this dedicated allocation absent, not row/cover removal or journal Complete.
 */
internal class PartitionMoveTreeRemoval(private val ownership:MigrationIoOwnership=MigrationIoOwnership(),
    private val unlink:(File)->Unit={Files.delete(it.toPath())}) {
    private enum class Phase { New,Active,Finished,Uncertain }
    private var phase=Phase.New
    private var current:File?=null
    @Synchronized internal fun run(tree:PartitionMoveTree,root:File,verifyDestination:()->Unit) =
        runMoved(tree,root,verifyDestination,verifyDestination)
    @Synchronized internal fun runMoved(tree:PartitionMoveTree,root:File,checkpoint:()->Unit,verifyDestination:()->Unit) {
        if(phase!=Phase.New) throw IOException("Move tree attempt already used or uncertain")
        phase=Phase.Active
        fun checked() {
            // The full binding/target check brackets the entire source hash and precedes EVERY
            // unlink. Physical reads retain fresh lease/volume/root/journal/cancellation checks;
            // they must not repeat all saved-row/cover queries for each 64KiB hash block.
            verifyDestination()
            tree.verify(root,true,ownership,checkpoint)
            checkpoint()
            verifyDestination()
        }
        fun sync(directory:File) {
            tree.parent(root)
            ownership.useOwned({FileChannel.open(directory.toPath(),StandardOpenOption.READ)}) {it.force(true)}
        }
        fun remove(file:File) {
            current=file
            try {
                unlink(file)
                if(moveTreePathPresent(file)) throw IOException("Move tree unlink did not establish absence")
                sync(requireNotNull(file.parentFile)); current=null
            } catch(failure:Throwable) {phase=Phase.Uncertain; throw PartitionOwnershipUncertain(this,failure)}
        }
        try {
            // Bound the manifest and parent before sorting. The first loop entry already checks
            // the full remaining tree and destination before any unlink; an extra initial
            // checked() would repeat the same full byte hashes without an intervening mutation.
            tree.parent(root)
            // All databases are already closed. Remove spans first, marker last; no native reopen.
            for(entry in tree.files.sortedBy {if(it.path.endsWith(".v3.exo")) 0 else if(it.path.endsWith(".uid")) 2 else 1}) {
                checked(); val file=File(root,entry.path)
                if(moveTreePathPresent(file)) remove(file)
            }
            for(entry in tree.directories.sortedByDescending {it.path.length}) {
                checked(); val file=if(entry.path.isEmpty()) root else File(root,entry.path)
                if(moveTreePathPresent(file)) remove(file)
            }
            verifyDestination(); tree.parent(root)
            if(moveTreePathPresent(root)) throw IOException("Move tree root remains")
            phase=Phase.Finished
        } catch(failure:Throwable) {
            if(failure is MigrationIoUncertain || !ownership.quiescent || phase==Phase.Uncertain) {
                phase=Phase.Uncertain
                throw if(failure is PartitionOwnershipUncertain) failure else PartitionOwnershipUncertain(this,failure)
            }
            phase=Phase.Finished; throw failure
        }
    }
}

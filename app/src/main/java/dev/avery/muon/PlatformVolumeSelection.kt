package dev.avery.muon

import java.io.File
import java.io.IOException

/** Only matches fresh Android-declared mount roots to an already selected app directory. This is
 * NOT filesystem/owner authority: callers retain canonical path, inode, UID/database and physical
 * I/O checks. No canonical/stat probes, mount snapshots or retained volume objects here. A null
 * root can mean unmounted; a no-match allows the existing framework lookup as a compatibility
 * fallback. Ambiguous/malformed/over-budget observations refuse instead of guessing a volume. */
internal fun <T:Any> selectPlatformVolume(directory:File,volumes:List<T>,root:(T)->File?):T? {
    if(volumes.size>8) throw IOException("Too many platform storage volumes")
    fun path(file:File):java.nio.file.Path {
        val value=file.toPath()
        if(file.path.length>4096 || !value.isAbsolute || value!=value.normalize() || value.nameCount==0)
            throw IOException("Unsupported platform storage path")
        return value
    }
    val selected=path(directory)
    var result:T?=null
    for(volume in volumes) {
        val mounted=root(volume) ?: continue
        if(selected.startsWith(path(mounted))) {
            if(result!=null) throw IOException("Ambiguous platform storage path")
            result=volume
        }
    }
    return result
}

package dev.avery.muon

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PlatformVolumeSelectionTest {
    private data class Volume(val root:File?,val identity:String)
    private fun select(path:String,vararg volumes:Volume)=selectPlatformVolume(File(path),volumes.toList()) {it.root}
    @Test fun wholePathComponentsMatchWithoutFilesystemCanonicalOrStatWork() {
        val root=object:File("/storage/card") {
            override fun getCanonicalFile():File=error("Mount-root canonical I/O is not needed")
            override fun isDirectory():Boolean=error("Mount-root stat I/O is not needed")
        }
        val target=object:File("/storage/card/Android/data/fixture/files") {
            override fun getCanonicalFile():File=error("Actual owner checks its selected directory separately")
        }
        val card=Volume(root,"card")
        assertSame(card,selectPlatformVolume(target,listOf(Volume(File("/storage/emulated/0"),"phone"),card)) {it.root})
        assertNull(select("/storage/card-extra/Android/data/fixture/files",card))
        assertNull(select("/storage/CARD/Android/data/fixture/files",card))
        assertSame(card,select("/storage/card",card))
    }
    @Test fun duplicateAndOverlappingMountRootsRefuseInsteadOfPickingTheFirst() {
        val card=Volume(File("/storage/card"),"one")
        assertThrows(IOException::class.java) {select("/storage/card/Android",card,card.copy(identity="two"))}
        assertThrows(IOException::class.java) {select("/storage/card/Android",card,Volume(File("/storage"),"other"))}
    }
    @Test fun nullAndNoMatchAllowFrameworkFallbackButMalformedPathsRefuse() {
        assertNull(select("/storage/card/Android",Volume(null,"not-mounted")))
        assertNull(select("/storage/card/Android",Volume(File("/mnt/media_rw/card"),"alias-layout")))
        for(path in listOf("relative/card","/storage/card/../other","/")) {
            assertThrows(IOException::class.java) {select(path,Volume(File("/storage/card"),"card"))}
            assertThrows(IOException::class.java) {select("/storage/card/Android",Volume(File(path),"invalid"))}
        }
        assertThrows(IOException::class.java) {select("/storage/"+"x".repeat(4096),Volume(null,"none"))}
    }
    @Test fun freshObservationsDoNotRetainAnEarlierSelectionOrUuid() {
        val first=Volume(File("/storage/card"),"first")
        assertSame(first,select("/storage/card/Android",first))
        assertNull(select("/storage/card/Android",first.copy(root=null)))
        val replacement=first.copy(identity="replacement")
        assertSame(replacement,select("/storage/card/Android",replacement))
        assertEquals("first",first.identity) // Actual caller still compares exact selected UUID.
    }
    @Test fun boundedVolumeListRefusesBeforeAccessingAnyRoot() {
        val values=List(9) {Volume(null,"fixture")}
        assertThrows(IOException::class.java) {selectPlatformVolume(File("/storage/card"),values) {error("Oversized list must not be read")}}
        assertNull(selectPlatformVolume(File("/storage/card"),values.take(8)) {it.root})
    }
}

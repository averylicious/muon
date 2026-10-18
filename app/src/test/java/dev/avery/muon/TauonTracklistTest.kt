package dev.avery.muon

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,34],manifest=Config.NONE)
class TauonTracklistTest {
    private fun previous(text:String):List<TauonTrack> {
        val array=parseTauonJson(text).getJSONArray("tracks")
        requireLibraryEntryCount(array.length())
        return List(array.length()) {projectTauonTrack(array.getJSONObject(it))}
    }
    @Test fun ordinaryWideTracklistMatchesTheExistingProjectionAndKeepsEveryRecord() {
        val array=JSONArray()
        repeat(1000) {i ->array.put(JSONObject().put("id",i).put("title","Track $i")
            .put("artist","Artist").put("duration",180000).put("can_download",true))}
        val text=JSONObject().put("tracks",array).toString()
        val actual=parseTauonTracklist(text)
        assertEquals(previous(text),actual);assertEquals(1000,actual.size)
        assertEquals(0L,actual.first().id);assertEquals(999L,actual.last().id)
    }
    @Test fun unicodeEscapesAndFullAlbumMetadataMatchWithoutTagTruncation() {
        val record=JSONObject().put("id",42).put("title","Björk 宇多田🎵 \"quote\" \\ tail\u0000")
            .put("artist","A; B").put("album","Album").put("album_artist","Various Artists")
            .put("track_number"," 3/12 ").put("duration",123456).put("has_lyrics",true).put("can_download",true)
        val text=JSONObject().put("tracks",JSONArray().put(record)).toString()
        val actual=parseTauonTracklist(text).single()
        assertEquals(previous(text).single(),actual);assertEquals(record.getString("title"),actual.title)
        assertEquals("Various Artists",actual.albumArtist);assertEquals("3/12",actual.trackNumber)
    }
    @Test fun stringNumberNullAndBooleanCoercionsRemainJSONObjectCompatible() {
        val text="""{"tracks":[{"id":1.9,"title":null,"path":"/any/Fallback.ogg","artist":7,"album":false,"duration":"180000","can_download":"true","has_lyrics":1,"album_artist":null,"track_number":2,"extra":{"array":[true,null,1.5]}}]}"""
        val actual=parseTauonTracklist(text).single()
        assertEquals(previous(text).single(),actual);assertEquals(1L,actual.id)
        assertEquals("7",actual.artist);assertEquals(180000L,actual.durationMs)
    }
    @Test fun establishedPlatformLenientSyntaxKeepsTheBoundedCompatibilityPath() {
        for(text in listOf(
            "{/* ignored */ tracks:[{id:1,title:'Single quoted'}]}",
            "{tracks:[{id=1;title:'Separators'}]}",
            "{tracks:[{id:0x10,title:'Hex'}]}",
            "{tracks:[{id:1,artist:unquoted}]}",
        )) assertEquals(text,previous(text),parseTauonTracklist(text))
    }
    @Test fun duplicateTracksPropertiesUseTheLastValueEvenAfterEarlierProjectionFailure() {
        for(text in listOf(
            """{"tracks":[{"title":"No id"}],"tracks":[{"id":2}]}""",
            """{"tracks":null,"tracks":[{"id":2}]}""",
            """{"tracks":[{"id":-1}],"tracks":[]}""",
        )) assertEquals(previous(text),parseTauonTracklist(text))
        assertThrows(Exception::class.java) {parseTauonTracklist("""{"tracks":[{"id":2}],"tracks":null}""")}
    }
    @Test fun ignoredExtensionsStillReceiveTheSameStructuralLimitsBeforeProjection() {
        val wide="""{"tracks":[{"id":1}],"ignored":[1,2,3]}"""
        assertThrows(LibraryResourceLimit::class.java) {parseTauonTracklist(wide,TauonJsonLimits(arrayItems=2))}
        assertThrows(LibraryResourceLimit::class.java) {parseTauonTracklist("""{"tracks":[{"id":1}]}""",TauonJsonLimits(values=5))}
        val deep="{\"tracks\":[],\"ignored\":"+"[".repeat(10000)+"0"+"]".repeat(10000)+"}"
        assertThrows(IOException::class.java) {parseTauonTracklist(deep)}
    }
    @Test fun missingWrongTypeAndMalformedRecordsFailRatherThanPublishingAPrefix() {
        for(text in listOf(
            "{}","[]","{\"tracks\":null}","{\"tracks\":{}}","{\"tracks\":[null]}",
            "{\"tracks\":[{\"id\":1},\"wrong\"]}","{\"tracks\":[{\"id\":-1}]}",
            "{\"tracks\":[{\"id\":1}","{\"tracks\":[,]}",
        )) {
            assertThrows(text,Exception::class.java) {previous(text)}
            assertThrows(text,Exception::class.java) {parseTauonTracklist(text)}
        }
        assertEquals(emptyList<TauonTrack>(),parseTauonTracklist("{\"tracks\":[]}"))
    }
    @Test fun lastTrackArrayStillEnforcesMetadataAdmissionButOverriddenBadTagsDoNotEscape() {
        val record=JSONObject().put("id",1).put("title","X".repeat(TRACK_METADATA_MAX_BYTES+1)).toString()
        val bad="{\"tracks\":[$record]}"
        assertThrows(IOException::class.java) {previous(bad)}
        assertThrows(IOException::class.java) {parseTauonTracklist(bad)}
        val overridden="{\"tracks\":[$record],\"tracks\":[{\"id\":2}]}"
        assertEquals(previous(overridden),parseTauonTracklist(overridden))
    }
}

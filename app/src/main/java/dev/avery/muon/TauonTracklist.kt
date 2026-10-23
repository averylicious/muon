package dev.avery.muon

import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import java.io.IOException
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Keep the existing JSONObject field coercions/title fallback and per-track admission exactly. */
internal fun projectTauonTrack(record:JSONObject):TauonTrack {
    val id=record.getLong("id");require(id>=0)
    val artist=record.optString("artist")
    return TauonTrack(id,trackDisplayTitle(record.opt("title") as? String,record.opt("path") as? String),artist,
        record.optString("album"),record.optLong("duration"),record.optBoolean("can_download",false),
        record.optBoolean("has_lyrics"),albumArtist=albumArtistTag(record.opt("album_artist"),artist),
        trackNumber=trackNumberTag(record.opt("track_number"))).also(::requireTrackMetadataBudget)
}

/** Raw text remains wire-bounded. Standard JSON retains one record DOM at a time instead of a
 * complete track-array graph. All structural limits run FIRST, including ignored extensions.
 * Older lenient syntax keeps the original bounded DOM parser; it gets no relaxed admission. */
internal fun parseTauonTracklist(text:String,limits:TauonJsonLimits=TauonJsonLimits(),
    checkpoint:()->Unit={}):List<TauonTrack> {
    validateTauonJson(text,limits,checkpoint)
    try {return strictTracklist(text,checkpoint)}
    catch(_:MalformedJsonException) {
        checkpoint()
        val array=JSONObject(text).getJSONArray("tracks")
        checkpoint()
        requireLibraryEntryCount(array.length())
        return List(array.length()) { checkpoint(); projectTauonTrack(array.getJSONObject(it)) }.also { checkpoint() }
    }
}

private fun strictTracklist(text:String,checkpoint:()->Unit):List<TauonTrack> = JsonReader(StringReader(text)).use { reader ->
    checkpoint()
    reader.beginObject()
    var result:List<TauonTrack>?=null
    var failure:Exception?=null
    while(reader.hasNext()) {
        checkpoint()
        if(reader.nextName()!="tracks") {
            // Preserve complete syntax/value validation, but do not retain unrelated outer fields.
            readTrackJsonValue(reader,checkpoint)
            continue
        }
        result=null;failure=null
        if(reader.peek()!=JsonToken.BEGIN_ARRAY) {
            readTrackJsonValue(reader,checkpoint);failure=IOException("Tauon tracks must be an array");continue
        }
        val records=ArrayList<TauonTrack>();var count=0
        reader.beginArray()
        while(reader.hasNext()) {
            checkpoint()
            requireLibraryEntryCount(++count)
            val record=readTrackJsonValue(reader,checkpoint)
            // JSONObject uses the last duplicate property. A projection failure in an overridden
            // tracks array must not reject a later valid one. Structural failures still abort.
            if(failure==null) try {
                records+=projectTauonTrack(record as? JSONObject ?: throw IOException("Tauon track must be an object"))
            } catch(problem:Exception) {failure=problem;records.clear()}
            checkpoint() // Outside deferred projection errors: cancellation cannot be overridden.
        }
        reader.endArray();result=records
    }
    reader.endObject()
    checkpoint()
    failure?.let {throw it}
    result ?: throw IOException("Tauon tracks array is missing")
}

/** One record (or discarded extension) uses the same JSON value types as JSONObject. Preserve
 * its numeric conversions rather than substituting JsonReader.nextLong's stricter coercions.
 * The already completed depth/value preflight bounds recursive record/extension construction. */
private fun readTrackJsonValue(reader:JsonReader,checkpoint:()->Unit):Any {
    checkpoint()
    return when(reader.peek()) {
    JsonToken.BEGIN_OBJECT -> {
        val value=JSONObject();reader.beginObject()
        while(reader.hasNext()) {val name=reader.nextName();value.put(name,readTrackJsonValue(reader,checkpoint))}
        reader.endObject();value
    }
    JsonToken.BEGIN_ARRAY -> {
        val value=JSONArray();reader.beginArray()
        while(reader.hasNext()) value.put(readTrackJsonValue(reader,checkpoint))
        reader.endArray();value
    }
    JsonToken.STRING -> reader.nextString()
    JsonToken.NUMBER -> JSONTokener(reader.nextString()).nextValue()
    JsonToken.BOOLEAN -> reader.nextBoolean()
    JsonToken.NULL -> {reader.nextNull();JSONObject.NULL}
    else -> throw IOException("Unexpected Tauon JSON value")
}
}

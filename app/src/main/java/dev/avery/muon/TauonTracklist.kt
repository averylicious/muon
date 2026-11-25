package dev.avery.muon

import android.util.JsonReader
import android.util.JsonToken
import android.util.MalformedJsonException
import java.io.IOException
import java.io.StringReader
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Keep JSONObject field coercions/title fallback and per-track admission. Add new projected fields
 * to TRACK_FIELDS below too, so strict parsing retains their values. */
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
            // Preserve complete syntax/value validation, but do not build unrelated outer fields.
            requireObjectMember(readTrackJsonValue(reader,checkpoint,retain=false))
            continue
        }
        result=null;failure=null
        if(reader.peek()!=JsonToken.BEGIN_ARRAY) {
            requireObjectMember(readTrackJsonValue(reader,checkpoint,retain=false))
            failure=IOException("Tauon tracks must be an array");continue
        }
        val records=ArrayList<TauonTrack>();var count=0
        reader.beginArray()
        while(reader.hasNext()) {
            checkpoint()
            requireLibraryEntryCount(++count)
            // After a deferred failure nothing more is projected from this array: validate only.
            val record=readTrackJsonValue(reader,checkpoint,retain=failure==null,record=true)
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

/** The fields projectTauonTrack reads. Only a record's own unused fields are dropped: a retained
 * known value keeps its complete nested shape for the established optString/opt coercions. */
private val TRACK_FIELDS=setOf("id","title","path","artist","album","duration","can_download","has_lyrics",
    "album_artist","track_number")

/** Discarded containers return this; nothing reads them. */
private val DISCARDED=Unit

/** JSONObject.put rejects a non-finite Number member (JSON.checkDouble); JSONArray.put(Object)
 * accepts it. A discarded object member keeps the same check without building the object. */
private fun requireObjectMember(value:Any) {
    if(value is Number) {
        val number=value.toDouble()
        if(number.isNaN()||number.isInfinite()) throw JSONException("Forbidden numeric value: $number")
    }
}

/** One record (or discarded extension) uses the same JSON value types as JSONObject. Preserve
 * its numeric conversions rather than substituting JsonReader.nextLong's stricter coercions.
 * With retain=false every value is still read, syntax-checked and checkpointed, and object
 * members keep JSONObject's numeric check, but no JSONObject/JSONArray is built. A track
 * record (record=true) retains only an object, and only its TRACK_FIELDS.
 * The already completed depth/value preflight bounds recursive record/extension reading. */
private fun readTrackJsonValue(reader:JsonReader,checkpoint:()->Unit,retain:Boolean=true,record:Boolean=false):Any {
    checkpoint()
    return when(reader.peek()) {
    JsonToken.BEGIN_OBJECT -> {
        val value=if(retain) JSONObject() else null;reader.beginObject()
        while(reader.hasNext()) {
            val name=reader.nextName()
            val keep=value!=null && (!record || name in TRACK_FIELDS)
            val member=readTrackJsonValue(reader,checkpoint,keep)
            if(keep) value!!.put(name,member) else requireObjectMember(member)
        }
        reader.endObject();value ?: DISCARDED
    }
    JsonToken.BEGIN_ARRAY -> {
        // A non-object track record cannot be projected; do not build it.
        val value=if(retain && !record) JSONArray() else null;reader.beginArray()
        while(reader.hasNext()) {
            val member=readTrackJsonValue(reader,checkpoint,value!=null)
            value?.put(member)
        }
        reader.endArray();value ?: DISCARDED
    }
    JsonToken.STRING -> reader.nextString()
    JsonToken.NUMBER -> JSONTokener(reader.nextString()).nextValue()
    JsonToken.BOOLEAN -> reader.nextBoolean()
    JsonToken.NULL -> {reader.nextNull();JSONObject.NULL}
    else -> throw IOException("Unexpected Tauon JSON value")
}
}

package dev.avery.muon

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Capability for the fixed private review shortcut, not general exported-activity authority.
 * Android's own launcher/shortcut service receives it. Never log it, accept supplied config/paths,
 * publish a shortcut after an uncertain commit, or create a credential while validating a caller.
 */
internal class StorageReviewEntry(private val prefs:SharedPreferences,
    private val random:()->String={UUID.randomUUID().toString()}) {
    private var uncertain=false
    private fun canonical(value:String?):String? {
        if(value==null || value.length!=36) return null
        return try {value.takeIf {UUID.fromString(it).toString()==it}} catch(_:IllegalArgumentException) {null}
    }
    private fun existing():String?=try {canonical(prefs.getString(KEY,null))} catch(_:ClassCastException) {null}
    @Synchronized fun prepare():String {
        if(uncertain) throw IOException("Review shortcut credential write is uncertain")
        existing()?.let {return it}
        if(prefs.contains(KEY)) throw IOException("Review shortcut credential is unsupported")
        val value=canonical(random()) ?: throw IOException("Review shortcut credential is unsupported")
        try {if(!prefs.edit().putString(KEY,value).commit()) throw IOException("Review shortcut credential was not confirmed")}
        catch(failure:Exception) {uncertain=true;throw IOException("Review shortcut credential write is uncertain",failure)}
        return value
    }
    @Synchronized fun accepts(intent:Intent):Boolean {
        if(uncertain || intent.action!=REVIEW_SAVED_STORAGE_ACTION) return false
        val expected=existing() ?: return false
        val supplied=try {canonical(intent.getStringExtra(EXTRA))} catch(_:RuntimeException) {null} ?: return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII),supplied.toByteArray(Charsets.US_ASCII))
    }
    fun intent(context:Context,credential:String):Intent {
        if(canonical(credential)==null) throw IOException("Review shortcut credential is unsupported")
        return Intent(context,MainActivity::class.java).setAction(REVIEW_SAVED_STORAGE_ACTION).putExtra(EXTRA,credential)
    }
    companion object {
        private const val KEY="shortcutCredentialV1"
        internal const val EXTRA="dev.avery.muon.extra.STORAGE_REVIEW_CREDENTIAL"
        private var processOwner:StorageReviewEntry?=null
        @Synchronized fun from(context:Context):StorageReviewEntry=processOwner ?:
            StorageReviewEntry(context.applicationContext.getSharedPreferences("storage-review-entry",Context.MODE_PRIVATE)).also {processOwner=it}
    }
}

package dev.avery.muon

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class StorageReviewEntryTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.getSharedPreferences("storage-review-entry-test",Context.MODE_PRIVATE)
    @Before fun clear() {assertTrue(prefs.edit().clear().commit())}
    @Test fun fixedShortcutNeedsThePersistedPrivateCapabilityAndValidationNeverCreatesOne() {
        val owner=StorageReviewEntry(prefs)
        val external=Intent().setAction(REVIEW_SAVED_STORAGE_ACTION)
        assertFalse(owner.accepts(external));assertTrue(prefs.all.isEmpty())
        val token=owner.prepare();assertEquals(token,owner.prepare())
        val valid=owner.intent(app,token);assertTrue(owner.accepts(valid))
        assertEquals(MainActivity::class.java.name,valid.component!!.className)
        assertFalse(owner.accepts(external));assertFalse(owner.accepts(Intent(valid).setAction(Intent.ACTION_MAIN)))
        assertFalse(owner.accepts(Intent(valid).putExtra(StorageReviewEntry.EXTRA,UUID.randomUUID().toString())))
        assertFalse(owner.accepts(Intent(valid).putExtra(StorageReviewEntry.EXTRA,"x".repeat(8192))))
        assertFalse(owner.accepts(Intent(valid).putExtra(StorageReviewEntry.EXTRA,5)))
        assertTrue(StorageReviewEntry(prefs).accepts(valid))
    }
    @Test fun failedCredentialCommitDoesNotGrantThisOwnerReviewEvenAfterMemoryChanged() {
        val failing=object:SharedPreferences by prefs {
            override fun edit():SharedPreferences.Editor {
                val actual=prefs.edit()
                return object:SharedPreferences.Editor by actual {
                    override fun putString(key:String,value:String?):SharedPreferences.Editor {actual.putString(key,value);return this}
                    override fun commit():Boolean {actual.commit();return false}
                }
            }
        }
        val token=UUID.randomUUID().toString();val owner=StorageReviewEntry(failing) {token}
        assertThrows(IOException::class.java) {owner.prepare()}
        assertFalse(owner.accepts(owner.intent(app,token)));assertThrows(IOException::class.java) {owner.prepare()}
        // Same uncertain owner remains captured in production; another instance isn't a retry API.
        assertTrue(prefs.all.isNotEmpty())
    }
}

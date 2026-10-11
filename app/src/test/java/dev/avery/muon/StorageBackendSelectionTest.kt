package dev.avery.muon

import android.content.Context
import java.io.IOException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class StorageBackendSelectionTest {
    private val app:Context get()=RuntimeEnvironment.getApplication()
    private val prefs get()=app.getSharedPreferences("backend-selection-test",Context.MODE_PRIVATE)
    @Before fun clean() { prefs.edit().clear().commit() }

    @Test fun absentOrExplicitLegacyStartsOneOwnerAndNeverReadsLaterChoice() {
        for(explicit in listOf(false,true)) {
            prefs.edit().clear().apply()
            if(explicit) prefs.edit().putString(StorageBackendSelection.KEY,"legacy").commit()
            val startup=StorageStartup<Any>(); var legacy=0; var partition=0
            val made=startup.open { owner -> StorageBackendSelection.read(prefs).open(
                legacy={ owner.own { legacy++; Any() } },partition={ partition++; Any() }) }
            prefs.edit().putString(StorageBackendSelection.KEY,"partition-v1").commit()
            assertSame(made,startup.open { error("Must not switch an existing owner") })
            assertEquals(1,legacy); assertEquals(0,partition); assertEquals(1,startup.retained)
        }
    }

    @Test fun unsupportedPartitionRefusesBeforeAnyParticipantAndDoesNotRetryAfterPreferenceChange() {
        prefs.edit().putString(StorageBackendSelection.KEY,"partition-v1").commit()
        val startup=StorageStartup<Any>(); var opened=0
        try {
            startup.open { owner -> StorageBackendSelection.read(prefs).open(legacy={ owner.own { opened++; Any() } }) }
            fail("Partition is not yet integrated")
        } catch(expected:IOException) { assertTrue(expected.message!!.contains("legacy storage was not opened")) }
        assertEquals(0,opened); assertEquals(0,startup.retained)
        prefs.edit().remove(StorageBackendSelection.KEY).commit()
        try { startup.open { opened++; Any() }; fail("Failed startup must remain sticky") } catch(_:IOException) {}
        assertEquals(0,opened)
    }

    @Test fun unknownOrWrongTypeRefusesWithoutRepairingThePersistedChoice() {
        for(value in listOf("future-v99","","partition-v1-extra")) {
            prefs.edit().putString(StorageBackendSelection.KEY,value).commit()
            try { StorageBackendSelection.read(prefs); fail("Unknown mode must not default to legacy") } catch(_:IOException) {}
            assertEquals(value,prefs.getString(StorageBackendSelection.KEY,null))
        }
        prefs.edit().putInt(StorageBackendSelection.KEY,1).commit()
        try { StorageBackendSelection.read(prefs); fail("Wrong type must not default to legacy") } catch(_:IOException) {}
        assertEquals(1,prefs.getInt(StorageBackendSelection.KEY,0))
    }

    @Test fun actualOfflineStoreRefusesBeforeOpeningDatabaseOrLegacyFolder() {
        val actual=app.getSharedPreferences("storage",Context.MODE_PRIVATE)
        actual.edit().putString(StorageBackendSelection.KEY,"partition-v1").commit()
        val before=app.databaseList().toSet()
        val folder=java.io.File(app.filesDir,"downloads")
        assertFalse(folder.exists())
        val startup=StorageStartup<OfflineStore.Store>()
        val create=OfflineStore::class.java.declaredMethods.single { it.name=="create" }.apply { isAccessible=true }
        try {
            startup.open { opening ->
                try { create.invoke(OfflineStore,app,opening) as OfflineStore.Store }
                catch(failure:java.lang.reflect.InvocationTargetException) { throw requireNotNull(failure.cause) }
            }
            fail("Actual production startup must refuse staged partition selection")
        } catch(expected:IOException) { assertTrue(expected.message!!.contains("production integration")) }
        assertEquals(0,startup.retained)
        assertEquals(before,app.databaseList().toSet())
        assertFalse(folder.exists())
        assertEquals("partition-v1",actual.getString(StorageBackendSelection.KEY,null))
        actual.edit().remove(StorageBackendSelection.KEY).commit()
    }

    @Test fun selectedPartitionFailureKeepsActualAttemptOwnedWithoutInvokingLegacy() {
        prefs.edit().putString(StorageBackendSelection.KEY,"partition-v1").commit()
        val startup=StorageStartup<Any>(); var legacy=0; var partition=0
        try {
            startup.open { owner -> StorageBackendSelection.read(prefs).open(legacy={legacy++; Any()},partition={
                owner.own<Any> { partition++; throw IOException("Partly constructed root") }
            }) }; fail("Construction failed")
        } catch(_:IOException) {}
        assertEquals(0,legacy); assertEquals(1,partition); assertEquals(1,startup.retained)
        try { startup.open { legacy++; Any() }; fail("Cannot retry fallback") } catch(_:IOException) {}
        assertEquals(0,legacy)
    }
}

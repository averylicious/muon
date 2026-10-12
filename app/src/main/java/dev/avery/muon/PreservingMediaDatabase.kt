@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.avery.muon

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import androidx.media3.database.StandaloneDatabaseProvider

/** Pinned Media3's default onDowngrade drops every feature table before our per-feature guards
 * run. Refuse that destructive hook while retaining the supported helper's normal creation and
 * opening behavior. SQLite already supplies exact versions here; no separate full-index load,
 * inferred schema repair, silent fallback or mutable copy of the helper implementation is needed.
 * Register this actual helper in StorageStartup before any getReadable/WritableDatabase call.
 */
internal class PreservingMediaDatabase(context:Context):StandaloneDatabaseProvider(context) {
    override fun onDowngrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        throw SQLiteException("Saved media database version is newer than this build; no downgrade or table reset was performed")
    }
    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int) {
        throw SQLiteException("Saved media database upgrade is unsupported by this build; existing records were kept")
    }
}

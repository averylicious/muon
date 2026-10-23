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

/** Android may report the same private database below /data/user/0 while filesDir resolves
 * below /data/data. Normalize only the trusted framework-provided PARENT before transferring
 * this path to strict legacy ownership. Keep the final file unresolved so a database symlink
 * still refuses; no arbitrary path/alias is accepted by LegacyTransitionOwner itself. */
internal fun platformMediaDatabaseFile(file:java.io.File):java.io.File =
    java.io.File(requireNotNull(file.absoluteFile.parentFile).canonicalFile,file.name)

package top.cylunex.shadowmedia.database

import androidx.room.withTransaction
import top.cylunex.shadowmedia.model.EmbySession

/** Keep asset ids, files and all edition locators intact. Ambiguous legacy accounts stay unbound. */
suspend fun ShadowMediaDatabase.migrateAccountScopes(accounts: List<EmbySession>) = withTransaction {
    val sql = openHelper.writableDatabase
    for ((old, matches) in accounts.distinctBy { it.providerId }.groupBy { it.legacyProviderId }) {
        val ambiguous = "account-scope-ambiguous:$old"
        if (matches.size != 1) {
            sql.execSQL("INSERT OR IGNORE INTO migration_imports(id) VALUES (?)", arrayOf(ambiguous))
            continue
        }
        if (sql.query("SELECT id FROM migration_imports WHERE id = ?", arrayOf(ambiguous)).use { it.moveToFirst() }) continue
        val next = matches.single().providerId
        val marker = "account-scope:$old:$next"
        if (sql.query("SELECT id FROM migration_imports WHERE id = ?", arrayOf(marker)).use { it.moveToFirst() }) continue
        for (table in listOf("media_history", "media_favorites")) {
            // A newly written scoped record wins; never replace it with an older legacy row.
            sql.execSQL("DELETE FROM $table WHERE providerId = ? AND EXISTS(SELECT 1 FROM $table n WHERE n.providerId = ? AND n.itemId = $table.itemId)", arrayOf(old, next))
            sql.execSQL("UPDATE $table SET providerId = ?, stableKey = ? || ':' || itemId WHERE providerId = ?", arrayOf(next, next, old))
        }
        for (table in listOf("library_assets", "recent_searches", "playback_metrics", "source_health", "media_moments", "media_segments", "playlist_exports", "remote_user_states")) {
            sql.execSQL("UPDATE $table SET providerId = ? WHERE providerId = ?", arrayOf(next, old))
        }
        sql.execSQL("UPDATE sync_operations SET scope = ? WHERE scope = ?", arrayOf(next, old))
        // These projections are reconstructible. Retaining their asset membership prevents
        // refresh failure from making a previously visible song disappear.
        val prefix = "${old.length}:$old"; val replacement = "${next.length}:$next"
        for ((table, column) in listOf("catalog_entries" to "scopeId", "catalog_scopes" to "id", "music_tracks" to "albumKey", "music_tracks" to "artistKey")) {
            sql.execSQL("UPDATE $table SET $column = ? || substr($column, ?) WHERE substr($column, 1, ?) = ?", arrayOf(replacement, prefix.length + 1, prefix.length, prefix))
        }
        sql.execSQL("INSERT INTO migration_imports(id) VALUES (?)", arrayOf(marker))
    }
}

package top.cylunex.shadowmedia.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each historical exported schema is seeded and upgraded using the production migration chain. */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(instrumentation, ShadowMediaDatabase::class.java)
    private val migrations = arrayOf(ShadowMediaDatabase.MIGRATION_1_2, ShadowMediaDatabase.MIGRATION_2_3,
        ShadowMediaDatabase.MIGRATION_3_4, ShadowMediaDatabase.MIGRATION_4_5, ShadowMediaDatabase.MIGRATION_5_6, ShadowMediaDatabase.MIGRATION_6_7)

    @Test fun everyHistoricalSchemaPreservesAllLegacyColumnsAndReopens() {
        val latest = 7
        for (version in 1 until latest) {
            val name = "migration-from-$version"
            val schema = instrumentation.context.assets.open("${ShadowMediaDatabase::class.java.name}/$version.json").bufferedReader().use { JSONObject(it.readText()) }
            val entities = schema.getJSONObject("database").getJSONArray("entities")
            val before = linkedMapOf<String, List<Pair<String, String?>>>()
            helper.createDatabase(name, version).use { database ->
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    val fields = entity.getJSONArray("fields")
                    val values = (0 until fields.length()).map { index ->
                        val field = fields.getJSONObject(index)
                        val value = if (!field.getBoolean("notNull")) null else when (field.getString("affinity")) {
                            "INTEGER" -> "1"; "REAL" -> "1.0"; else -> "seed"
                        }
                        field.getString("columnName") to value
                    }
                    database.execSQL("INSERT INTO `$table` (${values.joinToString { "`${it.first}`" }}) VALUES (${values.joinToString { "?" }})", values.map { it.second }.toTypedArray())
                    before[table] = values
                }
            }
            helper.runMigrationsAndValidate(name, latest, true, *migrations).use { migrated ->
                for ((table, values) in before) {
                    migrated.query("SELECT ${values.joinToString { "`${it.first}`" }} FROM `$table`").use { cursor ->
                        assertTrue("Missing historical row: v$version $table", cursor.moveToFirst())
                        values.forEachIndexed { index, value -> assertEquals("v$version $table.${value.first}", value.second, if (cursor.isNull(index)) null else cursor.getString(index)) }
                        assertFalse(cursor.moveToNext())
                    }
                }
                migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
            repeat(2) {
                Room.databaseBuilder(instrumentation.targetContext, ShadowMediaDatabase::class.java, name)
                    .addMigrations(*migrations).build().useDatabase { reopened ->
                        reopened.openHelper.readableDatabase.query("SELECT count(*) FROM media_history").use { cursor -> cursor.moveToFirst(); assertEquals(1, cursor.getInt(0)) }
                    }
            }
            instrumentation.targetContext.deleteDatabase(name)
        }
    }
}

internal inline fun <T> ShadowMediaDatabase.useDatabase(block: (ShadowMediaDatabase) -> T): T = try { block(this) } finally { close() }

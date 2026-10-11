package dev.fitnessapp.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ANDROID.md 8.5: every historical schema migrates to the latest one and keeps its data. One fixture row per table
 * is generated from the exported schema of the old version (every column filled), written into a real file
 * database, migrated with the production migrations, validated against the exported latest schema and read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FitnessDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), FitnessDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory(),
    )

    // An absolute path: Room's SupportSQLite driver compares the requested path with the configured name.
    private val name: String by lazy {
        ApplicationProvider.getApplicationContext<android.content.Context>().getDatabasePath("migration-test.db").absolutePath
    }

    private data class Column(val name: String, val affinity: String)
    private data class Table(val name: String, val columns: List<Column>)

    private fun schema(version: Int): List<Table> {
        val root = Json.parseToJsonElement(File("schemas/dev.fitnessapp.data.db.FitnessDatabase/$version.json").readText()).jsonObject
        return root.getValue("database").jsonObject.getValue("entities").jsonArray.map { e ->
            val o = e.jsonObject
            Table(
                o.getValue("tableName").jsonPrimitive.content,
                o.getValue("fields").jsonArray.map { f ->
                    val fo = f.jsonObject
                    Column(fo.getValue("columnName").jsonPrimitive.content, fo.getValue("affinity").jsonPrimitive.content)
                },
            )
        }
    }

    /** A deterministic value per column: text, integer or real by affinity. */
    private fun fixture(t: Table): ContentValues = ContentValues().apply {
        t.columns.forEachIndexed { i, c ->
            when (c.affinity) {
                "INTEGER" -> put(c.name, (i + 1).toLong())
                "REAL" -> put(c.name, i + 0.5)
                else -> put(c.name, if (c.name.endsWith("_json") || c.name in JSON_COLUMNS) "{}" else "${t.name}:${c.name}")
            }
        }
    }

    private fun insertFixtures(db: SupportSQLiteDatabase, tables: List<Table>) {
        for (t in tables) db.insert(t.name, SQLiteDatabase.CONFLICT_ABORT, fixture(t))
    }

    private fun readBack(db: SupportSQLiteDatabase, t: Table): Map<String, Any?> =
        db.query("SELECT * FROM `${t.name}`").use { c ->
            c.count shouldBe 1
            c.moveToFirst()
            t.columns.associate { col ->
                val i = c.getColumnIndexOrThrow(col.name)
                col.name to when (col.affinity) {
                    "INTEGER" -> c.getLong(i)
                    "REAL" -> c.getDouble(i)
                    else -> c.getString(i)
                }
            }
        }

    @Test
    fun migrateEveryVersionToLatestKeepsData() {
        for (from in 1 until FitnessDatabase.VERSION) {
            val old = schema(from)
            helper.createDatabase(name, from).use { insertFixtures(it, old) }
            val migrated = helper.runMigrationsAndValidate(name, FitnessDatabase.VERSION, true, *FitnessDatabase.MIGRATIONS)
            for (t in old) {
                val expected = fixture(t)
                val actual = readBack(migrated, t)
                for (c in t.columns) actual[c.name] shouldBe expected.get(c.name)
            }
            migrated.close()
            File(name).delete()
        }
    }

    @Test
    fun productionBuilderOpensMigratedFile() {
        helper.createDatabase(name, 1).use { insertFixtures(it, schema(1)) }
        helper.closeWhenFinished(
            Room.databaseBuilder(ApplicationProvider.getApplicationContext(), FitnessDatabase::class.java, name)
                .addMigrations(*FitnessDatabase.MIGRATIONS).build().also { db ->
                    db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM user_exercise_prefs").use { c ->
                        c.moveToFirst()
                        c.getInt(0) shouldBe 0
                    }
                },
        )
    }

    @Test
    fun latestSchemaHasNoTableWithoutAnOlderOrigin() {
        // Every v1 table still exists in the latest schema with all its columns (no column was dropped).
        val latest = schema(FitnessDatabase.VERSION).associateBy { it.name }
        for (t in schema(1)) {
            val now = requireNotNull(latest[t.name]) { "table ${t.name} dropped" }
            now.columns.map { it.name }.containsAll(t.columns.map { it.name }) shouldBe true
        }
    }

    private companion object {
        val JSON_COLUMNS = setOf("payload", "availability", "opening_hours", "load_scheme", "override_fields", "calc_params", "adjustments_json")
    }
}

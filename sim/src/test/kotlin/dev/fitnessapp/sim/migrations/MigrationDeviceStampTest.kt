package dev.fitnessapp.sim.migrations

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The 046 rule (DATA_MODEL 13.2) over every fitness migration from 046 on: rows of a synced table that a migration
 * writes carry the migration device, or the phone that owns them never pulls the change.
 */
class MigrationDeviceStampTest {
    private val root = File(System.getProperty("fitness.repoRoot") ?: ".")

    /** The tables the app syncs, read from the app's own registry so a new synced table is covered at once. */
    private val synced: Set<String> = Regex("""SyncTable\("([a-z_]+)"""")
        .findAll(root.resolve("data/src/main/kotlin/dev/fitnessapp/data/sync/SyncTables.kt").readText())
        .map { it.groupValues[1] }.toSet()

    /** 046 introduced the rule; earlier data migrations were repaired by 046 itself. */
    private val firstVersion = "20261101223501"

    /** Broke the rule before this test existed; its rows were restamped by 068 (version 20261110201512). */
    private val grandfathered = setOf("20261107163247_fitness_065_calf_machines_plates.sql")

    @Test
    fun `data migrations stamp synced rows with the migration device`() {
        check("set_logs" in synced && "gym_equipment" in synced) { "SyncTables.kt not parsed: $synced" }
        val files = (root.resolve("supabase/migrations").listFiles().orEmpty().toList() +
            root.resolve("supabase/migrations/pending").listFiles().orEmpty().toList())
            .filter { it.isFile && it.name.endsWith(".sql") && "fitness_" in it.name }
            .filter { it.parentFile.name == "pending" || it.name.substringBefore('_') >= firstVersion }
            .filter { it.name !in grandfathered }
            .sortedBy { it.name }
        check(files.isNotEmpty()) { "no migrations found under $root" }
        val problems = files.flatMap { f -> MigrationDeviceStamp.check(f.readText(), synced).map { "${f.name}: ${it.kind} ${it.table}: ${it.statement}" } }
        problems.shouldBeEmpty()
    }

    @Test
    fun `065 is caught`() {
        val sql = root.resolve("supabase/migrations/20261107163247_fitness_065_calf_machines_plates.sql").readText()
        MigrationDeviceStamp.check(sql, synced).map { it.kind to it.table }.toSet() shouldBe setOf(
            "insert" to "gym_equipment", "update" to "gym_equipment", "update" to "set_logs",
        )
    }

    @Test
    fun `accepted and rejected forms`() {
        val t = setOf("set_logs", "gym_equipment", "exercises")
        fun bad(sql: String) = MigrationDeviceStamp.check(sql, t).map { it.kind to it.table }

        bad("update fitness.set_logs set reps = 3 where id = 'x';") shouldBe listOf("update" to "set_logs")
        bad("update fitness.set_logs s set device_id = s.device_id, reps = 3;") shouldBe listOf("update" to "set_logs")
        bad("update fitness.set_logs set device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid, reps = 3;").shouldBeEmpty()
        // A subquery's from/where inside the SET list does not hide a later device_id.
        bad("update fitness.set_logs s set reps = (select max(r) from x where y), device_id = v_mig where s.id = 1;\n" +
            "-- v_mig uuid := 'nope'\n").shouldHaveSize(1)
        bad("do \$\$ declare v_mig uuid := 'ffffffff-ffff-ffff-ffff-ffffffffffff'; begin\n" +
            "update fitness.set_logs s set reps = (select max(r) from x where y), device_id = v_mig where s.id = 1; end \$\$;").shouldBeEmpty()
        // Global catalog rows are exempt; unknown tables are ignored.
        bad("update fitness.exercises set catalog_version = '1.0.5' where user_id is null and slug = 'a';").shouldBeEmpty()
        bad("update fitness.exercises x set catalog_version = '1.0.5' where x.user_id is null;").shouldBeEmpty()
        bad("update fitness.set_logs s set reps = 1 from fitness.exercises e where e.id = s.exercise_id and e.user_id is null;") shouldBe
            listOf("update" to "set_logs")
        bad("update fitness.equipment_types set label_en = 'x';").shouldBeEmpty()
        // Inserts: device_id must be listed and the migration device used.
        bad("insert into fitness.gym_equipment (id, user_id, device_id) select v.id, c.user_id, c.device_id from c;") shouldBe
            listOf("insert" to "gym_equipment")
        bad("insert into fitness.gym_equipment (id, user_id) values ('a', 'b');") shouldBe listOf("insert" to "gym_equipment")
        bad("insert into fitness.gym_equipment (id, user_id, device_id) values ('a', 'b', 'ffffffff-ffff-ffff-ffff-ffffffffffff');").shouldBeEmpty()
        // Function bodies are runtime code, not migration writes; dollar literals and comments are not code.
        bad("create or replace function fitness.f() returns void language sql as \$fn\$ update fitness.set_logs set reps = 1; \$fn\$;").shouldBeEmpty()
        bad("select \$j\$ update fitness.set_logs set reps = 1 \$j\$; -- update fitness.set_logs set reps = 1;").shouldBeEmpty()
        // A semicolon inside a string does not split a statement.
        bad("update fitness.set_logs set deviation_note = 'a; b', device_id = 'ffffffff-ffff-ffff-ffff-ffffffffffff';").shouldBeEmpty()
    }
}

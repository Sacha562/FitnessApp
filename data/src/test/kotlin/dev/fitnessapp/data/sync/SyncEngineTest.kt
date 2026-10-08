package dev.fitnessapp.data.sync

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.fitnessapp.algo.core.clock.Clock
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.entity.GymEntity
import dev.fitnessapp.data.db.entity.SessionBlockEntity
import dev.fitnessapp.data.db.entity.WorkoutSessionEntity
import dev.fitnessapp.data.device.DeviceIdentity
import dev.fitnessapp.data.ids.UuidV7Generator
import dev.fitnessapp.data.outbox.OutboxWriter
import dev.fitnessapp.data.repo.WriteContext
import dev.fitnessapp.data.telemetry.EventRegistry
import dev.fitnessapp.data.telemetry.TelemetryWriter
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

/**
 * ANDROID.md 9 / DATA_MODEL 11 against a fake PostgREST that applies the server's LWW drop rule (7.1):
 * push order, stale-write detection, bisection, foreign-key deferral, the outbox generation guard, pull merge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {
    private lateinit var db: FitnessDatabase
    private lateinit var remote: FakeRemote
    private lateinit var engine: SyncEngine
    private lateinit var ctx: WriteContext
    private lateinit var outbox: OutboxWriter
    private var now = 1_791_482_280_000L
    private val uid = "11111111-1111-4111-8111-111111111111"

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, FitnessDatabase::class.java).allowMainThreadQueries().build()
        val device = DeviceIdentity(app)
        ctx = WriteContext(device, "0.1.0-test", Clock { Instant.ofEpochMilli(now) }, { ZoneId.of("Europe/Amsterdam") }, UuidV7Generator({ now }), { uid })
        outbox = OutboxWriter(db)
        remote = FakeRemote(uid)
        engine = SyncEngine(
            db, remote, syncTables(db), outbox, TelemetryWriter(db, outbox, ctx, EventRegistry.bundled()),
            { device.deviceId() }, SyncConfig(pushBatchRows = 50), { now }, "0".repeat(64),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun blocking(block: suspend () -> Unit): Unit = runBlocking { block() }

    private fun session(id: String, cms: Long = ctx.nextClientMs(), device: String = ctx.device.deviceId()) = WorkoutSessionEntity(
        id = id, user_id = uid, client_updated_at = cms, device_id = device, app_version = "0.1.0-test",
        template_source = "empty", local_date = "2026-10-08", timezone = "Europe/Amsterdam", iso_week = "2026-W41",
        started_at = now, engine_version = "1.0.0", params_hash = "a".repeat(64), catalog_version = "1.0.0",
    )

    private fun block(id: String, sessionId: String) = SessionBlockEntity(
        id = id, user_id = uid, client_updated_at = ctx.nextClientMs(), device_id = ctx.device.deviceId(),
        app_version = "0.1.0-test", session_id = sessionId, position = 0, block_kind = "general", origin = "user_built",
    )

    private suspend fun writeSession(s: WorkoutSessionEntity) = db.withTransaction {
        db.sessions().upsert(s)
        outbox.enqueue("workout_sessions", s.id, now)
    }

    @Test
    fun `an expired token that cannot refresh offline retries instead of stopping`() = blocking {
        writeSession(session("s9"))
        remote.signedIn = false
        remote.refreshPending = true
        engine.run("test") shouldBe RunOutcome.RETRY
        engine.state.value shouldBe SyncState.Offline
        db.outbox().pendingCount("workout_sessions", "s9") shouldBe 1
        remote.refreshPending = false
        engine.run("test") shouldBe RunOutcome.STOPPED
        engine.state.value shouldBe SyncState.NeedsSignIn
    }

    @Test
    fun `pushes children after parents and clears the outbox`() = blocking {
        db.withTransaction {
            val b = block("b1", "s1")
            db.blocks().upsert(b)
            outbox.enqueue("session_blocks", b.id, now) // enqueued first ...
        }
        writeSession(session("s1")) // ... but its parent table is pushed first
        engine.run("test") shouldBe RunOutcome.SUCCESS
        remote.pushLog.take(2) shouldBe listOf("workout_sessions", "session_blocks")
        db.outbox().depth() shouldBe 1 // the sync_run event rides with the next run
        remote.rows("workout_sessions").getValue("s1")["params_hash"]!!.jsonPrimitive.content shouldBe "a".repeat(64)
        db.sessions().get("s1")!!.server_updated_at shouldBe remote.rows("workout_sessions").getValue("s1")["server_updated_at"]!!.jsonPrimitive.content
    }

    @Test
    fun `a stale local version is replaced by the newer server row`() = blocking {
        val local = session("s2", cms = now - 10_000)
        writeSession(local)
        remote.seed("workout_sessions", SyncJson.encodeToJsonElement(WorkoutSessionEntity.serializer(), local.copy(client_updated_at = now, device_id = "ffffffff-ffff-ffff-ffff-ffffffffffff", notes = "edited on server")) as JsonObject)
        engine.run("test")
        db.sessions().get("s2")!!.notes shouldBe "edited on server"
        db.outbox().pendingCount("workout_sessions", "s2") shouldBe 0
        db.events().allIds().size shouldBe 2 // sync_conflict + sync_run
    }

    @Test
    fun `a bad row is isolated by bisection and dead-lettered`() = blocking {
        for (i in 1..3) writeSession(session("s$i"))
        remote.failRow["s2"] = SyncFailure(400, "23514")
        engine.run("test") shouldBe RunOutcome.SUCCESS
        remote.rows("workout_sessions").keys shouldBe setOf("s1", "s3")
        db.deadLetters().all().map { it.row_id } shouldBe listOf("s2")
        db.outbox().pendingCount("workout_sessions", "s2") shouldBe 0
    }

    @Test
    fun `foreign-key errors are deferred, not dead-lettered`() = blocking {
        writeSession(session("s1"))
        remote.failRow["s1"] = SyncFailure(409, "23503")
        engine.run("test")
        db.deadLetters().all() shouldBe emptyList()
        val entry = db.outbox().batch("workout_sessions", Long.MAX_VALUE, 10).single()
        entry.attempts shouldBe 1
        (entry.next_attempt_at_ms > now) shouldBe true
    }

    @Test
    fun `an edit during the push is pushed too (generation guard)`() = blocking {
        writeSession(session("s1"))
        remote.duringUpsert = {
            runBlocking {
                db.withTransaction {
                    db.sessions().upsert(db.sessions().get("s1")!!.copy(notes = "edited mid-push", client_updated_at = ctx.nextClientMs()))
                    outbox.enqueue("workout_sessions", "s1", now)
                }
            }
        }
        engine.run("test")
        // Without the (seq, gen) guard the entry would be deleted after the first upsert and the edit lost.
        remote.rows("workout_sessions").getValue("s1")["notes"]!!.jsonPrimitive.content shouldBe "edited mid-push"
        db.outbox().pendingCount("workout_sessions", "s1") shouldBe 0
    }

    @Test
    fun `transient failures keep the outbox and ask for a retry`() = blocking {
        writeSession(session("s1"))
        remote.failTable["workout_sessions"] = SyncFailure(null, null, network = true)
        engine.run("test") shouldBe RunOutcome.RETRY
        db.outbox().pendingCount("workout_sessions", "s1") shouldBe 1
        engine.state.value shouldBe SyncState.Offline
    }

    @Test
    fun `pull inserts rows of other devices and keeps newer local rows`() = blocking {
        val other = "22222222-2222-4222-8222-222222222222"
        val g = GymEntity(id = "g1", user_id = uid, client_updated_at = now, device_id = other, app_version = "x", name = "Basic-Fit")
        remote.seed("gyms", SyncJson.encodeToJsonElement(GymEntity.serializer(), g) as JsonObject)
        val mine = session("s9", cms = now + 5_000)
        db.sessions().upsert(mine) // newer local row, not queued
        remote.seed("workout_sessions", SyncJson.encodeToJsonElement(WorkoutSessionEntity.serializer(), mine.copy(client_updated_at = now, device_id = other, notes = "old")) as JsonObject)
        engine.run("test")
        db.gyms().all().map { it.name } shouldBe listOf("Basic-Fit")
        db.sessions().get("s9")!!.notes shouldBe null
        db.cursors().get("gyms")!!.server_updated_at shouldBe remote.rows("gyms").getValue("g1")["server_updated_at"]!!.jsonPrimitive.content
        // the newer local row was re-enqueued, so the next run pushes it over the older server row
        engine.run("test")
        remote.rows("workout_sessions").getValue("s9")["notes"].toString() shouldBe "null"
    }

    /** In-memory PostgREST with the LWW trigger and injectable failures. */
    private class FakeRemote(private val uid: String) : SyncRemote {
        var signedIn = true
        var refreshPending = false

        override fun authRefreshPending() = refreshPending

        private val tables = sortedMapOf<String, java.util.TreeMap<String, JsonObject>>()
        private var tick = 0
        val pushLog = mutableListOf<String>()
        val failRow = mutableMapOf<String, SyncFailure>()
        val failTable = mutableMapOf<String, SyncFailure>()
        var duringUpsert: (() -> Unit)? = null

        fun rows(t: String) = tables.getOrPut(t) { java.util.TreeMap() }

        private fun stamp(): String {
            tick++
            return "2026-10-08T18:00:%02d.%06d+00:00".format(java.util.Locale.ROOT, tick / 1000, tick % 1_000_000)
        }

        fun seed(t: String, obj: JsonObject) {
            rows(t)[obj["id"]!!.jsonPrimitive.content] = JsonObject(obj + ("server_updated_at" to JsonPrimitive(stamp())))
        }

        override fun userId() = uid.takeIf { signedIn }

        private fun check(t: String, rows: List<JsonObject>) {
            failTable[t]?.let { throw SyncRemoteException(it) }
            rows.firstNotNullOfOrNull { failRow[it["id"]!!.jsonPrimitive.content] }?.let { throw SyncRemoteException(it) }
        }

        private fun tuple(o: JsonObject) = LwwTuple(
            dev.fitnessapp.data.db.EpochMsIso.parse(o["client_updated_at"]!!.jsonPrimitive.content),
            o["device_id"]!!.jsonPrimitive.content,
        )

        override suspend fun upsertMerge(table: String, rows: List<JsonObject>): List<JsonObject> {
            check(table, rows)
            pushLog += table
            duringUpsert?.invoke()
            duringUpsert = null
            val out = mutableListOf<JsonObject>()
            for (r in rows) {
                val id = r["id"]!!.jsonPrimitive.content
                val old = rows(table)[id]
                if (old != null && tuple(r) <= tuple(old)) continue // the trigger drops it
                val stored = JsonObject(r + ("server_updated_at" to JsonPrimitive(stamp())))
                rows(table)[id] = stored
                out += stored
            }
            return out
        }

        override suspend fun insertIgnore(table: String, rows: List<JsonObject>) {
            check(table, rows)
            pushLog += table
            for (r in rows) rows(table).putIfAbsent(r["id"]!!.jsonPrimitive.content, JsonObject(r + ("server_updated_at" to JsonPrimitive(stamp()))))
        }

        override suspend fun selectByIds(table: String, ids: List<String>) = ids.mapNotNull { rows(table)[it] }

        override suspend fun pullPage(table: String, userId: String, excludeDeviceId: String, since: String?, afterTs: String?, afterId: String?, limit: Int): List<JsonObject> {
            failTable[table]?.let { throw SyncRemoteException(it) }
            return rows(table).values
                .filter { it["device_id"]!!.jsonPrimitive.content != excludeDeviceId }
                .filter { o ->
                    val ts = o["server_updated_at"]!!.jsonPrimitive.content
                    val id = o["id"]!!.jsonPrimitive.content
                    when {
                        afterTs != null -> SyncEngine.compareTs(ts, afterTs) > 0 || (ts == afterTs && id > afterId!!)
                        since != null -> SyncEngine.compareTs(ts, since) >= 0
                        else -> true
                    }
                }
                .sortedWith(compareBy<JsonObject>({ it["server_updated_at"]!!.jsonPrimitive.contentOrNull }, { it["id"]!!.jsonPrimitive.content }))
                .take(limit)
        }
    }
}

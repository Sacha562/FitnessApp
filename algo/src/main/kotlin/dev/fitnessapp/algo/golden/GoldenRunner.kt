package dev.fitnessapp.algo.golden

import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.Jcs
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.clock.Week
import dev.fitnessapp.algo.core.params.ParamView
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Runs one golden fixture (ALGORITHMS.md D1 shape: `{id, module, description, input, expected}`) and
 * returns its outputs with their JCS bytes and hash. Used by `GoldenFixtureTest` on the JVM and by the
 * on-device determinism self-test (ANDROID.md 6.3 item 4, guarantee R2). Pure: the caller does the IO and
 * passes the bundled default params (the fixtures' `"params"` label).
 */
object GoldenRunner {
    class Result(val outputs: JsonElement, val outputsJcs: String, val outputsSha256: String)

    fun run(fixture: JsonObject, params: ParamView): Result {
        val input = fixture.getValue("input").jsonObject
        val module = input.getValue("module").jsonPrimitive.content
        val payload = { input.getValue("payload").jsonObject }
        val outputs = when (module.substringBefore('@')) {
            "clock" -> clock(input)
            "fatigue" -> EstimationGolden.fatigue(payload(), params.fork())
            "rcm" -> EstimationGolden.rcm(payload(), params.fork())
            "rircal" -> EstimationGolden.rircal(payload(), params.fork())
            "sse" -> EstimationGolden.sse(payload(), params.fork())
            "hold" -> EstimationGolden.hold(payload(), params.fork())
            "rx" -> PrescriptionGolden.rx(payload(), params.fork())
            "warmup" -> PrescriptionGolden.warmup(payload(), params.fork())
            "rest" -> PrescriptionGolden.rest(payload(), params.fork())
            "reason" -> PrescriptionGolden.reason(payload(), params.fork())
            "stall" -> PrescriptionGolden.stall(payload(), params.fork())
            "autoreg" -> PrescriptionGolden.autoreg(payload(), params.fork())
            "warnings" -> PrescriptionGolden.warnings(payload(), params.fork())
            "setcredit" -> PlanningGolden.setcredit(payload(), params.fork())
            "readiness" -> PlanningGolden.readiness(payload(), params.fork())
            "targets" -> PlanningGolden.targets(payload(), params.fork())
            "allocator" -> SessionGolden.allocator(payload(), params.fork())
            "select" -> SessionGolden.select(payload(), params.fork())
            "weekplan" -> WeekGolden.weekplan(payload(), params.fork())
            "today" -> WeekGolden.today(payload(), params.fork())
            else -> throw IllegalArgumentException("no golden adapter for module $module")
        }
        return Result(outputs, Jcs.canonicalize(outputs), Jcs.sha256Hex(outputs))
    }

    /** CLOCK-1 queries (GF-CLK-*); every key of the payload is optional. */
    private fun clock(input: JsonObject): JsonObject {
        val zone = ZoneId.of(input.getValue("clock").jsonObject.getValue("zone").jsonPrimitive.content)
        val payload = input.getValue("payload").jsonObject
        return buildJsonObject {
            payload["iso_week"]?.let { dates ->
                put("iso_week", strings(dates) { Week.isoWeek(LocalDate.parse(it)) })
            }
            payload["iso_week_start"]?.let { dates ->
                put("iso_week_start", strings(dates) { Week.isoWeekStart(LocalDate.parse(it)).toString() })
            }
            payload["local"]?.let { instants ->
                put("local", buildJsonArray {
                    instants.jsonArray.forEach {
                        val i = Instant.parse(it.jsonPrimitive.content)
                        add(buildJsonObject {
                            put("local_date", Week.localDate(i, zone).toString())
                            put("local_time", hhmm(Week.localTime(i, zone)))
                            put("tz_offset_min", JsonNumbers.int(Week.offsetMinutes(i, zone).toLong()))
                        })
                    }
                })
            }
            payload["elapsed_hours"]?.let { pairs ->
                put("elapsed_hours", buildJsonArray {
                    pairs.jsonArray.forEach {
                        val (a, b) = pair(it)
                        add(JsonNumbers.grid(Week.elapsedHours(Instant.parse(a), Instant.parse(b)), GridKind.RATIO))
                    }
                })
            }
            payload["at_local"]?.let { pairs ->
                put("at_local", buildJsonArray {
                    pairs.jsonArray.forEach {
                        val (d, t) = pair(it)
                        add(JsonPrimitive(Week.atLocal(LocalDate.parse(d), LocalTime.parse(t), zone).toString()))
                    }
                })
            }
            payload["calendar_days"]?.let { pairs ->
                put("calendar_days", buildJsonArray {
                    pairs.jsonArray.forEach {
                        val (d1, d2) = pair(it)
                        add(JsonNumbers.int(Week.calendarDays(LocalDate.parse(d1), LocalDate.parse(d2))))
                    }
                })
            }
        }
    }

    private fun strings(values: JsonElement, f: (String) -> String): JsonArray =
        buildJsonArray { values.jsonArray.forEach { add(JsonPrimitive(f(it.jsonPrimitive.content))) } }

    private fun pair(e: JsonElement): Pair<String, String> {
        val a = e.jsonArray
        require(a.size == 2) { "expected a pair" }
        return a[0].jsonPrimitive.content to a[1].jsonPrimitive.content
    }

    private fun hhmm(t: LocalTime): String {
        fun two(v: Int) = if (v < 10) "0$v" else v.toString()
        return two(t.hour) + ":" + two(t.minute)
    }
}

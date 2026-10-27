package dev.fitnessapp.algo.golden

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.estimation.CurveClass
import dev.fitnessapp.algo.estimation.ExerciseFacts
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.estimation.stall.Stall
import dev.fitnessapp.algo.prescription.autoreg.Autoreg
import dev.fitnessapp.algo.prescription.autoreg.AutoregInput
import dev.fitnessapp.algo.prescription.autoreg.AutoregJson
import dev.fitnessapp.algo.prescription.warnings.ConstraintFacts
import dev.fitnessapp.algo.prescription.warnings.SchemeFacts
import dev.fitnessapp.algo.prescription.warnings.Warning
import dev.fitnessapp.algo.prescription.warnings.Warnings
import dev.fitnessapp.algo.estimation.stall.StallExposure
import dev.fitnessapp.algo.estimation.stall.StallStatus
import dev.fitnessapp.algo.prescription.PrescriptionJson
import dev.fitnessapp.algo.prescription.reason.JointLoad
import dev.fitnessapp.algo.prescription.reason.Reason
import dev.fitnessapp.algo.prescription.rest.RestClass
import dev.fitnessapp.algo.prescription.rest.RestInput
import dev.fitnessapp.algo.prescription.rest.RestPolicy
import dev.fitnessapp.algo.prescription.rest.SideMode
import dev.fitnessapp.algo.prescription.rx.RuleItem
import dev.fitnessapp.algo.prescription.rx.Rx
import dev.fitnessapp.algo.prescription.rx.RxEstimate
import dev.fitnessapp.algo.prescription.rx.RxFlags
import dev.fitnessapp.algo.prescription.rx.RxInput
import dev.fitnessapp.algo.prescription.rx.RxLast
import dev.fitnessapp.algo.prescription.rx.Scheme
import dev.fitnessapp.algo.prescription.rx.SchemeKind
import dev.fitnessapp.algo.prescription.warmup.Warmup
import dev.fitnessapp.algo.prescription.warmup.WarmupInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.util.TreeMap

/** Golden adapters for the prescription modules (ALGORITHMS.md D2 "Prescription": GF-RX, GF-WU, GF-REST, GF-REASON, GF-STALL). */
internal object PrescriptionGolden {
    fun rx(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        put("cases", buildJsonArray {
            payload.getValue("cases").jsonArray.forEach { e ->
                val c = e.jsonObject
                val input = rxInput(c)
                val out = if (str(c, "op") == "model") Rx.modelMode(input, params.fork()) else Rx.prescribe(input, params.fork())
                add(buildJsonObject {
                    put("outputs", PrescriptionJson.rx(out))
                    put("trace", PrescriptionJson.rxTrace(out))
                })
            }
        })
    }

    fun warmup(payload: JsonObject, params: ParamView): JsonObject {
        val i = WarmupInput(
            wWork = dbl(payload, "w_work"), m = opt(payload, "m_kg"), k = dbl(payload, "k"), isolation = bool(payload, "isolation"),
            loadables = loadables(payload.getValue("loadables")), bodyweightW = opt(payload, "bodyweight_w"),
            warm = bool(payload, "warm"), firstForGroup = payload["first_for_group"]?.jsonPrimitive?.booleanOrNull ?: true,
            rStar = opt(payload, "r_star")?.toInt(), testSession = bool(payload, "test_session"),
        )
        return PrescriptionJson.warmup(Warmup.plan(i, params))
    }

    fun rest(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        put("decide", buildJsonArray {
            payload["decide"]?.jsonArray?.forEach { e ->
                val o = e.jsonObject
                val input = RestInput(
                    restClass = RestClass.of(str(o, "rest_class")), challengeRestS = opt(o, "challenge_rest_s"),
                    targetReps = opt(o, "target_reps")?.toInt(), userRestPrefS = opt(o, "user_rest_pref_s"), userRestMult = opt(o, "user_rest_mult"),
                    sideMode = o["side_mode"]?.let { SideMode.valueOf(it.jsonPrimitive.content.uppercase()) } ?: SideMode.NONE,
                    lastQTrue = opt(o, "last_q_true"), lastReps = opt(o, "last_reps")?.toInt(), lastTarget = opt(o, "last_target")?.toInt(),
                    qStar = opt(o, "q_star"), fNext = opt(o, "f_next"),
                )
                add(PrescriptionJson.rest(RestPolicy.decide(input, params.fork())))
            }
        })
        put("pair", buildJsonArray {
            payload["pair"]?.jsonArray?.forEach { e ->
                val a = e.jsonArray.map { d(it) }
                add(JsonNumbers.grid(RestPolicy.pairTimer(a[0], a[1], params), GridKind.S))
            }
        })
        put("readout", buildJsonArray {
            payload["readout"]?.jsonArray?.forEach { e ->
                val a = e.jsonArray.map { d(it) }
                add(JsonNumbers.grid(RestPolicy.readout(a[0], a[1], a[2], a[3], a[4], a[5], params), GridKind.REP))
            }
        })
    }

    fun reason(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        put("rows", buildJsonArray {
            payload.getValue("codes").jsonArray.forEach { c ->
                val r = Reason.row(c.jsonPrimitive.content)
                add(buildJsonObject {
                    put("code", r.code)
                    put("session", r.session.code)
                    put("invalidates_exposure", r.invalidatesExposure)
                    put("excluded_from_stall", r.excludedFromStall)
                    put("excluded_from_fail_counter", r.excludedFromFailCounter)
                    put("counts_for_recovery", r.countsForRecovery)
                })
            }
        })
        payload["pain_stop"]?.jsonObject?.let { p ->
            val joints = p.getValue("joints").jsonArray.map { it.jsonPrimitive.content }
            val ex = p.getValue("exercises").jsonArray.map { e ->
                val o = e.jsonObject
                val js = TreeMap<String, Int>()
                o.getValue("js").jsonObject.forEach { (k, v) -> js[k] = v.jsonPrimitive.content.toInt() }
                JointLoad(str(o, "id"), js)
            }
            put("pain_stop", buildJsonArray {
                p.getValue("scores").jsonArray.forEach { s ->
                    add(buildJsonArray { Reason.painStop(d(s), joints, ex, params).forEach { add(JsonPrimitive(it)) } })
                }
            })
        }
    }

    fun stall(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        payload["evaluate"]?.jsonObject?.let { o ->
            val today = LocalDate.parse(str(o, "today"))
            val ex = o.getValue("exposures").jsonArray.map { e ->
                val x = e.jsonObject
                StallExposure(LocalDate.parse(str(x, "date")), bool(x, "counts"), opt(x, "residual"))
            }
            val r = Stall.evaluate(dbl(o, "g"), dbl(o, "p_gg"), ex, today, o["previous_status"]?.let { StallStatus.valueOf(it.jsonPrimitive.content.uppercase()) },
                o["event_dates"]?.jsonArray?.map { LocalDate.parse(it.jsonPrimitive.content) } ?: emptyList(), null, params)
            put("evaluate", buildJsonObject {
                put("status", r.status.code)
                put("g", JsonNumbers.grid(Grid.stat(r.g), GridKind.STAT))
                put("sd_g", JsonNumbers.grid(Grid.stat(r.sdG), GridKind.STAT))
                put("p_prog", JsonNumbers.grid(Grid.ratio(r.pProg), GridKind.RATIO))
                put("n_exposures", JsonNumbers.int(r.nExposures.toLong()))
                put("event", r.event)
                put("tier_action", r.tierAction.code)
            })
        }
        put("global", buildJsonArray {
            payload["global"]?.jsonArray?.forEach { e ->
                val o = e.jsonObject
                val g = Stall.global(
                    o.getValue("active").jsonArray.map { it.jsonPrimitive.content.toBoolean() },
                    o["deltas"]?.jsonArray?.map { d(it) } ?: emptyList(), o["bad_events"]?.let { d(it).toInt() } ?: 0, params,
                )
                add(buildJsonObject {
                    put("triggers", buildJsonArray { g.triggers.forEach { add(JsonPrimitive(it)) } })
                    put("recommended", g.recommended)
                })
            }
        })
    }

    /**
     * AUTO-1 (GF-AUTO): the plan is RX-1 MODEL of the case at `p_ll` 0 (the posterior mean, B13.6); the logged sets go
     * through SSE-1's shared steps with the exercise's curve class, and AUTO-1 reads the case's `p_ll_autoreg`.
     */
    fun autoreg(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        put("cases", buildJsonArray {
            payload.getValue("cases").jsonArray.forEach { e ->
                val c = e.jsonObject
                val input = rxInput(c)
                val plan = Rx.modelMode(input, params.fork())
                val facts = ExerciseFacts("golden", CurveClass.of(str(c, "curve_class")), dbl(c, "f_bw"), 0.0, sortedMapOf())
                val sets = c.getValue("logged").jsonArray.mapIndexed { n, s ->
                    val o = s.jsonObject
                    val at = Instant.parse("2026-10-27T17:00:00Z").plusSeconds(n * 240L)
                    LoggedSet(
                        id = "g$n", sessionId = "golden", exerciseId = "golden", side = "both", performedAt = at,
                        localDate = LocalDate.parse("2026-10-27"), setKind = "working", contraction = "dynamic",
                        loadMode = LoadMode.EXTERNAL, wSys = dbl(o, "w"), reps = dbl(o, "reps").toInt(), rirReported = opt(o, "rir"),
                        rirLowerBound = bool(o, "lower_bound"), reasonCode = o["reason"]?.jsonPrimitive?.content,
                        restBeforeS = if (n > 0) dbl(c, "rest_s") else null,
                    )
                }
                val prepared = Sse.prepare(sets, mapOf("golden" to facts), RirCal.initial(params), params)
                val r = Autoreg.evaluate(
                    AutoregInput(input.copy(estimate = input.estimate!!.copy(pLL = dbl(c, "p_ll_autoreg"))), plan, prepared),
                    params.fork(),
                )
                add(AutoregJson.result(r))
            }
        })
    }

    /** WE-1 (GF-WE): one evaluation per case, by rule family. */
    fun warnings(payload: JsonObject, params: ParamView): JsonObject {
        val out = ArrayList<Warning>()
        payload.getValue("cases").jsonArray.forEach { e ->
            val c = e.jsonObject
            val entity = str(c, "entity")
            when (str(c, "op")) {
                "improve_weekly_low" -> Warnings.improveWeeklyLow(entity, dbl(c, "x"), dbl(c, "exposures").toInt(), params)?.let { out += it }
                "session_volume_high" -> Warnings.sessionVolumeHigh(entity, dbl(c, "x"), params)?.let { out += it }
                "scheme" -> out += Warnings.scheme(entity, SchemeFacts(
                    rLo = dbl(c, "r_lo").toInt(), rHi = dbl(c, "r_hi").toInt(), qStar = dbl(c, "q_star"),
                    qStarAsked = opt(c, "q_star_asked") ?: dbl(c, "q_star"), strength = bool(c, "strength"), highRisk = bool(c, "high_risk"),
                ), params)
                "constraint" -> out += Warnings.constraint(entity, ConstraintFacts(restDayBefore = bool(c, "rest_day_before")))
                "load_above_cap" -> Warnings.loadAboveCap(entity, dbl(c, "w"), dbl(c, "w_cap"), bool(c, "rule_item"), bool(c, "hold"))?.let { out += it }
                "logged_set" -> Warnings.loggedSet(entity, bool(c, "high_risk"), str(c, "set_kind"), "dynamic", opt(c, "rir"), bool(c, "failed_rep"))?.let { out += it }
                else -> throw IllegalArgumentException("unknown warnings op")
            }
        }
        return Warnings.json(out)
    }

    private fun rxInput(c: JsonObject): RxInput {
        val s = c.getValue("scheme").jsonObject
        val kind = SchemeKind.valueOf(str(s, "kind").uppercase())
        val scheme = Scheme(kind, dbl(s, "r_lo").toInt(), dbl(s, "r_hi").toInt(), dbl(s, "q_star"), null, null,
            s["strength"]?.jsonPrimitive?.booleanOrNull ?: (kind == SchemeKind.FIXED), TreeMap(), emptyList(), emptyList())
        val last = c["last"]?.takeUnless { it is JsonNull }?.jsonObject?.let { l ->
            RxLast(
                dbl(l, "w_last"), ints(l["reps"]), l["q_true"]?.jsonArray?.map { if (it is JsonNull) null else d(it) } ?: emptyList(),
                valid = l["valid"]?.jsonPrimitive?.booleanOrNull ?: true, targets = l["targets"]?.let { ints(it) },
                ext = l["ext"]?.let { d(it).toInt() } ?: 0, failed = bool(l, "failed"),
            )
        }
        val f = c["flags"]?.jsonObject
        val flags = RxFlags(
            deload = f?.let { bool(it, "deload") } ?: false,
            failCounter = f?.get("fail_counter")?.let { d(it).toInt() } ?: 0,
            usedDoubleThisMeso = f?.let { bool(it, "used_double") } ?: false,
        )
        val rule = c["rule"]?.jsonObject?.let { r ->
            RuleItem(ints(r["reps"]).map { it }, wSys = opt(r, "w_sys"), rirTarget = opt(r, "rir_target"), restS = opt(r, "rest_s"), wLastItem = opt(r, "w_last_item"))
        }
        return RxInput(
            exerciseId = "golden", side = c["side"]?.jsonPrimitive?.content ?: "both", isolation = bool(c, "isolation"),
            highRisk = bool(c, "high_risk"), bodyweightOnly = bool(c, "bodyweight_only"), scheme = scheme,
            sets = dbl(c, "sets").toInt(), restsS = listOf(dbl(c, "rest_s")), loadables = loadables(c.getValue("loadables")),
            k = dbl(c, "k"), estimate = opt(c, "m_kg")?.let { RxEstimate(Det.ln(it), opt(c, "p_ll") ?: 0.0) },
            transferM = opt(c, "transfer_m"), last = last, flags = flags, rule = rule,
        )
    }

    /** A list of system loads, or `{base, add: [...]}` (BW plus belt), or `{from, to, step}` (a stack). */
    private fun loadables(e: JsonElement): List<Double> = when {
        e is JsonArray -> e.map { d(it) }
        e.jsonObject.containsKey("add") -> e.jsonObject.let { o -> o.getValue("add").jsonArray.map { Grid.kg(dbl(o, "base") + d(it)) } }
        else -> e.jsonObject.let { o ->
            val from = dbl(o, "from")
            val step = dbl(o, "step")
            val n = kotlin.math.round((dbl(o, "to") - from) / step).toInt()
            (0..n).map { Grid.kg(from + it * step) }
        }
    }

    private fun ints(e: JsonElement?): List<Int> = e?.jsonArray?.map { d(it).toInt() } ?: emptyList()
    private fun d(e: JsonElement): Double = e.jsonPrimitive.content.toDouble()
    private fun dbl(o: JsonObject, k: String): Double = d(o.getValue(k))
    private fun opt(o: JsonObject, k: String): Double? = o[k]?.takeUnless { it is JsonNull }?.let { d(it) }
    private fun str(o: JsonObject, k: String): String = o.getValue(k).jsonPrimitive.content
    private fun bool(o: JsonObject, k: String): Boolean = o[k]?.jsonPrimitive?.booleanOrNull ?: false
}

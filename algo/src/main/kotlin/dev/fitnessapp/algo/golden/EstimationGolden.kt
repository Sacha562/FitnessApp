package dev.fitnessapp.algo.golden

import dev.fitnessapp.algo.core.det.Det
import dev.fitnessapp.algo.core.det.Grid
import dev.fitnessapp.algo.core.det.GridKind
import dev.fitnessapp.algo.core.det.JsonNumbers
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.estimation.CurveClass
import dev.fitnessapp.algo.estimation.EstimationJson
import dev.fitnessapp.algo.estimation.ExerciseFacts
import dev.fitnessapp.algo.estimation.LoggedSet
import dev.fitnessapp.algo.estimation.fatigue.Fatigue
import dev.fitnessapp.algo.estimation.hold.Hold
import dev.fitnessapp.algo.estimation.hold.HoldFacts
import dev.fitnessapp.algo.estimation.hold.HoldSet
import dev.fitnessapp.algo.estimation.hold.HoldState
import dev.fitnessapp.algo.estimation.rcm.Rcm
import dev.fitnessapp.algo.estimation.rcm.RcmState
import dev.fitnessapp.algo.estimation.rcm.RepCurve
import dev.fitnessapp.algo.estimation.rircal.CheckCandidate
import dev.fitnessapp.algo.estimation.rircal.RirCal
import dev.fitnessapp.algo.estimation.sse.SideKey
import dev.fitnessapp.algo.estimation.sse.Sse
import dev.fitnessapp.algo.estimation.sse.SseState
import dev.fitnessapp.algo.estimation.sse.Trend
import dev.fitnessapp.algo.estimation.sse.TrendState
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
import java.time.ZoneId
import java.util.TreeMap

/** Golden adapters for the estimation modules (ALGORITHMS.md D2 "Estimation": GF-FAT, GF-RCM, GF-RIR, GF-SSE, GF-HOLD). */
internal object EstimationGolden {
    private val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")

    fun fatigue(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        val k = Fatigue.constants(params)
        payload["c_rest_s"]?.let { rests ->
            put("c", buildJsonArray { rests.jsonArray.forEach { add(ratio(Fatigue.c(d(it), k))) } })
        }
        payload["to_failure"]?.jsonObject?.let { t ->
            val n = int(t, "sets")
            val ones = List(n) { 1.0 }
            val fs = Fatigue.observed(ones, ones, List(n - 1) { dbl(t, "rest_s") }, 0.0, k)
            put("relative_reps", buildJsonArray { fs.forEach { add(ratio(1.0 - it)) } })
        }
        payload["planned"]?.jsonObject?.let { p ->
            val capFresh = RepCurve.cap(dbl(p, "w_sys"), dbl(p, "m_kg"), dbl(p, "k"))
            val reps = p.getValue("reps").jsonArray.map { d(it) }
            val fs = Fatigue.planned(capFresh, reps, List(reps.size - 1) { dbl(p, "rest_s") }, 0.0, k)
            val qStar = dbl(p, "q_star")
            put("cap_fresh", rep(capFresh))
            put("fatigue_f", buildJsonArray { fs.forEach { add(ratio(it)) } })
            put("rir_pred", buildJsonArray { fs.forEachIndexed { j, f -> add(rep(capFresh * (1.0 - f) - reps[j])) } })
            put("readout_reps_at_q_star", buildJsonArray {
                p.getValue("readout_s").jsonArray.forEach {
                    add(rep(Fatigue.predictNext(capFresh, fs[0], reps[0], capFresh, d(it), k) - qStar))
                }
            })
        }
    }

    fun rcm(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        payload["two_point"]?.let { pairs ->
            put("two_point_k", buildJsonArray {
                pairs.jsonArray.forEach { p ->
                    val a = p.jsonArray.map { d(it) }
                    add(stat(RepCurve.twoPointK(a[0], a[1], a[2], a[3])))
                }
            })
        }
        var state: RcmState? = null
        payload["initial"]?.let { state = Rcm.initial(CurveClass.of(it.jsonPrimitive.content), params) }
        payload["fit"]?.jsonObject?.let { f ->
            val facts = facts(f)
            val rircal = RirCal.initial(params)
            val prepared = sets(f).groupBy { it.sessionId }.toSortedMap().values.flatMap { Sse.prepare(it, facts, rircal, params) }
            val prev = f["prev"]?.takeUnless { it is JsonNull }?.jsonObject?.let { RcmState(dbl(it, "k_used"), dbl(it, "sd_lnk")) }
            val pooled = f["pooled_k"]?.takeUnless { it is JsonNull }?.let { d(it) }
            val fit = Rcm.fit(prepared, LocalDate.parse(str(f, "fit_date")), CurveClass.of(str(f, "curve_class")), prev, pooled, params)
            state = fit.state
            put("fit", EstimationJson.rcm(fit))
        }
        payload["interval"]?.jsonObject?.let { i ->
            val s = requireNotNull(state) { "interval needs a fit or an initial state" }
            val (w, r, q, bw) = listOf(dbl(i, "w_sys"), dbl(i, "reps"), dbl(i, "q"), dbl(i, "bw_kg"))
            val (lo, hi) = RepCurve.e1rmInterval(w, r, q, s.kUsed, s.sdLnk, params.double("sse.ci_z"))
            put("interval", buildJsonObject {
                put("e1rm_added_kg", kg(RepCurve.e1rm(w, r, q, s.kUsed) - bw))
                put("ci80_lo_added_kg", kg(lo - bw))
                put("ci80_hi_added_kg", kg(hi - bw))
            })
        }
    }

    fun rircal(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        payload["explicit"]?.jsonObject?.let { e ->
            val u = RirCal.explicit(
                RirCal.initial(params), curve(e), dbl(e, "q_pred"), int(e, "extra"), LocalDate.parse(str(e, "date")), params,
            )
            put("explicit", EstimationJson.rircal(u))
        }
        payload["schedule"]?.jsonObject?.let { s ->
            val c = s.getValue("candidate").jsonObject
            val cand = CheckCandidate(
                str(c, "exercise_id"), int(c, "plan_position"), curve(c), str(c, "safety_class"),
                challenge = false, ruleMode = false, jointFlagged = false,
            )
            var state = RirCal.initial(params)
            put("chosen", buildJsonArray {
                s.getValue("dates").jsonArray.forEach { dateEl ->
                    val date = LocalDate.parse(dateEl.jsonPrimitive.content)
                    val chosen = RirCal.chooseCheck(listOf(cand), true, false, date, state, params)
                    if (chosen != null) state = RirCal.explicit(state, chosen.curveClass, 2.0, 4, date, params).state
                    add(chosen?.let { JsonPrimitive(it.exerciseId) } ?: JsonNull)
                }
            })
        }
    }

    fun sse(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        val priors = TreeMap<SideKey, SseState>()
        payload["priors"]?.jsonArray?.forEach { p ->
            val o = p.jsonObject
            priors[SideKey(str(o, "exercise_id"), str(o, "side"))] = SseState(trend(o), dbl(o, "k"))
        }
        payload["predict"]?.jsonObject?.let { p ->
            val t = trend(p.getValue("state").jsonObject)
            put("predict", buildJsonArray {
                p.getValue("at").jsonArray.forEach { at ->
                    val s = Trend.predict(t, Instant.parse(at.jsonPrimitive.content), params)
                    add(buildJsonObject {
                        put("m_kg", kg(Det.exp(s.l)))
                        put("sd_ln", stat(Det.sqrt(s.pLL)))
                        put("g_per_week", stat(s.g))
                    })
                }
            })
        }
        payload["runs"]?.let { runs ->
            put("runs", buildJsonArray {
                runs.jsonArray.forEach { runEl ->
                    val run = runEl.jsonObject
                    val facts = facts(run)
                    val prepared = Sse.prepare(sets(run), facts, RirCal.initial(params), params)
                    val kBy = TreeMap<String, Double>()
                    run["k_by_exercise"]?.jsonObject?.forEach { (ex, v) -> kBy[ex] = d(v) }
                    val r = Sse.update(
                        prepared, priors, kBy, emptyMap(), facts, Instant.parse(str(run, "session_start")),
                        run["bw_kg"]?.let { d(it) }, params,
                    )
                    add(buildJsonObject {
                        put("outputs", EstimationJson.sse(r))
                        put("trace", EstimationJson.sseTrace(r))
                    })
                }
            })
        }
    }

    fun hold(payload: JsonObject, params: ParamView): JsonObject = buildJsonObject {
        val facts = TreeMap<String, HoldFacts>()
        payload.getValue("facts").jsonArray.forEach { f ->
            val o = f.jsonObject
            facts[str(o, "exercise_id")] = HoldFacts(str(o, "exercise_id"), o["hold_class"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content, str(o, "pattern"), dbl(o, "f_bw"))
        }
        val holds = payload.getValue("sets").jsonArray.map { e ->
            val o = e.jsonObject
            HoldSet(
                str(o, "id"), str(o, "exercise_id"), str(o, "side"), Instant.parse(str(o, "performed_at")), str(o, "set_kind"),
                o["w_sys"]?.takeUnless { it is JsonNull }?.let { d(it) }, o["external_kg"]?.let { d(it) } ?: 0.0, dbl(o, "t_done_s"),
                o["chip"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            )
        }
        val r = Hold.update(holds, facts, emptyMap(), Instant.parse(str(payload, "session_start")), params)
        put("outputs", EstimationJson.hold(r))
        put("steps", EstimationJson.steps(r.trace))
        payload["prescribe"]?.jsonObject?.let { p ->
            val key = SideKey(str(p, "exercise_id"), str(p, "side"))
            val st: HoldState = r.next.getValue(key)
            val beta = Hold.beta(facts.getValue(key.exerciseId), params)
            val options = p["options"]?.jsonArray?.map { d(it) } ?: emptyList()
            val rx = Hold.prescribe(st, beta, options, dbl(p, "t_lo"), dbl(p, "t_hi"), 0.0, params)
            put("prescribe", buildJsonObject {
                put("options", buildJsonArray {
                    options.forEach { w ->
                        val cap = Hold.tCap(st, w, beta)
                        add(buildJsonObject {
                            put("w_sys", kg(w))
                            put("t_cap_s", JsonNumbers.grid(Grid.round(cap, GridKind.REP), GridKind.REP))
                            put("t_target_s", JsonNumbers.grid(Grid.round(Hold.tTarget(cap, 0.0, params), GridKind.REP), GridKind.REP))
                        })
                    }
                })
                put("w_sys", rx.wSys?.let { kg(it) } ?: JsonNull)
                put("t_star_s", JsonNumbers.grid(Grid.s(rx.tTargetS), GridKind.S))
                put("feasible", rx.feasible)
            })
        }
    }

    private fun sets(o: JsonObject): List<LoggedSet> = o.getValue("sets").jsonArray.map { e ->
        val s = e.jsonObject
        val at = Instant.parse(str(s, "performed_at"))
        LoggedSet(
            id = str(s, "id"),
            sessionId = s["session_id"]?.jsonPrimitive?.content ?: "s1",
            exerciseId = str(s, "exercise_id"),
            side = s["side"]?.jsonPrimitive?.content ?: "both",
            performedAt = at,
            localDate = LocalDate.ofInstant(at, ZONE),
            setKind = s["set_kind"]?.jsonPrimitive?.content ?: "working",
            contraction = s["contraction"]?.jsonPrimitive?.content ?: "dynamic",
            loadMode = LoadMode.of(s["load_mode"]?.jsonPrimitive?.content ?: "bodyweight_plus_external"),
            wSys = s["w_sys"]?.takeUnless { it is JsonNull }?.let { d(it) },
            reps = int(s, "reps"),
            rirReported = s["rir"]?.takeUnless { it is JsonNull }?.let { d(it) },
            rirLowerBound = s["rir_lower_bound"]?.jsonPrimitive?.booleanOrNull ?: false,
            failedRep = s["failed_rep"]?.jsonPrimitive?.booleanOrNull ?: false,
            fingerAssisted = s["finger_assisted"]?.jsonPrimitive?.booleanOrNull ?: false,
            reasonCode = s["reason_code"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            restBeforeS = s["rest_before_s"]?.takeUnless { it is JsonNull }?.let { d(it) },
        )
    }

    private fun facts(o: JsonObject): Map<String, ExerciseFacts> {
        val out = TreeMap<String, ExerciseFacts>()
        o["facts"]?.jsonArray?.forEach { e ->
            val f = e.jsonObject
            val coeffs = TreeMap<String, Double>()
            f["group_coeffs"]?.jsonObject?.forEach { (g, v) -> coeffs[g] = d(v) }
            out[str(f, "exercise_id")] = ExerciseFacts(
                str(f, "exercise_id"), f["curve_class"]?.takeUnless { it is JsonNull }?.let { CurveClass.of(it.jsonPrimitive.content) },
                dbl(f, "f_bw"), f["implement_base_kg"]?.let { d(it) } ?: 0.0, coeffs,
            )
        }
        return out
    }

    private fun trend(o: JsonObject) = TrendState(
        l = Det.ln(dbl(o, "m_kg")),
        g = dbl(o, "g"),
        pLL = dbl(o, "p_ll"),
        pLg = dbl(o, "p_lg"),
        pgg = dbl(o, "p_gg"),
        tLast = Instant.parse(str(o, "t_last")),
        nObs = int(o, "n_obs"),
    )

    private fun curve(o: JsonObject): CurveClass? = o["curve_class"]?.takeUnless { it is JsonNull }?.let { CurveClass.of(it.jsonPrimitive.content) }
    private fun d(e: JsonElement): Double = e.jsonPrimitive.content.toDouble()
    private fun dbl(o: JsonObject, k: String): Double = d(o.getValue(k))
    private fun int(o: JsonObject, k: String): Int = o.getValue(k).jsonPrimitive.content.toInt()
    private fun str(o: JsonObject, k: String): String = o.getValue(k).jsonPrimitive.content
    private fun ratio(x: Double) = JsonNumbers.grid(Grid.ratio(x), GridKind.RATIO)
    private fun rep(x: Double) = JsonNumbers.grid(Grid.rep(x), GridKind.REP)
    private fun kg(x: Double) = JsonNumbers.grid(Grid.kg(x), GridKind.KG)
    private fun stat(x: Double) = JsonNumbers.grid(Grid.stat(x), GridKind.STAT)
}

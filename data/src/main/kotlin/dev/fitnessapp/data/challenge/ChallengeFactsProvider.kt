package dev.fitnessapp.data.challenge

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.enrollment.EnrollmentState
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.EngineFacts
import dev.fitnessapp.algo.challenge.fitexpr.LoadProfile
import dev.fitnessapp.algo.challenge.fitexpr.MachineCalibration
import dev.fitnessapp.algo.challenge.fitexpr.UserKFact
import dev.fitnessapp.algo.challenge.fitexpr.Value
import dev.fitnessapp.algo.challenge.materialize.ExerciseResolver
import dev.fitnessapp.algo.core.load.ExerciseLoadAttrs
import dev.fitnessapp.algo.core.load.Laterality
import dev.fitnessapp.algo.core.load.LoadMode
import dev.fitnessapp.algo.core.load.LoadScheme
import dev.fitnessapp.algo.core.load.Loadables
import dev.fitnessapp.algo.core.params.ParamView
import dev.fitnessapp.algo.selection.catalog.CatalogDoc
import dev.fitnessapp.data.db.FitnessDatabase
import dev.fitnessapp.data.db.JsonText
import dev.fitnessapp.data.db.Scaled
import dev.fitnessapp.data.repo.EquipmentRepository
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.SortedMap
import java.util.TreeMap

/**
 * The engine-fact snapshot of CHALLENGE_ENGINE 3.9 as the phone knows it, frozen into each event the app writes:
 * the latest weigh-in, the assisted machine's calibration (or the definition's defaults until calibrated), the
 * belt's achievable loads, the available equipment, the last pain date per body region and RCM-1's `k` per
 * definition exercise. Until RCM-1 runs (M3) `k` is the class prior `rcm.k0` with `rcm.sigma_lnk` (C59).
 */
class ChallengeFactsProvider(
    private val db: FitnessDatabase,
    private val equipment: EquipmentRepository,
    private val catalog: () -> CatalogDoc,
    private val params: ParamView,
    private val now: () -> Instant,
) {
    /**
     * Before an enrollment exists ([state] null) the grip-dependent exercises resolve with the definition's param
     * defaults and the setup's choices ([setupParams]), so the creation's facts carry their `k` (D37: without the
     * weighted pull-up's `k` its `anchorFraction` init failed).
     */
    suspend fun facts(
        def: CompiledDefinition,
        state: EnrollmentState?,
        weekPlanSlots: SortedMap<LocalDate, List<String>> = sortedMapOf(),
        setupParams: Map<String, Value> = emptyMap(),
    ): EngineFacts {
        val rows = equipment.equipment().filter { it.available && it.deleted_at == null }
        val bw = db.metrics().byMetric("bw").filter { it.value_num != null }.maxByOrNull { it.observed_at }
            ?.let { Dec.norm(Scaled.toDecimal(it.value_num!!, 3).setScale(2, java.math.RoundingMode.HALF_EVEN)) }
        val machines = TreeMap<String, MachineCalibration>()
        for (req in def.doc.equipment.required.filter { it.binding == "machineCalibration" }) {
            val row = rows.firstOrNull { it.equipment_type == req.id }
            val scheme = row?.let { EquipmentRepository.parseScheme(it.load_scheme) } as? LoadScheme.PinAssist
                ?: defaults(req.defaults) ?: continue
            machines[req.id] = MachineCalibration(
                scheme.unit, dec(scheme.step), dec(scheme.min), scheme.max?.let(::dec), dec(scheme.offsetKg), dec(scheme.ratio), row?.calibration_rev ?: 0,
            )
        }
        val profiles = TreeMap<String, LoadProfile>()
        for (req in def.doc.equipment.required.filter { it.roundingProfile != null }) {
            val row = rows.firstOrNull { it.equipment_type == req.id }
            val scheme = row?.let { EquipmentRepository.parseScheme(it.load_scheme) }
                ?: equipment.types.firstOrNull { it.key == req.id }?.defaultLoadScheme?.let { EquipmentRepository.parseScheme(JsonText.canonical(it)) }
                ?: continue
            val attrs = ExerciseLoadAttrs(1.0, 0.0, Laterality.BILATERAL, listOf(LoadMode.BODYWEIGHT_PLUS_EXTERNAL))
            val points = Loadables.enumerate(scheme, Loadables.Context(attrs, null, row?.id, row?.calibration_rev), params)
            val kgs = (listOf(BigDecimal.ZERO) + points.map { p -> Dec.kg(p.addedKg) }).map(Dec::norm).distinct().sorted()
            profiles[req.roundingProfile!!] = LoadProfile(kgs, row?.calibration_rev ?: 0)
        }
        val pain = TreeMap<String, LocalDate>()
        for (w in db.wellness().painLogs()) {
            val region = w.pain_body_region ?: continue
            val d = LocalDate.parse(w.local_date)
            if (pain[region]?.isBefore(d) != false) pain[region] = d
        }
        val effective = state?.let { dev.fitnessapp.algo.challenge.eca.RuleEnv.effectiveParams(def, it) }
            ?: TreeMap<String, Value>().apply {
                for ((k, p) in def.params) put(k, p.default)
                for ((k, v) in setupParams) if (def.params.containsKey(k)) put(k, v)
            }
        val resolver = ExerciseResolver(def, catalog(), effective)
        val userK = TreeMap<String, UserKFact>()
        val k0 = params.table("rcm.k0")
        val bySlug = catalog().exercises.associateBy { it.slug }
        for (spec in def.doc.exercises) {
            val slug = resolver.slugOf(spec) ?: continue
            val k = bySlug[slug]?.curveClass?.let { k0[it] } ?: continue
            userK[spec.key] = UserKFact(slug, Dec.ofParam(k), Dec.ofParam(params.double("rcm.sigma_lnk")), null, 0)
        }
        return EngineFacts(
            capturedAt = now(),
            bwKg = bw,
            userK = userK,
            painLastDate = pain,
            has = rows.map { it.equipment_type }.distinct().sorted(),
            machines = machines,
            loadProfiles = profiles,
            weekPlanSlots = weekPlanSlots,
        )
    }

    private fun dec(x: Double): BigDecimal = Dec.norm(BigDecimal(x).setScale(4, java.math.RoundingMode.HALF_EVEN))

    /** The definition's calibration defaults (`unit, step, min, max, offsetKg, ratio`) as a pin-assist scheme. */
    private fun defaults(o: JsonObject?): LoadScheme.PinAssist? = o?.let {
        val scheme = JsonObject(
            mapOf(
                "type" to JsonPrimitive("pin_assist"), "unit" to (it["unit"] ?: JsonPrimitive("lb")), "step" to (it["step"] ?: JsonNull),
                "min" to (it["min"] ?: JsonNull), "max" to (it["max"] ?: JsonNull), "offset_kg" to (it["offsetKg"] ?: JsonNull),
                "ratio" to (it["ratio"] ?: JsonPrimitive(1)),
            ),
        )
        EquipmentRepository.parseScheme(JsonText.canonical(scheme)) as? LoadScheme.PinAssist
    }
}

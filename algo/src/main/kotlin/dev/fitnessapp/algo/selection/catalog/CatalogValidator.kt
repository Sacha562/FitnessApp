package dev.fitnessapp.algo.selection.catalog

import dev.fitnessapp.algo.selection.catalog.CatalogDomains as D

/** Catalog-level thresholds (params `catalog.validator`, ALGORITHMS_PLANNING 10.7). */
data class CatalogValidatorParams(
    val minPerGroup: Int = 3,
    val minDirectPerGroup: Int = 1,
    val minEquipmentClasses: Int = 2,
    val minSubSimilarity: Double = 0.6,
    /** A group counts an exercise when its group coefficient is at least this (fractional-set credit, C44). */
    val creditMinCoeff: Double = 0.5,
)

data class CatalogIssue(val code: String, val slug: String?, val message: String) {
    override fun toString(): String = "$code ${slug ?: "-"}: $message"
}

data class GroupCoverage(
    val groupId: String,
    val exercises: Int,
    val direct: Int,
    val equipmentClasses: List<String>,
)

data class CatalogReport(
    val issues: List<CatalogIssue>,
    val coverage: List<GroupCoverage>,
    /** Best substitute similarity per exercise checked by V10 (slug -> (substitute slug, similarity)). */
    val bestSubstitutes: List<Triple<String, String, Double>>,
) {
    val ok: Boolean get() = issues.isEmpty()
}

/**
 * CAT-1 validator (ALGORITHMS_PLANNING 10.7): row rules V1-V8, V11-V12 for every row (seed rows, custom rows on
 * save, challenge-local rows) and the catalog-level rules V9-V10 for a whole seed. Unknown keys are errors,
 * never silently dropped (C9). Seed rows must use canonical region ids; aliases are rewritten only for drafts.
 */
object CatalogValidator {

    fun validate(
        catalog: Catalog,
        stationKeys: Set<String>,
        params: CatalogValidatorParams = CatalogValidatorParams(),
        challengeLocalSlugs: Set<String> = emptySet(),
        requiredSlugs: Set<String> = emptySet(),
    ): CatalogReport {
        val issues = mutableListOf<CatalogIssue>()
        val seen = sortedSetOf<String>()
        for (e in catalog.doc.exercises) {
            if (!seen.add(e.slug)) issues += CatalogIssue("DUP", e.slug, "duplicate slug")
            issues += validateRow(catalog, e, stationKeys, seed = true)
            if (e.slug in challengeLocalSlugs && "challenge" !in e.tags) {
                issues += CatalogIssue("V12", e.slug, "challenge-local row without the challenge tag")
            }
        }
        for (slug in requiredSlugs.sorted()) {
            if (catalog.exercise(slug) == null) issues += CatalogIssue("C38", slug, "slug referenced by a challenge definition is missing")
        }
        for (r in catalog.doc.transferRatios) {
            if (catalog.exercise(r.fromSlug) == null || catalog.exercise(r.toSlug) == null) {
                issues += CatalogIssue("TR", r.fromSlug, "transfer ratio references an unknown slug (${r.toSlug})")
            }
            if (r.ratio < 0.2 || r.ratio > 3.0) issues += CatalogIssue("TR", r.fromSlug, "ratio ${r.ratio} outside 0.2-3.0")
            if (r.fromSlug == r.toSlug) issues += CatalogIssue("TR", r.fromSlug, "ratio from an exercise to itself")
        }
        val coverage = coverage(catalog, params)
        for (c in coverage) {
            if (c.exercises < params.minPerGroup) {
                issues += CatalogIssue("V9", null, "group ${c.groupId}: ${c.exercises} exercises < ${params.minPerGroup}")
            }
            if (c.direct < params.minDirectPerGroup) {
                issues += CatalogIssue("V9", null, "group ${c.groupId}: no exercise with group coefficient 1.0")
            }
            if (c.equipmentClasses.size < params.minEquipmentClasses) {
                issues += CatalogIssue("V9", null, "group ${c.groupId}: equipment classes ${c.equipmentClasses}")
            }
        }
        val best = substitutes(catalog)
        for ((slug, sub, sim) in best) {
            if (sim < params.minSubSimilarity) {
                issues += CatalogIssue("V10", slug, "best substitute $sub has similarity $sim < ${params.minSubSimilarity}")
            }
        }
        return CatalogReport(issues, coverage, best)
    }

    /** Row rules V1-V8 and V11 (plus the DATA_MODEL 9.2.1 column domains). */
    fun validateRow(catalog: Catalog, e: CatalogExercise, stationKeys: Set<String>, seed: Boolean): List<CatalogIssue> {
        val out = mutableListOf<CatalogIssue>()
        fun err(code: String, msg: String) { out += CatalogIssue(code, e.slug, msg) }

        if (!D.SLUG.matches(e.slug)) err("E54", "slug does not match ${D.SLUG.pattern}")
        if (e.nameEn.isBlank() || e.nameEn.length > 80) err("V8", "name_en must have 1-80 characters")
        if (seed && (e.nameNl.isNullOrBlank() || e.nameNl.length > 80)) err("V8", "seed rows need name_nl (D12)")

        // V1 / V2 / V3 / V4: coefficients.
        for ((region, coeff) in e.stimulus.toSortedMap()) {
            if (coeff !in D.COEFF_LEVELS) err("V1", "coefficient $coeff of $region is not a C10 level")
            val canonical = catalog.taxonomy.canonicalRegion(region)
            when {
                canonical == null -> err("V2", "unknown region $region")
                canonical != region -> err("V2", "region alias $region must be written as $canonical")
            }
        }
        val exempt = e.tags.any { it in setOf("prehab", "skill", "hang") } || e.pattern == "conditioning"
        if (!exempt && e.stimulus.values.none { it >= 0.75 }) err("V3", "no region at 0.75 or above")
        if (e.stimulus.values.any { it == 0.75 } && e.evidenceNote.isNullOrBlank()) err("V4", "a 0.75 coefficient needs an evidence_note")

        // V5: pattern, family, load modes, bodyweight fraction.
        if (e.pattern !in D.PATTERNS) err("V5", "unknown pattern ${e.pattern}")
        else if (D.FAMILY_OF_PATTERN[e.pattern] != e.family) err("V5", "family ${e.family} does not match pattern ${e.pattern}")
        if (e.allowedLoadModes.isEmpty() || e.allowedLoadModes.any { it !in D.LOAD_MODES }) err("V5", "allowed_load_modes ${e.allowedLoadModes}")
        if (e.loadMode !in e.allowedLoadModes) err("V5", "load_mode ${e.loadMode} not in allowed_load_modes")
        val bwMode = e.allowedLoadModes.any { it in D.BODYWEIGHT_MODES }
        if (bwMode != (e.bwFraction > 0.0)) err("V5", "bw_fraction ${e.bwFraction} must be > 0 iff a bodyweight mode is allowed")
        if (e.bwFraction < 0.0 || e.bwFraction > 1.0) err("V5", "bw_fraction outside 0-1")
        e.implementBaseKg?.let { if (it < 0) err("V5", "implement_base_kg < 0") }

        // V6: stations.
        for (s in e.requiresStation) if (s !in stationKeys) err("V6", "unknown station $s")

        // V7: curve class and hold class.
        val hold = e.contraction in D.HOLD_CONTRACTIONS
        if (e.contraction !in D.CONTRACTIONS) err("V7", "unknown contraction ${e.contraction}")
        if (e.contraction == "dynamic" && e.mechanics != "conditioning" && e.curveClass == null) {
            err("V7", "dynamic exercise without curve_class")
        }
        if (e.curveClass != null && e.curveClass !in D.CURVE_CLASSES) err("V7", "unknown curve_class ${e.curveClass}")
        if (hold && e.measure != "duration") err("V7", "holds and hangs are measured by duration")
        if (hold != (e.holdClass != null)) err("V7", "hold_class must be set exactly for holds and hangs")
        if (e.holdClass != null && e.holdClass !in D.HOLD_CLASSES) err("V7", "unknown hold_class ${e.holdClass}")

        // Column domains (DATA_MODEL 9.2.1), mirrored so CI fails before the database would.
        if (e.equipment.isEmpty() || e.equipment.any { it !in D.EQUIPMENT_CLASSES }) err("DOM", "equipment ${e.equipment}")
        if (e.laterality !in D.LATERALITY) err("DOM", "laterality ${e.laterality}")
        if (e.measure !in D.MEASURES) err("DOM", "measure ${e.measure}")
        if (e.mechanics !in D.MECHANICS) err("DOM", "mechanics ${e.mechanics}")
        if (e.restClass !in D.REST_CLASSES) err("DOM", "rest_class ${e.restClass}")
        if (e.safetyClass !in D.SAFETY_CLASSES) err("DOM", "safety_class ${e.safetyClass}")
        if (e.grip !in D.GRIPS) err("DOM", "grip ${e.grip}")
        if (e.stretchEmphasis !in 0..2 || e.stability !in 1..3 || e.skill !in 1..3 || e.axial !in 0..3 ||
            e.systemic !in 1..3 || e.sfr !in 1..5 || e.setupS !in 0..600 || e.gripDemand !in 0..3
        ) err("DOM", "attribute level out of range")
        for ((j, v) in e.jointStress.toSortedMap()) {
            if (j !in D.JOINTS) err("DOM", "unknown joint $j")
            if (v !in 0..3) err("DOM", "joint stress $j = $v out of 0-3")
        }
        for (t in e.tags) if (t !in D.RESERVED_TAGS) err("DOM", "tag $t is not a reserved tag")
        for (f in e.flagsOffered) if (f !in D.SET_FLAGS) err("DOM", "flag $f is not an E6 flag")
        for (a in e.aliases) if (a.isBlank() || a.length > 80) err("DOM", "alias '$a'")

        // V11: stored derived tags must agree with the derivation.
        val derived = catalog.derivedTags(e).toSet()
        for (t in listOf("grip_heavy", "elbow_flexor_heavy", "pull_heavy", "supinated_curl", "axial_heavy", "overhead_press_heavy")) {
            if (t in e.tags && t !in derived) err("V11", "stored tag $t contradicts its derivation")
        }
        return out
    }

    /** V9 input: per active group (except optional ones), the non-challenge exercises that earn credit. */
    fun coverage(catalog: Catalog, params: CatalogValidatorParams): List<GroupCoverage> =
        catalog.taxonomy.activeGroups.filter { !it.isOptional }.map { g ->
            val members = catalog.exercises.filter { "challenge" !in it.tags && catalog.groupCoeff(it, g.id) >= params.creditMinCoeff }
            GroupCoverage(
                groupId = g.id,
                exercises = members.size,
                direct = members.count { catalog.groupCoeff(it, g.id) >= 1.0 },
                equipmentClasses = members.flatMap { it.equipment }.toSortedSet().toList(),
            )
        }

    /**
     * V10 input: for every exercise not tagged skill/challenge/prehab (and not conditioning, whose vector is zero),
     * the most similar other non-challenge exercise; ties by slug.
     */
    fun substitutes(catalog: Catalog): List<Triple<String, String, Double>> {
        val pool = catalog.exercises.filter { "challenge" !in it.tags && it.pattern != "conditioning" }
        return pool
            .filter { e -> e.tags.none { it in setOf("skill", "challenge", "prehab") } }
            .map { e ->
                val best = pool.filter { it.slug != e.slug }
                    .map { it.slug to catalog.similarity(e, it) }
                    .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
                    .first()
                Triple(e.slug, best.first, best.second)
            }
    }
}

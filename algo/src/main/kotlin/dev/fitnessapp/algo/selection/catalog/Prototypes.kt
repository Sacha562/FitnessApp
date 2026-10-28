package dev.fitnessapp.algo.selection.catalog

/** What the custom-exercise form chose (UX 13.5 steps 1, 2 and 4); [stimulus] null keeps the prototype's coefficients. */
data class CustomDraft(
    val slug: String,
    val nameEn: String,
    val nameNl: String?,
    val pattern: String,
    val equipmentClass: String,
    val stations: List<String>,
    val loadMode: String,
    val unilateral: Boolean,
    val timed: Boolean,
    val flags: List<String>,
    val stimulus: Map<String, Double>? = null,
)

/**
 * CAT-1 10.9: every custom exercise is a copy of a prototype, one per (pattern, equipment class) pair present in the
 * seed, taken from the seed row with the lowest slug. Challenge-local rows are never prototypes. The copy keeps the
 * prototype's attributes and coefficients; the form's choices for laterality and timing adjust the fields CAT-1's
 * validator ties to them (V5, V7), so a copy is valid unless the user's coefficient edits break V1-V3.
 */
object Prototypes {
    fun table(doc: CatalogDoc): Map<Pair<String, String>, CatalogExercise> {
        val out = sortedMapOf<String, CatalogExercise>()
        for (e in doc.exercises.filter { "challenge" !in it.tags }.sortedBy { it.slug }) {
            for (eq in e.equipment) out.putIfAbsent(e.pattern + "|" + eq, e)
        }
        return out.entries.associate { (k, v) -> (k.substringBefore('|') to k.substringAfter('|')) to v }
    }

    /** The equipment classes the seed has a prototype for, for [pattern], in E45 order. */
    fun equipmentFor(table: Map<Pair<String, String>, CatalogExercise>, pattern: String): List<String> =
        CatalogDomains.EQUIPMENT_CLASSES.filter { (pattern to it) in table }

    /** The patterns with at least one prototype, in E36 order. */
    fun patterns(table: Map<Pair<String, String>, CatalogExercise>): List<String> =
        CatalogDomains.PATTERNS.filter { p -> table.keys.any { it.first == p } }

    fun copy(proto: CatalogExercise, d: CustomDraft, catalogVersion: String): CatalogExercise {
        val family = CatalogDomains.FAMILY_OF_PATTERN.getValue(d.pattern)
        val lower = family in LOWER_FAMILIES
        val laterality = when {
            d.unilateral && proto.laterality == "bilateral" -> "unilateral"
            !d.unilateral -> "bilateral"
            else -> proto.laterality
        }
        val wasTimed = proto.measure == "duration"
        val hold = d.timed
        val tags = proto.tags.filter { it !in DROPPED_TAGS }.toMutableSet()
        if (laterality == "bilateral") tags -= UNILATERAL_TAGS else tags += "unilateral"
        return proto.copy(
            slug = d.slug,
            nameEn = d.nameEn.trim(),
            nameNl = d.nameNl?.trim()?.takeIf { it.isNotEmpty() },
            aliases = emptyList(),
            pattern = d.pattern,
            family = family,
            equipment = listOf(d.equipmentClass),
            requiresStation = d.stations.sorted(),
            loadMode = d.loadMode,
            laterality = laterality,
            measure = if (hold) "duration" else if (wasTimed) "reps" else proto.measure,
            contraction = when {
                hold && wasTimed -> proto.contraction
                hold -> "isometric"
                wasTimed -> "dynamic"
                else -> proto.contraction
            },
            holdClass = when {
                hold && wasTimed -> proto.holdClass
                hold -> HOLD_CLASS_OF_FAMILY[family] ?: "shoulder"
                else -> null
            },
            curveClass = when {
                hold -> null
                wasTimed || proto.curveClass == null -> when {
                    proto.mechanics == "compound" && lower -> "lower_machine_compound"
                    proto.mechanics == "compound" -> "upper_compound"
                    lower -> "lower_isolation"
                    else -> "upper_isolation"
                }
                else -> proto.curveClass
            },
            restClass = when {
                hold -> "hold"
                wasTimed -> if (proto.mechanics == "compound") (if (lower) "compound_lower" else "compound_upper") else "isolation"
                else -> proto.restClass
            },
            tags = tags.sorted(),
            flagsOffered = d.flags.filter { it in CatalogDomains.SET_FLAGS }.distinct(),
            externalRefs = kotlinx.serialization.json.JsonObject(emptyMap()),
            evidenceNote = "Copied from ${proto.nameEn} (expert estimate)" + if (d.stimulus != null) "; coefficients edited." else ".",
            stimulus = (d.stimulus ?: proto.stimulus).filterValues { it > 0.0 }.toSortedMap(),
        ).also { require(catalogVersion.isNotBlank()) }
    }

    private val LOWER_FAMILIES = setOf("squat", "hinge", "legiso", "hip", "calf")
    private val DROPPED_TAGS = setOf("challenge", "home")
    private val UNILATERAL_TAGS = setOf("unilateral", "unilateral_pull", "heavy_unilateral")
    private val HOLD_CLASS_OF_FAMILY = mapOf("forearm" to "grip", "skill" to "grip", "core" to "trunk", "squat" to "knee", "legiso" to "knee", "calf" to "ankle")
}

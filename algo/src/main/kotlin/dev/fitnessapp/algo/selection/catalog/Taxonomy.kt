package dev.fitnessapp.algo.selection.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The two-level muscle taxonomy of C9/C44 (ALGORITHMS_PLANNING 10.2): 18 groups (+ inactive neck) carry goals
 * and volume; 34 regions (+ inactive neck) carry coefficients. Built from the bundled reference files
 * `fitness/reference/muscle_groups.json` and `muscle_regions.json` (the rows migration 005 seeded).
 */
data class Taxonomy(val groups: List<MuscleGroup>, val regions: List<MuscleRegion>) {
    /** Active groups in canonical order (sort, then id). */
    val activeGroups: List<MuscleGroup> = groups.filter { it.active }.sortedWith(compareBy({ it.sort }, { it.id }))

    /** Active regions in canonical order (sort, then id). */
    val activeRegions: List<MuscleRegion> = regions.filter { it.active }.sortedWith(compareBy({ it.sort }, { it.id }))

    private val regionById: Map<String, MuscleRegion> = regions.associateBy { it.id }
    private val aliasToRegion: Map<String, String> =
        regions.flatMap { r -> r.aliases.map { it to r.id } }.toMap()

    fun region(id: String): MuscleRegion? = regionById[id]

    /** Canonical region id for a key from a draft (aliases rewritten, ALGORITHMS_PLANNING 10.2), or null if unknown. */
    fun canonicalRegion(key: String): String? = when {
        regionById[key]?.active == true -> key
        else -> aliasToRegion[key]
    }

    fun regionsOf(groupId: String): List<MuscleRegion> = activeRegions.filter { it.groupId == groupId }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(groupsJson: String, regionsJson: String): Taxonomy = Taxonomy(
            json.decodeFromString(GroupFile.serializer(), groupsJson).rows,
            json.decodeFromString(RegionFile.serializer(), regionsJson).rows,
        )
    }

    @Serializable private data class GroupFile(val rows: List<MuscleGroup>)
    @Serializable private data class RegionFile(val rows: List<MuscleRegion>)
}

@Serializable
data class MuscleGroup(
    val id: String,
    val family: String,
    @SerialName("label_en") val labelEn: String,
    @SerialName("label_nl") val labelNl: String? = null,
    val aliases: List<String> = emptyList(),
    @SerialName("is_optional") val isOptional: Boolean = false,
    val active: Boolean = true,
    val sort: Int = 0,
)

@Serializable
data class MuscleRegion(
    val id: String,
    @SerialName("group_id") val groupId: String? = null,
    @SerialName("is_prehab_only") val isPrehabOnly: Boolean = false,
    val aliases: List<String> = emptyList(),
    @SerialName("label_en") val labelEn: String,
    @SerialName("label_nl") val labelNl: String? = null,
    val active: Boolean = true,
    val sort: Int = 0,
)

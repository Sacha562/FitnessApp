package dev.fitnessapp.app.ui.exercises

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fitnessapp.algo.selection.catalog.CatalogExercise
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.ui.common.ChipButton
import dev.fitnessapp.app.ui.common.FitTextField
import dev.fitnessapp.app.ui.common.Pill
import dev.fitnessapp.app.ui.common.dutchNames
import dev.fitnessapp.app.ui.common.isDutch
import dev.fitnessapp.app.ui.theme.Fit
import dev.fitnessapp.data.telemetry.EventDraft
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** One row of the catalog list (UX 13.2). */
data class CatalogRow(
    val ex: CatalogExercise,
    val id: String,
    val groups: List<String>,
    val equipment: String,
    val preference: String,
    val available: Boolean,
)

data class CatalogUi(
    val query: String = "",
    val filter: CatalogFilter = CatalogFilter(),
    val sort: CatalogSort = CatalogSort.NAME,
    val rows: List<CatalogRow> = emptyList(),
    val total: Int = 0,
)

/** The Exercises tab: catalog search, filters and sort over the bundled catalog, with the user's preferences. */
class ExercisesViewModel(private val c: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(CatalogFilter())
    private val sort = MutableStateFlow(CatalogSort.NAME)
    private val dutch = MutableStateFlow(isDutch(Locale.getDefault()))

    private val since = LocalDate.now(ZoneId.of("Europe/Amsterdam")).minusWeeks(8).toString()

    val ui: StateFlow<CatalogUi> = combine(
        combine(query, filter, sort, dutch) { q, f, s, d -> Inputs(q, f, s, d) },
        c.preferences.observe(),
        c.sessionRepository.observeUsageSince(since),
        c.equipment.observeEquipment(),
    ) { (q, f, s, d), prefs, usage, equipment ->
        val stations = equipment.filter { it.available }.map { it.equipment_type }.toSet()
        val available: (CatalogExercise) -> Boolean = { e -> equipment.isEmpty() || e.requiresStation.all { it in stations } }
        val usageMap = usage.associate { it.exercise_id to it.sessions }
        val list = CatalogQuery.apply(c.catalog.exercises, q, f, s, c.catalogModel, prefs, usageMap, available, c.catalog::idOf, d)
        CatalogUi(
            q, f, s,
            list.map { e ->
                val id = c.catalog.idOf(e)
                CatalogRow(e, id, CatalogQuery.primaryGroups(e, c.catalogModel), CatalogQuery.equipmentAbbrev(e), prefs[id] ?: "neutral", available(e))
            },
            c.catalog.exercises.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogUi())

    private var searchLog: kotlinx.coroutines.Job? = null

    /** Follows the phone language while the screen is shown (D12). */
    fun setDutch(v: Boolean) {
        dutch.value = v
    }

    fun setQuery(q: String) {
        query.value = q
        // `catalog_searched` 150 ms after the last keystroke; only the length is logged, never the text (UX 13.2).
        searchLog?.cancel()
        if (q.isBlank()) return
        searchLog = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val n = ui.value.rows.size
            c.sessionRepository.event(
                EventDraft(type = "catalog_searched", screen = "exercises", entityType = "none", payload = mapOf("query_length" to JsonPrimitive(q.length), "results" to JsonPrimitive(n))),
            )
        }
    }

    fun setFilter(name: String, value: String?, change: (CatalogFilter) -> CatalogFilter) {
        filter.update(change)
        logFilter(name, value ?: "none")
    }

    fun setSort(s: CatalogSort) {
        sort.value = s
        logFilter("sort", s.code)
    }

    private fun logFilter(name: String, value: String) = viewModelScope.launch {
        c.sessionRepository.event(
            EventDraft(type = "catalog_filter_changed", screen = "exercises", entityType = "none", payload = mapOf("filter" to JsonPrimitive(name), "value" to JsonPrimitive(value))),
        )
    }

    private data class Inputs(val query: String, val filter: CatalogFilter, val sort: CatalogSort, val dutch: Boolean)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 150L
    }
}

@Composable
fun ExercisesRoute(c: AppContainer, modifier: Modifier, onOpen: (String) -> Unit) {
    val vm: ExercisesViewModel = viewModel(factory = viewModelFactory { initializer { ExercisesViewModel(c) } })
    val ui by vm.ui.collectAsState()
    ExercisesContent(c, ui, vm, modifier, onOpen)
}

@Composable
private fun ExercisesContent(c: AppContainer, ui: CatalogUi, vm: ExercisesViewModel, modifier: Modifier, onOpen: (String) -> Unit) {
    var text by remember { mutableStateOf(ui.query) }
    val dutch = dutchNames()
    LaunchedEffect(text) { vm.setQuery(text) }
    LaunchedEffect(dutch) { vm.setDutch(dutch) }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Exercises", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).semantics { heading() })
                Text("${ui.rows.size} of ${ui.total}", style = MaterialTheme.typography.labelMedium, color = Fit.Muted, modifier = Modifier.padding(bottom = 6.dp))
            }
            FitTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = if (dutch) "Search exercises (EN / NL)" else "Search exercises",
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Fit.Muted) },
                trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Rounded.Close, "Clear search", tint = Fit.Muted) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FilterBar(c, ui, vm)
        if (ui.rows.isEmpty()) {
            Text(
                if (text.isNotBlank()) "No exercise matches '$text'." else "No exercise matches these filters.",
                style = MaterialTheme.typography.bodyLarge, color = Fit.Muted, modifier = Modifier.padding(20.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(ui.rows, key = { it.id }) { row -> CatalogRowItem(row) { onOpen(row.id) } }
        }
    }
}

@Composable
private fun FilterBar(c: AppContainer, ui: CatalogUi, vm: ExercisesViewModel) {
    val f = ui.filter
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val groups = c.catalogModel.taxonomy.activeGroups
        MenuChip("Muscle", groups.firstOrNull { it.id == f.muscle }?.labelEn, listOf<Pair<String?, String>>(null to "Any muscle") + groups.map { it.id to it.labelEn }) { v ->
            vm.setFilter("muscle", v) { it.copy(muscle = v) }
        }
        MenuChip("Equipment", f.equipment?.label, listOf<Pair<String?, String>>(null to "Any equipment") + EquipmentFilter.entries.map { it.code to it.label }) { v ->
            vm.setFilter("equipment", v) { it.copy(equipment = EquipmentFilter.entries.firstOrNull { e -> e.code == v }) }
        }
        MenuChip("Preference", f.preference?.let(::prefLabel), listOf<Pair<String?, String>>(null to "Any preference") + CatalogQuery.PREF_ORDER.map { it to prefLabel(it) }) { v ->
            vm.setFilter("preference", v) { it.copy(preference = v) }
        }
        ChipButton(
            "Available here", selected = f.availableOnly, role = Role.Checkbox,
            onClick = { vm.setFilter("available_here", (!f.availableOnly).toString()) { it.copy(availableOnly = !it.availableOnly) } },
        )
        MenuChip("Sort", ui.sort.label, CatalogSort.entries.map { it.code to it.label }, alwaysSelected = true) { v ->
            vm.setSort(CatalogSort.entries.first { it.code == v })
        }
    }
}

@Composable
private fun MenuChip(label: String, value: String?, options: List<Pair<String?, String>>, alwaysSelected: Boolean = false, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val on = value != null && !alwaysSelected
    Box {
        Row(
            Modifier.heightIn(min = 48.dp).clip(CircleShape).background(if (on) Fit.Accent else Fit.Raised)
                .clickable(role = Role.DropdownList) { open = true }.padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = if (on) Fit.OnAccent else Fit.Ink
            Text(if (alwaysSelected) "$label: $value" else value ?: label, style = MaterialTheme.typography.labelLarge, color = ink)
            Icon(Icons.Rounded.ArrowDropDown, null, tint = ink)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (code, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onPick(code) })
            }
        }
    }
}

@Composable
private fun CatalogRowItem(row: CatalogRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(18.dp)).background(Fit.Tile).clickable(onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PreferenceIcon(row.preference)
                Text(row.ex.nameEn, style = MaterialTheme.typography.titleMedium, color = if (row.available) Fit.Ink else Fit.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val sub = buildList {
                if (row.groups.isNotEmpty()) add(row.groups.joinToString(" · "))
                if (!row.available) add("not available here")
                if (row.preference != "neutral") add(prefLabel(row.preference))
            }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Fit.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (row.equipment.isNotBlank()) Pill(row.equipment)
    }
}

@Composable
fun PreferenceIcon(pref: String) {
    val (icon, desc) = when (pref) {
        "favorite" -> Icons.Rounded.Star to "Favorite"
        "like" -> Icons.Rounded.ThumbUp to "Liked"
        "dislike" -> Icons.Rounded.ThumbDown to "Disliked"
        "never" -> Icons.Rounded.Block to "Never"
        else -> return
    }
    Icon(icon, null, tint = if (pref == "never" || pref == "dislike") Fit.Muted else Fit.Accent, modifier = Modifier.size(18.dp).semantics { contentDescription = desc })
}

fun prefLabel(p: String): String = when (p) {
    "favorite" -> "Favorite"
    "like" -> "Like"
    "dislike" -> "Dislike"
    "never" -> "Never"
    else -> "Neutral"
}

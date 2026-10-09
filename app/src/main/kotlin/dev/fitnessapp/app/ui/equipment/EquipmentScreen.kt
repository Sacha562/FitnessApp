package dev.fitnessapp.app.ui.equipment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fitnessapp.app.AppContainer
import dev.fitnessapp.app.format.Numbers
import dev.fitnessapp.app.ui.common.ChoiceChips
import dev.fitnessapp.app.ui.common.SectionLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EquipmentViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<Checklist?>(null)
    val state: StateFlow<Checklist?> = _state
    val types get() = c.equipment.types

    init {
        viewModelScope.launch {
            val gym = c.equipment.defaultGym()
            _state.value = Checklist.fromRows(types, gym?.name, c.equipment.equipment())
        }
    }

    fun edit(change: (Checklist) -> Checklist) = _state.update { it?.let(change) }

    /** "Confirm equipment" (confirmed) or "Later" (defaults kept, unconfirmed), UX 8.7. */
    fun save(confirmed: Boolean, done: () -> Unit) {
        val c0 = _state.value ?: return
        viewModelScope.launch {
            c.equipment.saveChecklist(c0.gym(), c0.drafts(types), confirmed)
            c.equipment.defaultGym()?.let { c.profiles.setDefaultGym(it.id) }
            done()
        }
    }
}

/** UX 8.7 checklist content; the caller adds the Confirm / Later buttons. */
@Composable
fun EquipmentContent(state: Checklist, vm: EquipmentViewModel) {
    val labels = vm.types.associate { it.key to it.labelEn }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BufferedField("Your gym", state.gymName, { it }, { it }) { v -> vm.edit { it.copy(gymName = v) } }
        Text("Mon-Fri 07:00-22:30 · Sat-Sun 09:00-16:00", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionLabel("Stations (untick what's missing)")
        state.stations.forEach { (key, on) ->
            CheckRow(labels[key] ?: key, on) { v -> vm.edit { it.copy(stations = (it.stations + (key to v)).toSortedMap()) } }
        }

        SectionLabel("Plates")
        NumberListField("Plate sizes (kg)", state.plates.sortedDescending()) { v -> vm.edit { it.copy(plates = v.sortedDescending()) } }
        Text("Add 1.25 if your gym has it. Plates are assumed unlimited.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumberField("Barbell (kg)", state.barKg) { v -> vm.edit { it.copy(barKg = v) } }

        SectionLabel("Dumbbells (per hand)")
        NumberListField("Values (kg)", state.dumbbells) { v -> vm.edit { it.copy(dumbbells = v) } }

        SectionLabel("Machine stacks")
        ChoiceChips(listOf("lb", "kg"), state.stackUnit, { it }) { u ->
            vm.edit { if (it.stackUnit == u) it else it.copy(stackUnit = u, stackStep = Checklist.defaultStackStep(u)) }
        }
        key(state.stackUnit) {
            NumberField("Step (${state.stackUnit})", state.stackStep) { v -> if (v > 0) vm.edit { it.copy(stackStep = v) } }
        }
        val u = state.stackUnit
        Text(
            (if (u == "lb") "${Numbers.num(state.stackStep)} lb ≈ ${Numbers.oneDecimal(state.stackStep * Checklist.LB_KG)} kg; the set card shows both. " else "") +
                "Leg press, hack squat, calf and hip-thrust machines go to ${Numbers.num(state.stackMaxNative(state.heavyStackMaxKg))} $u, " +
                "others to ${Numbers.num(state.stackMaxNative(state.stackMaxKg))} $u.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Smith machine: plates only; the bar weight is asked later.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionLabel("Assisted pull-up machine")
        NumberField("Highest pin (lb), empty = ask later", state.assistedMaxPinLb, allowEmpty = true) { v -> vm.edit { it.copy(assistedMaxPinLb = v.takeIf { x -> x > 0 }) } }
        NumberField("Help at pin 0 (kg)", state.assistedOffsetKg) { v -> vm.edit { it.copy(assistedOffsetKg = v) } }
        Text(
            "assist = pin × 0.4536 × ${Numbers.num(state.assistedRatio)} + ${Numbers.num(state.assistedOffsetKg)} kg; at pin 0 the machine still helps about ${Numbers.num(state.assistedOffsetKg)} kg.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionLabel("Your own")
        CheckRow("Dip belt", state.ownDipBelt) { v -> vm.edit { it.copy(ownDipBelt = v) } }
        if (state.ownDipBelt) {
            NumberField("Belt maximum (kg)", state.beltMaxKg) { v -> vm.edit { it.copy(beltMaxKg = v) } }
            NumberField("Items on the belt at most", state.beltMaxItems.toDouble()) { v -> vm.edit { it.copy(beltMaxItems = v.toInt().coerceIn(1, 8)) } }
        }
        CheckRow("Chalk", state.chalk) { v -> vm.edit { it.copy(chalk = v) } }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A text field that owns its text while focused: each edit that parses is pushed out, but the field is only
 * rewritten from [value] (normalised: sorted, coerced, trailing dot dropped) when it is not focused, so typing
 * "2." or "20, 1" is never reformatted under the cursor.
 */
@Composable
private fun <T> BufferedField(
    label: String,
    value: T,
    format: (T) -> String,
    parse: (String) -> T?,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValue: (T) -> Unit,
) {
    var text by remember { mutableStateOf(format(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = format(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            parse(t)?.let(onValue)
        },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).onFocusChanged { focused = it.isFocused },
    )
}

@Composable
private fun NumberField(label: String, value: Double?, allowEmpty: Boolean = false, onValue: (Double) -> Unit) {
    BufferedField(
        label, value, { v -> v?.let(Numbers::num) ?: "" },
        { t -> if (allowEmpty && t.isBlank()) 0.0 else t.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 } },
        KeyboardType.Decimal,
    ) { v -> v?.let(onValue) }
}

@Composable
private fun NumberListField(label: String, values: List<Double>, onValues: (List<Double>) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(values.joinToString(", ") { Numbers.num(it) }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = true }) { Text("Edit") }
        }
        return
    }
    BufferedField(
        "$label, separated by commas", values, { v -> v.joinToString(", ") { Numbers.num(it) } },
        { t -> t.split(',', ' ', ';').mapNotNull { it.trim().toDoubleOrNull() }.filter { it > 0 }.distinct().sorted().takeIf { it.isNotEmpty() } },
    ) { v -> onValues(v) }
}

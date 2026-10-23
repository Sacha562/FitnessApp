package dev.fitnessapp.app.ui.challenges

import dev.fitnessapp.algo.challenge.cds.CompiledDefinition
import dev.fitnessapp.algo.challenge.enrollment.PastTest
import dev.fitnessapp.algo.challenge.fitexpr.BoolV
import dev.fitnessapp.algo.challenge.fitexpr.Dec
import dev.fitnessapp.algo.challenge.fitexpr.QV
import dev.fitnessapp.algo.challenge.fitexpr.SideV
import dev.fitnessapp.algo.challenge.fitexpr.Type
import dev.fitnessapp.algo.challenge.fitexpr.Value
import java.time.LocalDate
import java.util.TreeMap

/** The setup draft's parts of the cutover import that need the definition's types (D40). */
object SetupImport {
    /** A draft test as the import's [PastTest]; values that do not parse as their metric's type are left out. */
    fun pastTest(def: CompiledDefinition, t: DraftTest): PastTest? {
        val date = runCatching { LocalDate.parse(t.date) }.getOrNull() ?: return null
        fun value(type: Type, text: String?): Value? = when {
            text == null -> null
            type is Type.Q -> text.toBigDecimalOrNull()?.let { QV(type.dim, Dec.norm(it)) }
            type == Type.BoolT -> text.toBooleanStrictOrNull()?.let(BoolV::of)
            else -> null
        }
        val metrics = TreeMap<String, Value>()
        for (m in def.doc.metrics) {
            val info = def.metrics[m.id] ?: continue
            if (info.sided) {
                // A sided metric's type is the pair (SideT); each side is its element type.
                val side = (info.type as? Type.SideT)?.elem ?: info.type
                val l = value(side, t.metrics["${m.id}.L"])
                val r = value(side, t.metrics["${m.id}.R"])
                if (l != null || r != null) metrics[m.id] = SideV(l, r)
            } else {
                value(info.type, t.metrics[m.id])?.let { metrics[m.id] = it }
            }
        }
        return PastTest(date, metrics, t.validForGate, t.notComparable.toSet(), TreeMap(t.protocolCodes))
    }
}

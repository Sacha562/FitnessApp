package dev.fitnessapp.app.format

/**
 * Plain-language lines for RX-1's trace codes (UX 16.1 summary, 12.3 "Next time"). Codes and mode names never reach
 * the screen (P15); they stay in the Why sheet's trace.
 */
object RxTexts {
    /** [top] is the rep count at which double progression steps the load up (range top plus extension), when known. */
    fun reason(steps: List<String>, mode: String, qStar: Double, probeSet: Boolean, top: Int? = null): String {
        val q = Numbers.num(qStar, 1)
        val climb = "each time you hit every target, one set gets one more rep; when every set reaches ${top ?: "the top of the range"}, the weight goes up."
        return when {
            probeSet -> "A first set after a new start or a long break measures where you are today; the other sets follow from it."
            "rx.probe.followup" in steps -> "Planned from your first set, so each set ends about $q reps short of failure."
            "stall.fast_path" in steps -> "Two sessions in a row ended in a failed rep: one load step down, then climb again."
            "rx.deload" in steps -> "Deload: the same load with fewer reps, so you recover."
            "rx.double.advance" in steps -> "Every set reached the top of the range: the next load step, starting lower in the range."
            "rx.double.step" in steps -> "Targets met: one more rep on the next set in line."
            "rx.double.extend" in steps -> "Top of the range, and the next load step is too big a jump: the range extends a little."
            "rx.double.repeat" in steps -> "Not every target was met: the same targets again."
            "rx.probe" in steps || "rx.probe.pick_weight" in steps -> "A first set to measure where you are; the other sets follow from it."
            "rx.double.start" in steps -> "A new starting point at this weight: $climb"
            mode == "double" -> "Same weight: $climb"
            mode == "model" -> "Chosen from your estimated strength so each set ends about $q reps short of failure."
            else -> "No estimate yet, so last time's values."
        }
    }

    fun evidence(mode: String): String = if (mode == "double") "Expert opinion (double progression)" else "Engineering (the app's strength model)"
}

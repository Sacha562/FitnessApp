package dev.fitnessapp.algo.core.load

/**
 * B2.8 plate breakdown: among the multisets of the inventory that sum exactly to the target, the one with
 * the fewest items; ties go to the lexicographically larger sequence sorted descending (big plates first).
 *
 * The search visits sizes in descending order and, per size, counts from high to low, so solutions with the
 * same item count are met in descending lexicographic order: the first solution with the minimum count wins.
 */
object PlateBreakdown {
    /** [inventory]: (size in hundredths of a kg, available count); returns sizes descending, or null. */
    fun solveCents(targetCents: Long, inventory: List<Pair<Long, Int>>, maxItems: Int = Int.MAX_VALUE): List<Long>? {
        require(targetCents >= 0) { "negative target" }
        val sizes = inventory.filter { it.second > 0 && it.first > 0 }.sortedByDescending { it.first }
        var best: List<Long>? = null
        var bestItems = if (maxItems == Int.MAX_VALUE) Int.MAX_VALUE else maxItems + 1
        val chosen = IntArray(sizes.size)

        fun dfs(i: Int, remaining: Long, items: Int) {
            if (items >= bestItems) return
            if (remaining == 0L) {
                best = sizes.indices.flatMap { j -> List(chosen[j]) { sizes[j].first } }
                bestItems = items
                return
            }
            if (i == sizes.size) return
            val (size, available) = sizes[i]
            val maxCount = minOf(available.toLong(), remaining / size, (bestItems - 1 - items).toLong()).toInt()
            for (c in maxCount downTo 0) {
                chosen[i] = c
                dfs(i + 1, remaining - c * size, items + c)
            }
            chosen[i] = 0
        }

        dfs(0, targetCents, 0)
        return best
    }

    /** Per side for a barbell (pairs), or the total for a belt; values in kg, descending. */
    fun solve(targetKg: Double, inventory: List<PlateCount>, maxItems: Int = Int.MAX_VALUE): List<Double>? =
        solveCents(LoadModel.cents(targetKg), inventory.map { LoadModel.cents(it.kg) to it.count }, maxItems)
            ?.map(LoadModel::fromCents)
}

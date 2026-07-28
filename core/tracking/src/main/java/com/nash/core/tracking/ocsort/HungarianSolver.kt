package com.nash.core.tracking.ocsort

/**
 * Hungarian algorithm (Jonker/e-maxx potentials formulation) for minimum-cost
 * assignment on a rectangular cost matrix. Replaces greedy IoU matching so two
 * crossing targets resolve to the globally best pairing instead of the locally
 * best one.
 */
internal object HungarianSolver {

    /** Cost used to gate impossible pairs (class mismatch, IoU below threshold). */
    const val FORBIDDEN = 1e9

    /**
     * @param cost rows = detections, cols = tracks. Must be non-empty rows x cols.
     * @return assignment[row] = matched col, or -1. Callers must still reject
     * pairs whose cost is >= [FORBIDDEN] (the padded square matrix can force
     * forbidden assignments when nothing better exists).
     */
    fun solve(cost: Array<DoubleArray>): IntArray {
        if (cost.isEmpty()) return IntArray(0)
        val rows = cost.size
        val cols = cost[0].size
        val n = maxOf(rows, cols)

        // Pad to square; dummy cells cost 0 (a dummy match means "unassigned").
        val a = Array(n) { r ->
            DoubleArray(n) { c -> if (r < rows && c < cols) cost[r][c] else 0.0 }
        }

        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)

        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minv = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
            val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.POSITIVE_INFINITY
                var j1 = 0
                for (j in 1..n) {
                    if (used[j]) continue
                    val cur = a[i0 - 1][j - 1] - u[i0] - v[j]
                    if (cur < minv[j]) { minv[j] = cur; way[j] = j0 }
                    if (minv[j] < delta) { delta = minv[j]; j1 = j }
                }
                for (j in 0..n) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minv[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }

        val result = IntArray(rows) { -1 }
        for (j in 1..n) {
            val i = p[j]
            if (i in 1..rows && j <= cols) result[i - 1] = j - 1
        }
        return result
    }
}
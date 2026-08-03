package com.nash.engine.tracking.ocsort

import kotlin.math.sqrt

/**
 * SORT-style constant-velocity Kalman filter over a single bounding box.
 *
 * State x (7):       [cx, cy, s, r, vcx, vcy, vs]
 *   cx, cy — box center (normalized [0, 1] coords)
 *   s      — box area (w * h)
 *   r      — aspect ratio (w / h), modeled as constant
 * Measurement z (4): [cx, cy, s, r]
 *
 * Noise magnitudes are the canonical SORT/OC-SORT values. The Kalman gain
 * depends only on the *ratios* between P, Q and R, so the filter behaves the
 * same in normalized coordinates as it does in pixels.
 */
internal class KalmanBoxFilter(z0: DoubleArray) {

    private var x = DoubleArray(DIM_X)
    private var p: Array<DoubleArray>

    init {
        require(z0.size == DIM_Z)
        for (i in 0 until DIM_Z) x[i] = z0[i]
        p = diag(doubleArrayOf(10.0, 10.0, 10.0, 10.0, 1e4, 1e4, 1e4))
    }

    /** Advance the state by one frame: x = Fx, P = FPFᵀ + Q. */
    fun predict() {
        // Area must never go non-positive; kill area velocity if it would.
        if (x[2] + x[6] <= 0.0) x[6] = 0.0
        x = doubleArrayOf(x[0] + x[4], x[1] + x[5], x[2] + x[6], x[3], x[4], x[5], x[6])
        p = add(matMul(matMul(F, p), FT), Q)
    }

    /** Standard Kalman update with measurement z = [cx, cy, s, r]. */
    fun update(z: DoubleArray) {
        require(z.size == DIM_Z)
        val ht = transpose(H)
        val s = add(matMul(matMul(H, p), ht), R)              // 4x4 innovation cov
        val k = matMul(matMul(p, ht), invert4x4(s))           // 7x4 gain
        val y = DoubleArray(DIM_Z) { z[it] - x[it] }          // innovation (H picks first 4)
        for (i in 0 until DIM_X) {
            var d = 0.0
            for (j in 0 until DIM_Z) d += k[i][j] * y[j]
            x[i] += d
        }
        val iKh = identity(DIM_X)
        val kh = matMul(k, H)
        for (i in 0 until DIM_X) for (j in 0 until DIM_X) iKh[i][j] -= kh[i][j]
        p = matMul(iKh, p)
    }

    /** Current state as (cx, cy, w, h), or null if the state is degenerate. */
    fun boxState(): DoubleArray? {
        val s = x[2]
        val r = x[3]
        if (s <= 0.0 || r <= 0.0 || s.isNaN() || r.isNaN()) return null
        val w = sqrt(s * r)
        val h = s / w
        if (w.isNaN() || h.isNaN() || w <= 0.0 || h <= 0.0) return null
        return doubleArrayOf(x[0], x[1], w, h)
    }

    // --- Snapshots for ORU (observation-centric re-update). ---

    fun stateSnapshot(): DoubleArray = x.copyOf()

    fun covarianceSnapshot(): Array<DoubleArray> = Array(DIM_X) { p[it].copyOf() }

    fun restore(state: DoubleArray, covariance: Array<DoubleArray>) {
        x = state.copyOf()
        p = Array(DIM_X) { covariance[it].copyOf() }
    }

    private companion object {
        const val DIM_X = 7
        const val DIM_Z = 4

        /** Constant-velocity transition: cx += vcx, cy += vcy, s += vs. */
        val F = identity(DIM_X).also { it[0][4] = 1.0; it[1][5] = 1.0; it[2][6] = 1.0 }
        val FT = transpose(F)

        /** Observe the first four state components. */
        val H = Array(DIM_Z) { row -> DoubleArray(DIM_X).also { it[row] = 1.0 } }

        val Q = diag(doubleArrayOf(1.0, 1.0, 1.0, 1.0, 0.01, 0.01, 1e-4))
        val R = diag(doubleArrayOf(1.0, 1.0, 10.0, 10.0))

        fun identity(n: Int) = Array(n) { i -> DoubleArray(n).also { it[i] = 1.0 } }

        fun diag(values: DoubleArray) =
            Array(values.size) { i -> DoubleArray(values.size).also { it[i] = values[i] } }

        fun transpose(a: Array<DoubleArray>) =
            Array(a[0].size) { i -> DoubleArray(a.size) { j -> a[j][i] } }

        fun matMul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
            val out = Array(a.size) { DoubleArray(b[0].size) }
            for (i in a.indices) for (kIdx in b.indices) {
                val aik = a[i][kIdx]
                if (aik == 0.0) continue
                for (j in b[0].indices) out[i][j] += aik * b[kIdx][j]
            }
            return out
        }

        fun add(a: Array<DoubleArray>, b: Array<DoubleArray>) =
            Array(a.size) { i -> DoubleArray(a[0].size) { j -> a[i][j] + b[i][j] } }

        /** Gauss-Jordan inverse with partial pivoting; input is 4x4 and well-conditioned. */
        fun invert4x4(m: Array<DoubleArray>): Array<DoubleArray> {
            val n = 4
            val a = Array(n) { m[it].copyOf() }
            val inv = identity(n)
            for (col in 0 until n) {
                var pivot = col
                for (row in col + 1 until n) {
                    if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
                }
                a[col] = a[pivot].also { a[pivot] = a[col] }
                inv[col] = inv[pivot].also { inv[pivot] = inv[col] }
                val d = a[col][col]
                for (j in 0 until n) { a[col][j] /= d; inv[col][j] /= d }
                for (row in 0 until n) {
                    if (row == col) continue
                    val factor = a[row][col]
                    if (factor == 0.0) continue
                    for (j in 0 until n) {
                        a[row][j] -= factor * a[col][j]
                        inv[row][j] -= factor * inv[col][j]
                    }
                }
            }
            return inv
        }
    }
}

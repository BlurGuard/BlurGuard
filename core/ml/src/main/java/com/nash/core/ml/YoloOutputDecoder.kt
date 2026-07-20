package com.nash.core.ml

import com.nash.core.model.BoundingBox
import com.nash.core.model.DetectionBox
import com.nash.core.model.DetectionClass

/**
 * Decodes raw YOLO (v8/v11-style) output into [DetectionBox]es normalized to
 * the source buffer.
 *
 * Expected tensor layout: `[4 + numClasses][numCandidates]` where rows 0..3
 * are (cx, cy, w, h) and rows 4.. are per-class scores (sigmoid already
 * applied by the export). Handles both pixel-space and 0..1-normalized
 * exports by sniffing coordinate magnitude.
 *
 * @param classes class-index -> DetectionClass mapping (order must match the
 * training data.yaml).
 */
internal class YoloOutputDecoder(
    private val classes: List<DetectionClass>,
    private val inputSize: Int,
    private val confidenceThreshold: Float,
    private val iouThreshold: Float = 0.45f
) {

    /** How the source buffer was letterboxed into the model input. */
    data class Letterbox(
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val bufferWidth: Int,
        val bufferHeight: Int
    )

    private class Candidate(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val classIndex: Int,
        val confidence: Float
    )

    fun decode(output: Array<FloatArray>, letterbox: Letterbox): List<DetectionBox> {
        val numCandidates = output[0].size

        // Ultralytics TFLite exports may emit coords normalized to 0..1 or in
        // input pixels. Sniff once: if nothing exceeds ~1.5, it's normalized.
        var maxCoord = 0f
        for (c in 0..3) {
            val row = output[c]
            for (i in 0 until numCandidates) {
                if (row[i] > maxCoord) maxCoord = row[i]
            }
        }
        val coordScale = if (maxCoord <= 1.5f) inputSize.toFloat() else 1f

        val candidates = ArrayList<Candidate>()
        for (i in 0 until numCandidates) {
            // Best class for this candidate.
            var bestClass = 0
            var bestScore = 0f
            for (c in classes.indices) {
                val score = output[4 + c][i]
                if (score > bestScore) {
                    bestScore = score
                    bestClass = c
                }
            }
            if (bestScore < confidenceThreshold) continue

            val cx = output[0][i] * coordScale
            val cy = output[1][i] * coordScale
            val w = output[2][i] * coordScale
            val h = output[3][i] * coordScale

            // Input-pixel corners -> un-letterbox -> buffer pixels.
            val leftPx = (cx - w / 2f - letterbox.padX) / letterbox.scale
            val topPx = (cy - h / 2f - letterbox.padY) / letterbox.scale
            val rightPx = (cx + w / 2f - letterbox.padX) / letterbox.scale
            val bottomPx = (cy + h / 2f - letterbox.padY) / letterbox.scale

            // Normalize to the buffer and clamp.
            val left = (leftPx / letterbox.bufferWidth).coerceIn(0f, 1f)
            val top = (topPx / letterbox.bufferHeight).coerceIn(0f, 1f)
            val right = (rightPx / letterbox.bufferWidth).coerceIn(0f, 1f)
            val bottom = (bottomPx / letterbox.bufferHeight).coerceIn(0f, 1f)
            if (right <= left || bottom <= top) continue

            candidates += Candidate(left, top, right, bottom, bestClass, bestScore)
        }

        return nonMaxSuppression(candidates).map { c ->
            DetectionBox(
                box = BoundingBox(c.left, c.top, c.right, c.bottom),
                clazz = classes[c.classIndex],
                confidence = c.confidence
            )
        }
    }

    /** Greedy class-wise NMS. */
    private fun nonMaxSuppression(candidates: List<Candidate>): List<Candidate> {
        val kept = ArrayList<Candidate>()
        for (classIndex in classes.indices) {
            val pool = candidates
                .filter { it.classIndex == classIndex }
                .sortedByDescending { it.confidence }
                .toMutableList()
            while (pool.isNotEmpty()) {
                val best = pool.removeAt(0)
                kept += best
                pool.removeAll { iou(best, it) > iouThreshold }
            }
        }
        return kept
    }

    private fun iou(a: Candidate, b: Candidate): Float {
        val interW = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f)
        val interH = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        val inter = interW * interH
        if (inter <= 0f) return 0f
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}
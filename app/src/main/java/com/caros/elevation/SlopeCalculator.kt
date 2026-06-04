package com.caros.elevation

// ─────────────────────────────────────────────────────────────────────────────
//  SlopeCalculator.kt — Utility class for categorising, colourising, and
//  aggregating slope/gradient data derived from elevation profiles.
// ─────────────────────────────────────────────────────────────────────────────

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SlopeCalculator @Inject constructor() {

    // ── Slope categories ──────────────────────────────────────────────────────

    /** Human-readable road-grade classification. */
    enum class SlopeCategory {
        /** ≤ 2% — essentially flat road */
        FLAT,
        /** 2–5% — gentle incline/decline */
        MILD,
        /** 5–10% — noticeable gradient, towing/diesel performance affected */
        STEEP,
        /** > 10% — very steep; Alpine passes, underground car parks */
        EXTREME
    }

    /**
     * Classify a slope percentage into a [SlopeCategory].
     *
     * @param slopePct Grade in percent (positive = uphill, negative = downhill).
     *                 The sign is ignored for classification.
     */
    fun categorize(slopePct: Float): SlopeCategory = when {
        abs(slopePct) <= 2f  -> SlopeCategory.FLAT
        abs(slopePct) <= 5f  -> SlopeCategory.MILD
        abs(slopePct) <= 10f -> SlopeCategory.STEEP
        else                 -> SlopeCategory.EXTREME
    }

    /**
     * Return an ARGB colour integer suitable for map polyline or bar chart
     * colouring based on slope severity.
     *
     * | Category | Colour          |
     * |----------|-----------------|
     * | FLAT     | Dark green      |
     * | MILD     | Amber           |
     * | STEEP    | Deep orange     |
     * | EXTREME  | Deep red        |
     *
     * @param slopePct Grade in percent
     * @return ARGB colour as [Int] (fully opaque)
     */
    fun toColor(slopePct: Float): Int = when (categorize(slopePct)) {
        SlopeCategory.FLAT    -> 0xFF2E7D32.toInt()   // Material Green 800
        SlopeCategory.MILD    -> 0xFFF9A825.toInt()   // Material Amber 800
        SlopeCategory.STEEP   -> 0xFFE65100.toInt()   // Material Deep Orange 900
        SlopeCategory.EXTREME -> 0xFFC62828.toInt()   // Material Red 900
    }

    // ── Segment statistics ────────────────────────────────────────────────────

    /**
     * Aggregate statistics across a segment of [ElevationPoint]s.
     *
     * @param points Ordered list of elevation samples; may be empty
     * @return [SegmentStats] — all fields are 0 for an empty list
     */
    fun calculateSegmentStats(points: List<ElevationPoint>): SegmentStats {
        if (points.isEmpty()) return SegmentStats()

        val slopes = points.map { it.slopePercent }

        val ascent = points.zipWithNext()
            .sumOf { (a, b) -> if (b.altM > a.altM) (b.altM - a.altM).toDouble() else 0.0 }
            .toFloat()

        val descent = points.zipWithNext()
            .sumOf { (a, b) -> if (b.altM < a.altM) (a.altM - b.altM).toDouble() else 0.0 }
            .toFloat()

        return SegmentStats(
            avgSlope = slopes.average().toFloat(),
            maxSlope = slopes.maxOrNull() ?: 0f,
            minSlope = slopes.minOrNull() ?: 0f,
            ascentM  = ascent,
            descentM = descent
        )
    }

    /**
     * Aggregated slope statistics for a segment.
     *
     * @param avgSlope Average grade across all points in the segment (%)
     * @param maxSlope Maximum grade recorded in the segment (%)
     * @param minSlope Minimum grade recorded in the segment (%)
     * @param ascentM  Total cumulative ascent in metres
     * @param descentM Total cumulative descent in metres (positive value)
     */
    data class SegmentStats(
        val avgSlope: Float = 0f,
        val maxSlope: Float = 0f,
        val minSlope: Float = 0f,
        val ascentM:  Float = 0f,
        val descentM: Float = 0f
    )
}

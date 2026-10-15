package com.programminghut.realtime_object

import android.graphics.RectF
import kotlin.math.abs

class RiskAssessmentEngine {

    enum class HazardLevel { NONE, LOW, MEDIUM, HIGH, CRITICAL }

    // Mobility-specific prioritization
    private val priorityMap = mapOf(
        "car" to 3.0f,          // Lethal moving hazard
        "bus" to 3.5f,
        "truck" to 3.5f,
        "motorcycle" to 2.5f,
        "bicycle" to 2.0f,
        "person" to 2.2f,       // Dynamic, unpredictable
        "stairs" to 5.0f,       // Fall risk (High priority)
        "stop sign" to 1.8f,    // Head-height hazard
        "fire hydrant" to 1.5f, // Tripping hazard
        "bench" to 1.4f,        // Knee-height hazard
        "chair" to 1.2f,
        "potted plant" to 1.1f,
        "dog" to 1.8f           // Small, fast moving
    )

    /**
     * Advanced Risk Assessment
     * Factors: Semantic Priority, Proximity, Path Alignment, and Vertical Position (Floor vs Head)
     */
    fun assessRisk(
        label: String,
        rect: RectF,
        distanceFeet: Float,
        velocityX: Float = 0f
    ): Float {
        val basePriority = priorityMap[label] ?: 1.0f

        // 1. Proximity Escalation (Inverse square-ish)
        val proximityFactor = when {
            distanceFeet < 3.0f -> 6.0f  // Extreme danger
            distanceFeet < 6.0f -> 3.0f
            distanceFeet < 12.0f -> 1.5f
            else -> 0.5f
        }

        // 2. Floor & Head Hazard Detection
        // Objects at the very bottom (rect.bottom > 0.9) are tripping hazards.
        // Objects at the very top (rect.top < 0.2) are head-height hazards.
        var verticalRiskFactor = 1.0f
        if (rect.bottom > 0.85f) verticalRiskFactor += 0.8f // Trip hazard
        if (rect.top < 0.25f && distanceFeet < 6.0f) verticalRiskFactor += 1.0f // Head-height hazard (low-hanging branch/sign)

        // 3. Path Interference
        val centerX = rect.centerX()
        val deviationFromCenter = abs(centerX - 0.5f)
        val pathFactor = when {
            deviationFromCenter < 0.12f -> 3.5f // Directly in path
            deviationFromCenter < 0.25f -> 1.8f // Near path
            else -> 0.7f                        // Peripheral
        }

        // 4. Motion Intent
        // If object is moving towards center, increase risk
        val movingTowardsPath = (centerX < 0.5f && velocityX > 0.05f) || (centerX > 0.5f && velocityX < -0.05f)
        val motionFactor = if (movingTowardsPath) 1.5f else 1.0f

        return (basePriority * proximityFactor * verticalRiskFactor * pathFactor * motionFactor * 5f).coerceIn(0f, 100f)
    }

    fun getHazardLevel(riskScore: Float): HazardLevel {
        return when {
            riskScore > 90 -> HazardLevel.CRITICAL
            riskScore > 70 -> HazardLevel.HIGH
            riskScore > 40 -> HazardLevel.MEDIUM
            riskScore > 15 -> HazardLevel.LOW
            else -> HazardLevel.NONE
        }
    }
}

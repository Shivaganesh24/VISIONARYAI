package com.programminghut.realtime_object

import android.graphics.PointF
import android.graphics.RectF

/**
 * Shared data models for the VisionGuide AI pipeline.
 */

data class VisionObstacle(
    val label: String,
    val rect: RectF,
    val distance: Float,
    val riskScore: Float,
    val normalizedCenterX: Float = (rect.left + rect.right) / 2f
)

enum class Action { MOVE_FORWARD, STEER_LEFT, STEER_RIGHT, STOP_IMMEDIATELY, PROCEED_CAREFULLY }

data class PathPlan(
    val steeringAngle: Float,
    val confidence: Float,
    val suggestedAction: Action,
    val occupancyMap: FloatArray,
    val isCollisionImminent: Boolean = false,
    val pathPoints: List<PointF> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathPlan) return false
        return steeringAngle == other.steeringAngle && 
               confidence == other.confidence && 
               suggestedAction == other.suggestedAction && 
               occupancyMap.contentEquals(other.occupancyMap) &&
               isCollisionImminent == other.isCollisionImminent &&
               pathPoints == other.pathPoints
    }

    override fun hashCode(): Int {
        var result = steeringAngle.hashCode()
        result = 31 * result + confidence.hashCode()
        result = 31 * result + suggestedAction.hashCode()
        result = 31 * result + occupancyMap.contentHashCode()
        result = 31 * result + isCollisionImminent.hashCode()
        result = 31 * result + pathPoints.hashCode()
        return result
    }
}

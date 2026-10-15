package com.programminghut.realtime_object

import android.graphics.RectF

/**
 * Predicts future collisions by projecting object trajectories 2-3 seconds ahead.
 */
class CollisionPredictor {

    data class Prediction(
        val isFutureCollision: Boolean,
        val timeToImpact: Float, // seconds
        val impactPoint: Float // -1 (left) to 1 (right)
    )

    private val PREDICTION_HORIZON = 2.5f // Look 2.5 seconds ahead

    fun predict(trackedObstacles: List<NavigationMemory.TrackedObstacle>): Prediction {
        var earliestImpact = Float.MAX_VALUE
        var collisionFound = false
        var collisionX = 0f

        for (obs in trackedObstacles) {
            // Only worry about objects with significant velocity or high risk
            if (obs.riskScore < 20 && Math.abs(obs.velocityX) < 0.05f) continue

            // Simple Linear Projection
            // Current center is obs.rect.centerX() (0 to 1)
            // Path corridor is roughly 0.4 to 0.6
            
            val centerX = obs.rect.centerX()
            val velX = obs.velocityX
            
            // Check if the object's X-trajectory intersects the central walking corridor (0.35 to 0.65)
            // Corridor Width: 0.3
            val corridorMin = 0.35f
            val corridorMax = 0.65f

            // Solving for 't' when centerX + velX * t is within [corridorMin, corridorMax]
            // We only care about objects that are moving TOWARDS the center or are already there
            
            // If already in center and moving slow/towards us
            if (centerX in corridorMin..corridorMax && obs.distance < 10f) {
                val t = obs.distance / 5f // Estimate time based on distance (assuming avg walk speed 5ft/s)
                if (t < earliestImpact) {
                    earliestImpact = t
                    collisionFound = true
                    collisionX = centerX
                }
            } else if (velX != 0f) {
                // Time to reach corridor edges
                val t1 = (corridorMin - centerX) / velX
                val t2 = (corridorMax - centerX) / velX
                
                val entryTime = Math.min(t1, t2)
                if (entryTime > 0 && entryTime < PREDICTION_HORIZON && entryTime < earliestImpact) {
                    earliestImpact = entryTime
                    collisionFound = true
                    collisionX = corridorMin + (corridorMax - corridorMin) / 2
                }
            }
        }

        return Prediction(collisionFound, if (collisionFound) earliestImpact else 0f, collisionX)
    }
}

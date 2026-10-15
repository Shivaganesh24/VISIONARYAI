package com.programminghut.realtime_object

import android.graphics.RectF
import java.util.Locale

/**
 * The "Inner Eye".
 * Compiles detected objects and spatial data into a coherent environmental summary.
 */
class SceneContextEngine {

    fun generateDescription(trackedObstacles: List<NavigationMemory.TrackedObstacle>, plan: PathPlan): String {
        if (trackedObstacles.isEmpty()) {
            return "The path in front of you appears completely open."
        }

        val centerObs = trackedObstacles.filter { it.rect.centerX() in 0.35f..0.65f }
        val leftObs = trackedObstacles.filter { it.rect.centerX() < 0.35f }
        val rightObs = trackedObstacles.filter { it.rect.centerX() > 0.65f }

        val builder = StringBuilder()

        // 1. Central Focus
        if (centerObs.isNotEmpty()) {
            val closest = centerObs.minByOrNull { it.distance }!!
            builder.append("Directly in front, there is a ${closest.label} about ${String.format(Locale.US, "%.0f", closest.distance)} feet away. ")
        } else {
            builder.append("The central corridor is currently clear. ")
        }

        // 2. Peripheral Awareness
        if (leftObs.isNotEmpty()) {
            val closest = leftObs.minByOrNull { it.distance }!!
            builder.append("On your left, I see a ${closest.label}. ")
        }
        if (rightObs.isNotEmpty()) {
            val closest = rightObs.minByOrNull { it.distance }!!
            builder.append("To your right, there is a ${closest.label}. ")
        }

        // 3. Navigation Suggestion
        when {
            plan.isCollisionImminent -> builder.append("You should stop immediately.")
            plan.suggestedAction == Action.STEER_LEFT -> builder.append("You might want to move slightly to your left.")
            plan.suggestedAction == Action.STEER_RIGHT -> builder.append("There is more space to your right.")
            else -> builder.append("Continue walking straight.")
        }

        return builder.toString()
    }
}

package com.programminghut.realtime_object

import android.graphics.RectF
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced Navigation Memory with Ego-Motion Compensation and Low-Pass Distance/Box Smoothing.
 * Tracks obstacles across frames and compensates for phone rotation (head/body movement).
 */
class NavigationMemory {

    data class TrackedObstacle(
        val id: String,
        val label: String,
        var rect: RectF,
        var distance: Float,
        var riskScore: Float,
        var lastSeen: Long,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f,
        var firstSeen: Long = System.currentTimeMillis(),
        var framesSeen: Int = 1,
        var normalizedCenterX: Float = (rect.left + rect.right) / 2f
    )

    private val trackedItems = mutableMapOf<String, TrackedObstacle>()
    private val PERSISTENCE_MS = 2500L 
    private val IOU_THRESHOLD = 0.30f
    private val MIN_CONFIRMATION_FRAMES = 2
    
    // Approximate camera field of view in radians (~63° horizontal, ~48° vertical)
    private val HORIZONTAL_FOV_RAD = 1.10f
    private val VERTICAL_FOV_RAD = 0.85f

    private var lastAzimuth = 0f
    private var lastPitch = 0f
    private var lastRoll = 0f
    private var isFirstFrame = true

    fun update(
        currentObstacles: List<VisionObstacle>,
        azimuth: Float,
        pitch: Float,
        roll: Float
    ): List<TrackedObstacle> {
        val now = System.currentTimeMillis()
        
        if (!isFirstFrame) {
            var dAzimuth = azimuth - lastAzimuth
            // Handle angle wrap-around at -PI / +PI
            if (dAzimuth > Math.PI) dAzimuth -= (2 * Math.PI).toFloat()
            if (dAzimuth < -Math.PI) dAzimuth += (2 * Math.PI).toFloat()
            
            val dPitch = pitch - lastPitch
            compensateEgoMotion(dAzimuth, dPitch)
        } else {
            isFirstFrame = false
        }
        
        lastAzimuth = azimuth
        lastPitch = pitch
        lastRoll = roll

        val matchedIds = mutableSetOf<String>()

        for (newObs in currentObstacles) {
            var bestMatch: TrackedObstacle? = null
            var maxIou = 0f

            for (stored in trackedItems.values) {
                if (stored.label == newObs.label) {
                    val iou = calculateIOU(stored.rect, newObs.rect)
                    if (iou > maxIou && iou > IOU_THRESHOLD) {
                        maxIou = iou
                        bestMatch = stored
                    }
                }
            }

            if (bestMatch != null) {
                val dt = (now - bestMatch.lastSeen) / 1000f
                if (dt > 0) {
                    bestMatch.velocityX = (newObs.normalizedCenterX - bestMatch.normalizedCenterX) / dt
                }

                // Exponential Moving Average (EMA) smoothing for stability and flow
                val alpha = 0.65f
                bestMatch.distance = bestMatch.distance * (1f - alpha) + newObs.distance * alpha
                bestMatch.normalizedCenterX = bestMatch.normalizedCenterX * (1f - alpha) + newObs.normalizedCenterX * alpha
                
                val smLeft = bestMatch.rect.left * (1f - alpha) + newObs.rect.left * alpha
                val smTop = bestMatch.rect.top * (1f - alpha) + newObs.rect.top * alpha
                val smRight = bestMatch.rect.right * (1f - alpha) + newObs.rect.right * alpha
                val smBottom = bestMatch.rect.bottom * (1f - alpha) + newObs.rect.bottom * alpha
                bestMatch.rect = RectF().apply { left = smLeft; top = smTop; right = smRight; bottom = smBottom }

                bestMatch.riskScore = newObs.riskScore
                bestMatch.lastSeen = now
                bestMatch.framesSeen++
                matchedIds.add(bestMatch.id)
            } else {
                val newId = UUID.randomUUID().toString()
                val newItem = TrackedObstacle(
                    id = newId, 
                    label = newObs.label, 
                    rect = newObs.rect, 
                    distance = newObs.distance, 
                    riskScore = newObs.riskScore, 
                    lastSeen = now,
                    normalizedCenterX = newObs.normalizedCenterX
                )
                trackedItems[newId] = newItem
                matchedIds.add(newId)
            }
        }

        val iterator = trackedItems.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastSeen > PERSISTENCE_MS) {
                iterator.remove()
            }
        }

        return trackedItems.values.filter { it.framesSeen >= MIN_CONFIRMATION_FRAMES }
    }

    private fun compensateEgoMotion(dAzimuth: Float, dPitch: Float) {
        val horizontalShift = dAzimuth / HORIZONTAL_FOV_RAD 
        val verticalShift = dPitch / VERTICAL_FOV_RAD

        for (item in trackedItems.values) {
            val smLeft = item.rect.left - horizontalShift
            val smRight = item.rect.right - horizontalShift
            val smTop = item.rect.top - verticalShift
            val smBottom = item.rect.bottom - verticalShift

            item.rect = RectF().apply { left = smLeft; top = smTop; right = smRight; bottom = smBottom }
            item.normalizedCenterX -= horizontalShift
        }
    }

    private fun calculateIOU(r1: RectF, r2: RectF): Float {
        val interLeft = max(r1.left, r2.left)
        val interTop = max(r1.top, r2.top)
        val interRight = min(r1.right, r2.right)
        val interBottom = min(r1.bottom, r2.bottom)

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        val area1 = max(0f, r1.right - r1.left) * max(0f, r1.bottom - r1.top)
        val area2 = max(0f, r2.right - r2.left) * max(0f, r2.bottom - r2.top)
        val unionArea = area1 + area2 - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}

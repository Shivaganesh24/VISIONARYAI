package com.programminghut.realtime_object

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pro-Designer Ground-Locked Digital Eye Overlay.
 * Renders a dynamic "Safe Walking Lane" following traversable ground around obstacles,
 * firmly anchored and attached to the physical floor plane.
 */
class VisionOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private var currentPlan: PathPlan? = null
    private var obstacles: List<NavigationMemory.TrackedObstacle> = emptyList()
    
    private var rollDegrees = 0f
    private var pitchDegrees = 0f
    
    // Smoothed orientation to avoid jitter
    private var smoothRoll = 0f
    private var smoothPitch = 0f

    private val lanePaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val laneEdgePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        isAntiAlias = true
    }

    private val stopBarPaint = Paint().apply {
        color = Color.parseColor("#FF1744")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#28FFFFFF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 38f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(8f, 0f, 0f, Color.BLACK)
    }

    private val corridorPath = Path()

    fun updateData(plan: PathPlan, obstacles: List<NavigationMemory.TrackedObstacle>) {
        this.currentPlan = plan
        this.obstacles = obstacles
        invalidate()
    }
    
    fun updateOrientation(rollRad: Float, pitchRad: Float) {
        val targetRoll = Math.toDegrees(rollRad.toDouble()).toFloat()
        val targetPitch = Math.toDegrees(pitchRad.toDouble()).toFloat()
        
        // Heavy low pass filter for rock-solid stability
        smoothRoll = smoothRoll * 0.88f + targetRoll * 0.12f
        smoothPitch = smoothPitch * 0.88f + targetPitch * 0.12f
        
        // Dampen roll when pitch is high (looking down at floor) to prevent sensor gimbal lock from swinging the UI
        val pitchAbs = abs(smoothPitch)
        val rollDamping = (1.0f - (pitchAbs / 50f)).coerceIn(0f, 1f)
        
        this.rollDegrees = (smoothRoll * rollDamping).coerceIn(-12f, 12f)
        this.pitchDegrees = smoothPitch.coerceIn(-30f, 30f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return

        // 1. Subtle Contrast Tint
        canvas.drawColor(Color.argb(25, 0, 0, 0))

        val baseHorizonY = h * 0.45f
        // Pitch shift: Gentle vertical offset for camera pitch
        val pitchShift = - (pitchDegrees * 8f).coerceIn(-h * 0.15f, h * 0.15f)

        // 2. Floor-Anchored Navigation Layer
        canvas.save()
        // Stabilize roll and pitch around screen center so lane stays attached to the floor
        canvas.rotate(-rollDegrees, w / 2f, h / 2f)
        canvas.translate(0f, pitchShift)

        drawGroundGrid(canvas, w, h, baseHorizonY)

        currentPlan?.let { plan ->
            if (plan.isCollisionImminent || plan.suggestedAction == Action.STOP_IMMEDIATELY) {
                drawStopZone(canvas, w, h, baseHorizonY)
            } else {
                drawSafeWalkingLane(canvas, plan, w, h, baseHorizonY)
            }
        }

        canvas.restore()

        // 3. Floating Hazard Indicators & Ground Footprints
        drawHazardMarkers(canvas, w, h, baseHorizonY + pitchShift)
    }

    private fun drawGroundGrid(canvas: Canvas, w: Float, h: Float, horizonY: Float) {
        // Perspective grid lines meeting at horizon
        val numPerspectiveLines = 10
        for (i in 0..numPerspectiveLines) {
            val x = w * i / numPerspectiveLines.toFloat()
            val targetX = w / 2f + (x - w / 2f) * 0.12f
            canvas.drawLine(x, h, targetX, horizonY, gridPaint)
        }
        // Distance markers
        val numDistanceRows = 6
        for (i in 0..numDistanceRows) {
            val t = i / numDistanceRows.toFloat()
            val y = h - (h - horizonY) * t
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
    }

    private fun drawSafeWalkingLane(canvas: Canvas, plan: PathPlan, w: Float, h: Float, horizonY: Float) {
        corridorPath.reset()

        val points = plan.pathPoints
        if (points.isEmpty()) {
            // Classic straight corridor rod
            val baseWidth = w * 0.85f
            val topWidth = w * 0.12f
            val steeringOffset = (plan.steeringAngle / 35f) * (w * 0.45f)
            val topCenter = (w / 2f) + steeringOffset

            corridorPath.moveTo(w / 2f - baseWidth / 2f, h)
            corridorPath.lineTo(w / 2f + baseWidth / 2f, h)
            corridorPath.lineTo(topCenter + topWidth / 2f, horizonY)
            corridorPath.lineTo(topCenter - topWidth / 2f, horizonY)
            corridorPath.close()
        } else {
            val leftPoints = mutableListOf<PointF>()
            val rightPoints = mutableListOf<PointF>()
            val centerPoints = mutableListOf<PointF>()

            for (i in points.indices) {
                val pt = points[i]
                // pt.y ranges from 1.0 (feet) to 0.45 (horizon)
                val t = ((1.0f - pt.y) / (1.0f - 0.45f)).coerceIn(0f, 1f) // 0 at feet, 1 at horizon
                val screenY = h - t * (h - horizonY)
                
                // Perspective width decreases from base at feet to top at horizon
                val laneWidth = w * (0.80f * (1.0f - t) + 0.12f * t)
                
                // Ensure the base at user's feet (i = 0) stays firmly centered at screen bottom center
                val screenX = if (i == 0) (w / 2f) else (pt.x * w)

                leftPoints.add(PointF(screenX - laneWidth / 2f, screenY))
                rightPoints.add(PointF(screenX + laneWidth / 2f, screenY))
                centerPoints.add(PointF(screenX, screenY))
            }

            // Build smooth left edge
            corridorPath.moveTo(leftPoints[0].x, leftPoints[0].y)
            for (i in 1 until leftPoints.size) {
                val pPrev = leftPoints[i - 1]
                val pCurr = leftPoints[i]
                val midX = (pPrev.x + pCurr.x) / 2f
                val midY = (pPrev.y + pCurr.y) / 2f
                corridorPath.quadTo(pPrev.x, pPrev.y, midX, midY)
            }
            corridorPath.lineTo(leftPoints.last().x, leftPoints.last().y)

            // Connect to top right
            corridorPath.lineTo(rightPoints.last().x, rightPoints.last().y)

            // Build smooth right edge back to bottom
            for (i in rightPoints.size - 2 downTo 0) {
                val pNext = rightPoints[i + 1]
                val pCurr = rightPoints[i]
                val midX = (pNext.x + pCurr.x) / 2f
                val midY = (pNext.y + pCurr.y) / 2f
                corridorPath.quadTo(pNext.x, pNext.y, midX, midY)
            }
            corridorPath.lineTo(rightPoints[0].x, rightPoints[0].y)
            corridorPath.close()

            // Center dash guide line along the road
            if (centerPoints.size > 1) {
                val centerPath = Path().apply {
                    moveTo(centerPoints[0].x, centerPoints[0].y)
                    for (i in 1 until centerPoints.size) {
                        lineTo(centerPoints[i].x, centerPoints[i].y)
                    }
                }
                val dashPaint = Paint(laneEdgePaint).apply {
                    strokeWidth = 6f
                    color = Color.WHITE
                    alpha = 220
                    pathEffect = DashPathEffect(floatArrayOf(35f, 35f), 0f)
                }
                canvas.drawPath(centerPath, dashPaint)
            }
        }

        // Color coding: Green for clear, Yellow for proceed carefully
        val laneColor = when (plan.suggestedAction) {
            Action.PROCEED_CAREFULLY -> Color.parseColor("#FFD600") // Warm Yellow/Amber
            Action.STEER_LEFT, Action.STEER_RIGHT -> Color.parseColor("#00E676") // Green lane
            else -> Color.parseColor("#00E676") // Vibrant Green
        }
        
        lanePaint.color = laneColor
        lanePaint.alpha = 125
        canvas.drawPath(corridorPath, lanePaint)
        
        laneEdgePaint.color = laneColor
        laneEdgePaint.alpha = 240
        canvas.drawPath(corridorPath, laneEdgePaint)
    }

    private fun drawStopZone(canvas: Canvas, w: Float, h: Float, horizonY: Float) {
        // Red STOP Bar across floor lane base
        val barHeight = h * 0.16f
        canvas.drawRect(0f, h - barHeight, w, h, stopBarPaint)
        
        stopBarPaint.alpha = 100
        canvas.drawRect(0f, horizonY, w, h - barHeight, stopBarPaint)
        
        textPaint.textSize = 90f
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("STOP - HAZARD", w / 2f, h * 0.93f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 38f
    }

    private fun drawHazardMarkers(canvas: Canvas, w: Float, h: Float, currentHorizonY: Float) {
        val hazardPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }

        val groundShadowPaint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        for (obs in obstacles) {
            val rect = RectF(obs.rect.left * w, obs.rect.top * h, obs.rect.right * w, obs.rect.bottom * h)
            val isUrgent = obs.riskScore > 75 || obs.distance < 4.0f
            
            hazardPaint.color = if (isUrgent) Color.parseColor("#FF1744") else Color.parseColor("#00E5FF")
            
            // Floor footprint shadow
            groundShadowPaint.color = if (isUrgent) Color.argb(120, 255, 23, 68) else Color.argb(80, 0, 229, 255)
            val groundY = rect.bottom.coerceIn(currentHorizonY, h)
            val footprintWidth = max(rect.width(), 40f)
            val footprintRect = RectF(
                rect.centerX() - footprintWidth / 2f,
                groundY - 15f,
                rect.centerX() + footprintWidth / 2f,
                groundY + 15f
            )
            canvas.drawOval(footprintRect, groundShadowPaint)

            // Corner brackets
            val l = min(35f, rect.width() / 3f)
            canvas.drawLine(rect.left, rect.top + l, rect.left, rect.top, hazardPaint)
            canvas.drawLine(rect.left, rect.top, rect.left + l, rect.top, hazardPaint)
            
            canvas.drawLine(rect.right - l, rect.top, rect.right, rect.top, hazardPaint)
            canvas.drawLine(rect.right, rect.top, rect.right, rect.top + l, hazardPaint)
            
            canvas.drawLine(rect.right, rect.bottom - l, rect.right, rect.bottom, hazardPaint)
            canvas.drawLine(rect.right, rect.bottom, rect.right - l, rect.bottom, hazardPaint)
            
            canvas.drawLine(rect.left + l, rect.bottom, rect.left, rect.bottom, hazardPaint)
            canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - l, hazardPaint)

            val labelText = "${obs.label.uppercase()} ${String.format(Locale.US, "%.1f", obs.distance)}FT"
            canvas.drawText(labelText, rect.left, max(rect.top - 12f, 40f), textPaint)
        }
    }
}

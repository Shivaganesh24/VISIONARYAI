package com.programminghut.realtime_object

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SafePathPlanner {

    private var lastWaypoints: List<PointF> = emptyList()
    private var lastSteeringAngle = 0f
    private val STEERING_SMOOTHING = 0.80f
    private val WAYPOINT_SMOOTHING = 0.82f

    // Grid config
    private val NUM_DEPTH_ROWS = 8
    private val NUM_COLUMNS = 21 // x from 0.0 to 1.0 in steps of 0.05
    private val Y_HORIZON = 0.45f
    private val Y_FEET = 1.0f

    /**
     * Enhanced Ground Traversability & Safe Path Planner.
     * Evaluates ground grid, maps detected obstacle footprints, computes optimal
     * traversable corridor waypoints around hazards, and generates path guidance.
     */
    fun planPath(obstacles: List<NavigationMemory.TrackedObstacle>): PathPlan {
        if (obstacles.isEmpty()) {
            val waypoints = List(NUM_DEPTH_ROWS) { r ->
                val rowY = Y_FEET - (r.toFloat() / (NUM_DEPTH_ROWS - 1)) * (Y_FEET - Y_HORIZON)
                PointF().apply { x = 0.5f; y = rowY }
            }
            lastWaypoints = waypoints
            lastSteeringAngle = 0f
            return PathPlan(
                steeringAngle = 0f,
                confidence = 1.0f,
                suggestedAction = Action.MOVE_FORWARD,
                occupancyMap = FloatArray(NUM_DEPTH_ROWS * NUM_COLUMNS),
                isCollisionImminent = false,
                pathPoints = waypoints
            )
        }

        // 1. Build Ground Occupancy Grid
        val occupancyGrid = Array(NUM_DEPTH_ROWS) { FloatArray(NUM_COLUMNS) }
        
        // Critical collision check for obstacles directly in front & very close
        val criticalObstacles = obstacles.filter {
            val centerX = it.normalizedCenterX
            it.distance < 3.8f && centerX in 0.25f..0.75f
        }

        // Map obstacles onto occupancy grid
        for (obs in obstacles) {
            if (obs.distance > 16.0f) continue

            val obsLeft = if (obs.rect.left == 0f && obs.rect.right == 0f && obs.normalizedCenterX > 0f) {
                (obs.normalizedCenterX - 0.15f).coerceAtLeast(0f)
            } else {
                obs.rect.left
            }

            val obsRight = if (obs.rect.left == 0f && obs.rect.right == 0f && obs.normalizedCenterX > 0f) {
                (obs.normalizedCenterX + 0.15f).coerceAtMost(1f)
            } else {
                obs.rect.right
            }

            val obsTop = if (obs.rect.top == 0f && obs.rect.bottom == 0f) 0.5f else obs.rect.top
            val obsBottom = if (obs.rect.top == 0f && obs.rect.bottom == 0f) 0.9f else obs.rect.bottom

            val obsCenterX = (obsLeft + obsRight) / 2f
            val obsWidth = max(0.05f, obsRight - obsLeft)

            // Determine ground footprint X bounds (with safety buffer)
            val safetyBuffer = when {
                obs.distance < 4.0f -> 0.14f
                obs.distance < 8.0f -> 0.10f
                else -> 0.07f
            }

            // Expand footprint to screen edge if obstacle is close to edge (prevent tight squeeze)
            val minXBoundary = if (obsLeft <= 0.25f) 0.0f else (obsLeft - safetyBuffer)
            val maxXBoundary = if (obsRight >= 0.75f) 1.0f else (obsRight + safetyBuffer)
            val obsMinX = minXBoundary.coerceIn(0f, 1f)
            val obsMaxX = maxXBoundary.coerceIn(0f, 1f)

            // Determine depth/Y span for obstacle on ground plane
            val obsBaseY = obsBottom.coerceIn(Y_HORIZON, Y_FEET)
            val obsTopY = obsTop.coerceIn(Y_HORIZON, Y_FEET)

            for (r in 0 until NUM_DEPTH_ROWS) {
                val rowY = Y_FEET - (r.toFloat() / (NUM_DEPTH_ROWS - 1)) * (Y_FEET - Y_HORIZON)
                
                // Active depth range for obstacle
                if (rowY <= obsBaseY + 0.1f && rowY >= obsTopY - 0.05f) {
                    val severity = (16.0f - obs.distance) / 16.0f
                    for (c in 0 until NUM_COLUMNS) {
                        val colX = c.toFloat() / (NUM_COLUMNS - 1)
                        if (colX in obsMinX..obsMaxX) {
                            // Solid hazard penalty inside footprint
                            val distToCenter = abs(colX - obsCenterX)
                            val centerFactor = max(0.4f, 1.0f - (distToCenter / (obsWidth / 2f + safetyBuffer)))
                            occupancyGrid[r][c] += 8.0f * severity * centerFactor + 5.0f
                        } else {
                            // Soft proximity penalty outside footprint
                            val distToEdge = min(abs(colX - obsMinX), abs(colX - obsMaxX))
                            if (distToEdge < 0.15f) {
                                occupancyGrid[r][c] += 1.5f * severity * (1.0f - distToEdge / 0.15f)
                            }
                        }
                    }
                }
            }
        }

        // Flatten 2D occupancy grid into 1D float array for PathPlan metadata
        val flatOccupancy = FloatArray(NUM_DEPTH_ROWS * NUM_COLUMNS)
        var idx = 0
        for (r in 0 until NUM_DEPTH_ROWS) {
            for (c in 0 until NUM_COLUMNS) {
                flatOccupancy[idx++] = occupancyGrid[r][c]
            }
        }

        // 2. Find Traversable Path Waypoints (Dynamic Programming search)
        val dpCost = Array(NUM_DEPTH_ROWS) { FloatArray(NUM_COLUMNS) { Float.MAX_VALUE } }
        val dpParent = Array(NUM_DEPTH_ROWS) { IntArray(NUM_COLUMNS) { -1 } }

        // Initialize row 0 (at feet y=1.0). Strongly anchor start position at center (x = 0.5)
        val centerCol = NUM_COLUMNS / 2
        for (c in 0 until NUM_COLUMNS) {
            val colX = c.toFloat() / (NUM_COLUMNS - 1)
            val feetCenterAnchor = abs(colX - 0.5f) * 3.5f
            dpCost[0][c] = occupancyGrid[0][c] + feetCenterAnchor
        }

        // Forward DP over depth rows
        for (r in 1 until NUM_DEPTH_ROWS) {
            for (c in 0 until NUM_COLUMNS) {
                val colX = c.toFloat() / (NUM_COLUMNS - 1)
                val cellCost = occupancyGrid[r][c]
                val centerBias = abs(colX - 0.5f) * 0.5f

                for (prevC in 0 until NUM_COLUMNS) {
                    val prevX = prevC.toFloat() / (NUM_COLUMNS - 1)
                    val transitionCost = abs(colX - prevX) * 2.0f // Penalty for sharp turns
                    val totalCost = dpCost[r - 1][prevC] + cellCost + centerBias + transitionCost

                    if (totalCost < dpCost[r][c]) {
                        dpCost[r][c] = totalCost
                        dpParent[r][c] = prevC
                    }
                }
            }
        }

        // Find best terminal column at horizon (row NUM_DEPTH_ROWS - 1)
        var bestCol = centerCol
        var minTotalCost = Float.MAX_VALUE
        for (c in 0 until NUM_COLUMNS) {
            if (dpCost[NUM_DEPTH_ROWS - 1][c] < minTotalCost) {
                minTotalCost = dpCost[NUM_DEPTH_ROWS - 1][c]
                bestCol = c
            }
        }

        // Backtrack optimal path waypoints
        val rawWaypoints = ArrayList<PointF>(NUM_DEPTH_ROWS)
        var currCol = bestCol
        for (r in NUM_DEPTH_ROWS - 1 downTo 0) {
            val rowY = Y_FEET - (r.toFloat() / (NUM_DEPTH_ROWS - 1)) * (Y_FEET - Y_HORIZON)
            val rawColX = currCol.toFloat() / (NUM_COLUMNS - 1)
            // Clamp waypoints to stay inside visible bounds
            val clampedColX = rawColX.coerceIn(0.18f, 0.82f)
            rawWaypoints.add(PointF().apply { x = clampedColX; y = rowY })
            currCol = dpParent[r][currCol].takeIf { it != -1 } ?: currCol
        }
        rawWaypoints.reverse() // From feet (y=1.0) to horizon (y=0.45)

        // 3. Temporal Waypoint Smoothing across frames
        val smoothedWaypoints = mutableListOf<PointF>()
        if (lastWaypoints.size == rawWaypoints.size) {
            for (i in rawWaypoints.indices) {
                val raw = rawWaypoints[i]
                val prev = lastWaypoints[i]
                val smX = (raw.x * (1f - WAYPOINT_SMOOTHING)) + (prev.x * WAYPOINT_SMOOTHING)
                smoothedWaypoints.add(PointF().apply { x = smX.coerceIn(0.18f, 0.82f); y = raw.y })
            }
        } else {
            smoothedWaypoints.addAll(rawWaypoints)
        }
        lastWaypoints = smoothedWaypoints

        // 4. Calculate Steering Angle & Action
        val targetWaypointX = if (smoothedWaypoints.size >= 4) {
            (smoothedWaypoints[2].x + smoothedWaypoints[3].x) / 2f
        } else {
            smoothedWaypoints.lastOrNull()?.x ?: 0.5f
        }

        val rawSteeringAngle = (targetWaypointX - 0.5f) * 80f
        val finalSteeringAngle = (rawSteeringAngle * (1f - STEERING_SMOOTHING)) + (lastSteeringAngle * STEERING_SMOOTHING)
        lastSteeringAngle = finalSteeringAngle

        val bottomRowCost = occupancyGrid[0].minOrNull() ?: 0f
        val isFullBlockage = criticalObstacles.any { it.distance < 3.2f } || bottomRowCost > 4.0f
        
        val centralBlockage = obstacles.any {
            val centerX = it.normalizedCenterX
            centerX in 0.3f..0.7f && it.distance < 6.5f
        }

        val action = when {
            isFullBlockage -> Action.STOP_IMMEDIATELY
            criticalObstacles.isNotEmpty() -> Action.PROCEED_CAREFULLY
            centralBlockage && abs(finalSteeringAngle) > 12f -> {
                if (finalSteeringAngle < 0) Action.STEER_LEFT else Action.STEER_RIGHT
            }
            centralBlockage -> Action.PROCEED_CAREFULLY
            else -> Action.MOVE_FORWARD
        }

        val confidence = (1.0f - (minTotalCost / (NUM_DEPTH_ROWS * 3f))).coerceIn(0.2f, 1.0f)

        return PathPlan(
            steeringAngle = finalSteeringAngle.coerceIn(-45f, 45f),
            confidence = confidence,
            suggestedAction = action,
            occupancyMap = flatOccupancy,
            isCollisionImminent = isFullBlockage,
            pathPoints = smoothedWaypoints
        )
    }
}

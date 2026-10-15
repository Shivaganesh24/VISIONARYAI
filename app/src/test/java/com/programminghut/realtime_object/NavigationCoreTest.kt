package com.programminghut.realtime_object

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test

class NavigationCoreTest {

    @Test
    fun hazardLevel_classification_isCorrect() {
        val riskEngine = RiskAssessmentEngine()
        assertEquals(RiskAssessmentEngine.HazardLevel.CRITICAL, riskEngine.getHazardLevel(95f))
        assertEquals(RiskAssessmentEngine.HazardLevel.HIGH, riskEngine.getHazardLevel(75f))
        assertEquals(RiskAssessmentEngine.HazardLevel.MEDIUM, riskEngine.getHazardLevel(50f))
        assertEquals(RiskAssessmentEngine.HazardLevel.LOW, riskEngine.getHazardLevel(20f))
        assertEquals(RiskAssessmentEngine.HazardLevel.NONE, riskEngine.getHazardLevel(5f))
    }

    @Test
    fun safePathPlanner_emptyObstacles_returnsMoveForward() {
        val planner = SafePathPlanner()
        val plan = planner.planPath(emptyList())
        assertEquals(Action.MOVE_FORWARD, plan.suggestedAction)
        assertEquals(0f, plan.steeringAngle, 0.01f)
        assertFalse(plan.isCollisionImminent)
        assertTrue(plan.pathPoints.isNotEmpty())
    }

    @Test
    fun safePathPlanner_obstacleOnLeft_steersRight() {
        val planner = SafePathPlanner()
        val leftObstacle = NavigationMemory.TrackedObstacle(
            id = "obs1",
            label = "chair",
            rect = RectF(),
            distance = 5.0f,
            riskScore = 60f,
            lastSeen = System.currentTimeMillis(),
            normalizedCenterX = 0.29f
        )
        val plan = planner.planPath(listOf(leftObstacle))
        assertTrue("Expected steering right (>0), got ${plan.steeringAngle}", plan.steeringAngle > 0f)
        assertTrue(plan.pathPoints.isNotEmpty())
        val midWaypoint = plan.pathPoints[3]
        assertTrue("Expected waypoint X > 0.5, got ${midWaypoint.x}", midWaypoint.x > 0.5f)
    }

    @Test
    fun safePathPlanner_obstacleOnRight_steersLeft() {
        val planner = SafePathPlanner()
        val rightObstacle = NavigationMemory.TrackedObstacle(
            id = "obs2",
            label = "table",
            rect = RectF(),
            distance = 5.0f,
            riskScore = 60f,
            lastSeen = System.currentTimeMillis(),
            normalizedCenterX = 0.71f
        )
        val plan = planner.planPath(listOf(rightObstacle))
        assertTrue("Expected steering left (<0), got ${plan.steeringAngle}", plan.steeringAngle < 0f)
        assertTrue(plan.pathPoints.isNotEmpty())
        val midWaypoint = plan.pathPoints[3]
        assertTrue("Expected waypoint X < 0.5, got ${midWaypoint.x}", midWaypoint.x < 0.5f)
    }

    @Test
    fun blueprintManager_pathfinding_returnsValidConnections() {
        val blueprintManager = BlueprintManager()
        val path = blueprintManager.findPath("living_room", "exit")
        assertNotNull(path)
        assertTrue(path!!.isNotEmpty())
        assertEquals("exit", path.last().to)
    }

    @Test
    fun sceneContextEngine_emptyDetections_returnsOpenPathMessage() {
        val sceneEngine = SceneContextEngine()
        val plan = PathPlan(0f, 1f, Action.MOVE_FORWARD, FloatArray(0))
        val description = sceneEngine.generateDescription(emptyList(), plan)
        assertTrue(description.contains("completely open"))
    }
}

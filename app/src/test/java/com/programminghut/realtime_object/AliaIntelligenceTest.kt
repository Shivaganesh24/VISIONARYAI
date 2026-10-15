package com.programminghut.realtime_object

import android.graphics.RectF
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AliaIntelligenceTest {

    @Test
    fun intentClassifier_classifiesScenariosCorrectly() {
        val classifier = IntentClassifier()

        val intent1 = classifier.classify("What's the scenario?")
        assertEquals(IntentCategory.SCENE_DESCRIPTION, intent1.category)

        val intent2 = classifier.classify("Where is my bottle?")
        assertEquals(IntentCategory.OBJECT_SEARCH, intent2.category)
        assertEquals("bottle", intent2.targetObject)

        val intent3 = classifier.classify("What is the nearest object?")
        assertEquals(IntentCategory.NEAREST_OBJECT, intent3.category)

        val intent4 = classifier.classify("How many feet away is the chair?")
        assertEquals(IntentCategory.OBJECT_DISTANCE, intent4.category)
        assertEquals("chair", intent4.targetObject)

        val intent5 = classifier.classify("Which currency note am I holding?")
        assertEquals(IntentCategory.CURRENCY_RECOGNITION, intent5.category)

        val intent6 = classifier.classify("Read this text on the board")
        assertEquals(IntentCategory.TEXT_READING, intent6.category)

        val intent7 = classifier.classify("Is there a person in front of me?")
        assertEquals(IntentCategory.PERSON_DESCRIPTION, intent7.category)
    }

    @Test
    fun aliaAgent_objectSearch_returnsClockPositionAndDistance() = runBlocking {
        val aliaAgent = AliaAgent()
        val bottle = NavigationMemory.TrackedObstacle(
            id = "1",
            label = "bottle",
            rect = RectF(0.70f, 0.2f, 0.80f, 0.6f),
            distance = 4.2f,
            riskScore = 50f,
            lastSeen = System.currentTimeMillis(),
            normalizedCenterX = 0.75f // 2 o'clock
        )

        val response = aliaAgent.processQuery(
            userQuery = "Where is my bottle?",
            trackedObstacles = listOf(bottle),
            roomName = "Living Room"
        )

        assertTrue(response.contains("bottle"))
        assertTrue(response.contains("2 o'clock"))
        assertTrue(response.contains("4 feet"))
    }

    @Test
    fun aliaAgent_nearestObject_returnsCorrectItem() = runBlocking {
        val aliaAgent = AliaAgent()
        val chair = NavigationMemory.TrackedObstacle(
            id = "1",
            label = "chair",
            rect = RectF(0.38f, 0.2f, 0.48f, 0.6f),
            distance = 3.0f,
            riskScore = 40f,
            lastSeen = System.currentTimeMillis(),
            normalizedCenterX = 0.43f // 11 o'clock
        )
        val table = NavigationMemory.TrackedObstacle(
            id = "2",
            label = "dining table",
            rect = RectF(0.60f, 0.2f, 0.80f, 0.6f),
            distance = 8.0f,
            riskScore = 20f,
            lastSeen = System.currentTimeMillis(),
            normalizedCenterX = 0.70f
        )

        val response = aliaAgent.processQuery(
            userQuery = "What is the nearest object?",
            trackedObstacles = listOf(chair, table)
        )

        assertTrue(response.contains("chair"))
        assertTrue(response.contains("11 o'clock"))
        assertTrue(response.contains("3 feet"))
    }

    @Test
    fun aliaAgent_objectNotFound_returnsEmpatheticPrompt() = runBlocking {
        val aliaAgent = AliaAgent()
        val response = aliaAgent.processQuery(
            userQuery = "Where is my bottle?",
            trackedObstacles = emptyList()
        )

        assertTrue(response.contains("can't see a bottle"))
        assertTrue(response.contains("turn"))
    }
}

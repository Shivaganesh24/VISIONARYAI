package com.programminghut.realtime_object

import android.graphics.RectF
import android.os.Bundle
import android.speech.tts.TextToSpeech
import java.util.UUID
import kotlin.math.abs

/**
 * Tactical Precision Guidance System.
 * Delivers sharp, short, and accurate movement instructions.
 */
class GuidanceManager(private val tts: TextToSpeech?) {

    interface OnArrivalListener {
        fun onArrivedAtTarget(roomName: String)
    }

    private var arrivalListener: OnArrivalListener? = null
    private var lastGuidanceTime: Long = 0
    private var lastAnnouncement = ""
    private var lastPersonAnnouncementTime: Long = 0
    private var lastPersonId = ""
    private val REASSURANCE_INTERVAL = 4500L // Periodic reassurance when path is green
    private val MIN_GUIDANCE_INTERVAL = 1800L
    
    private var wasLastMovingAhead = false

    private var currentPath: List<BlueprintManager.Connection>? = null
    private var currentStepIndex = 0
    private var targetRoomName = ""

    fun setOnArrivalListener(listener: OnArrivalListener) {
        this.arrivalListener = listener
    }

    fun setNavigationPath(path: List<BlueprintManager.Connection>, targetName: String) {
        this.currentPath = path
        this.currentStepIndex = 0
        this.targetRoomName = targetName
        speak("Target $targetName set. Path clear. Move ahead.", System.currentTimeMillis(), isCritical = true)
    }

    fun provideGuidance(
        plan: PathPlan, 
        primaryObstacle: NavigationMemory.TrackedObstacle?,
        allObstacles: List<NavigationMemory.TrackedObstacle>,
        prediction: CollisionPredictor.Prediction,
        isCameraBlocked: Boolean = false,
        currentRoom: BlueprintManager.Room? = null,
        isUserTalking: Boolean = false
    ) {
        val currentTime = System.currentTimeMillis()
        
        if (isCameraBlocked) {
            speak("Camera obstructed. Check lens.", currentTime, isCritical = true)
            return
        }

        // 1. EMERGENCY SAFETY (Highest Priority)
        if (plan.isCollisionImminent || plan.suggestedAction == Action.STOP_IMMEDIATELY) {
            if (isUserTalking) return // Fully silence navigation to focus on Alia

            val label = primaryObstacle?.label ?: "Obstacle"
            val direction = getDirectionText(primaryObstacle?.rect)
            val dist = primaryObstacle?.distance?.toInt() ?: 3
            speak("STOP. $label $direction. $dist feet.", currentTime, isCritical = true, pan = calculatePan(primaryObstacle))
            wasLastMovingAhead = false
            return
        }

        // If the user is talking to the assistant, suppress non-critical navigation feedback
        if (isUserTalking) return

        // 2. PERSON AWARENESS
        val nearbyPerson = allObstacles.find { it.label == "person" && it.distance < 12f }
        if (nearbyPerson != null) {
            val isNewPerson = nearbyPerson.id != lastPersonId
            val timeSinceLastPersonAlert = currentTime - lastPersonAnnouncementTime
            
            if (isNewPerson || timeSinceLastPersonAlert > 7000L) {
                val direction = getDirectionText(nearbyPerson.rect)
                speak("Person $direction. ${nearbyPerson.distance.toInt()} feet.", currentTime, isCritical = true)
                lastPersonAnnouncementTime = currentTime
                lastPersonId = nearbyPerson.id
            }
        }

        if (isUserTalking) return

        // 3. PROACTIVE STEERING
        val angle = plan.steeringAngle
        val steeringMessage = when {
            angle < -25f -> "Turn left."
            angle < -12f -> "Veer left."
            angle < -5f -> "Slightly left."
            angle > 25f -> "Turn right."
            angle > 12f -> "Veer right."
            angle > 5f -> "Slightly right."
            else -> ""
        }

        if (steeringMessage.isNotEmpty() && (currentTime - lastGuidanceTime > MIN_GUIDANCE_INTERVAL || steeringMessage != lastAnnouncement)) {
            speak(steeringMessage, currentTime, pan = if (angle < 0) -0.4f else 0.4f)
            wasLastMovingAhead = false
            return
        }

        // 4. GREEN PATH / CLEAR ROAD: MOVE AHEAD FEEDBACK
        if (plan.suggestedAction == Action.MOVE_FORWARD && abs(angle) <= 8f) {
            if (!wasLastMovingAhead || (currentTime - lastGuidanceTime > REASSURANCE_INTERVAL)) {
                speak("Path clear. Move ahead.", currentTime, isCritical = true)
                wasLastMovingAhead = true
            }
            return
        }

        // 5. LANDMARK ARRIVAL
        handleWayfinding(currentRoom, currentTime)
    }

    private fun calculateClockPosition(obs: NavigationMemory.TrackedObstacle?): Int {
        if (obs == null) return 12
        val x = (obs.rect.left + obs.rect.right) / 2f
        return when {
            x < 0.15f -> 9
            x < 0.35f -> 10
            x < 0.45f -> 11
            x < 0.55f -> 12
            x < 0.65f -> 1
            x < 0.85f -> 2
            else -> 3
        }
    }

    fun announceScene(obstacles: List<NavigationMemory.TrackedObstacle>, plan: PathPlan) {
        val currentTime = System.currentTimeMillis()
        if (obstacles.isEmpty()) {
            speak("The path is wide open. Move ahead.", currentTime, isCritical = true)
            return
        }

        val sorted = obstacles.sortedBy { it.distance }
        val nearest = sorted.first()
        val clock = calculateClockPosition(nearest)
        
        val description = "Nearest is a ${nearest.label} at $clock o'clock, ${nearest.distance.toInt()} feet away. " +
                if (obstacles.size > 1) "Total ${obstacles.size} items detected." else ""
        
        speak(description, currentTime, isCritical = true)
    }

    fun speakOCRResult(text: String) {
        speak("Detected text says: $text", System.currentTimeMillis(), isCritical = true)
    }

    fun confirmPathClear(plan: PathPlan) {
        val currentTime = System.currentTimeMillis()
        if (plan.suggestedAction == Action.MOVE_FORWARD && !plan.isCollisionImminent && plan.confidence > 0.7f) {
            speak("Path clear. Move ahead.", currentTime, isCritical = true)
        } else if (plan.isCollisionImminent || plan.suggestedAction == Action.STOP_IMMEDIATELY) {
            speak("Path is blocked. Do not move.", currentTime, isCritical = true)
        } else {
            val direction = when {
                plan.steeringAngle < -8f -> "slightly left"
                plan.steeringAngle > 8f -> "slightly right"
                else -> "carefully"
            }
            speak("Path is not fully clear. Proceed $direction.", currentTime, isCritical = true)
        }
    }

    private fun handleWayfinding(currentRoom: BlueprintManager.Room?, currentTime: Long) {
        val path = currentPath ?: return
        if (currentRoom?.name == targetRoomName) {
            speak("Arrived at $targetRoomName.", currentTime, isCritical = true)
            currentPath = null
            arrivalListener?.onArrivedAtTarget(targetRoomName)
            return
        }
        val nextStep = path.getOrNull(currentStepIndex)
        if (currentRoom != null && currentRoom.id == nextStep?.to) {
            currentStepIndex++
            val followingStep = path.getOrNull(currentStepIndex)
            if (followingStep != null) {
                speak("Next, ${followingStep.direction}.", currentTime, isCritical = true)
            }
        }
    }

    private fun getDirectionText(rect: RectF?): String {
        if (rect == null) return "ahead"
        val x = (rect.left + rect.right) / 2f
        return when {
            x < 0.4f -> "on your left"
            x > 0.6f -> "on your right"
            else -> "directly ahead"
        }
    }

    private fun calculatePan(obs: NavigationMemory.TrackedObstacle?): Float {
        if (obs == null) return 0f
        val x = (obs.rect.left + obs.rect.right) / 2f
        return ((x - 0.5f) * 2.0f).coerceIn(-1.0f, 1.0f)
    }

    private fun speak(message: String, time: Long, isCritical: Boolean = false, pan: Float = 0f) {
        val throttle = if (isCritical) 1800L else 3000L
        if (message == lastAnnouncement && time - lastGuidanceTime < throttle) return
        
        // If it's a different message but too soon, and NOT critical, skip it to avoid chatter
        if (!isCritical && time - lastGuidanceTime < 1500L) return

        val params = Bundle()
        if (pan != 0f) params.putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, pan)
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, UUID.randomUUID().toString())
        lastGuidanceTime = time
        lastAnnouncement = message
    }
}

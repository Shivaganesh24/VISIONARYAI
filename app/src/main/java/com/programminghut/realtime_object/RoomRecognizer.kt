package com.programminghut.realtime_object

import android.util.Log

/**
 * Recognizes the current room based on detected landmarks.
 * Uses temporal voting to ensure stable room identification.
 */
class RoomRecognizer(private val blueprintManager: BlueprintManager) {

    private val roomVotes = mutableMapOf<String, Int>()
    private var currentRoom: BlueprintManager.Room? = null
    private val VOTE_THRESHOLD = 15 // Number of frames a landmark must be seen to switch rooms

    fun processDetections(trackedObstacles: List<NavigationMemory.TrackedObstacle>): BlueprintManager.Room? {
        for (obs in trackedObstacles) {
            val room = blueprintManager.getRoomByLandmark(obs.label)
            if (room != null) {
                roomVotes[room.id] = (roomVotes[room.id] ?: 0) + 1
                
                if ((roomVotes[room.id] ?: 0) > VOTE_THRESHOLD) {
                    if (currentRoom?.id != room.id) {
                        currentRoom = room
                        Log.d("RoomRecognizer", "Room detected: ${room.name}")
                        // Reset other votes to prevent rapid switching
                        roomVotes.clear()
                        roomVotes[room.id] = VOTE_THRESHOLD
                    }
                }
            }
        }
        
        // Slowly decay votes for rooms not seen
        val iterator = roomVotes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (trackedObstacles.none { blueprintManager.getRoomByLandmark(it.label)?.id == entry.key }) {
                entry.setValue(entry.value - 1)
                if (entry.value <= 0) iterator.remove()
            }
        }

        return currentRoom
    }
}

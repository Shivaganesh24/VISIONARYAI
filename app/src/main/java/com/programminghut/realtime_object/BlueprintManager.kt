package com.programminghut.realtime_object

import android.graphics.Bitmap
import android.util.Log

/**
 * Manages the house layout and pathfinding between rooms.
 * In a production app, this would parse an image using CV/OCR.
 * For this implementation, it provides a graph-based topological map.
 */
class BlueprintManager {

    data class Room(val id: String, val name: String, val landmarks: List<String>)
    data class Connection(val from: String, val to: String, val direction: String)

    private val rooms = mutableMapOf<String, Room>()
    private val connections = mutableListOf<Connection>()

    init {
        // Default Mock Layout (Can be replaced by "Scanning" logic)
        addRoom("living_room", "Living Room", listOf("tv", "dining table"))
        addRoom("kitchen", "Kitchen", listOf("refrigerator", "microwave", "sink", "oven"))
        addRoom("bedroom", "Bedroom", listOf("wardrobe"))
        addRoom("bathroom", "Bathroom", listOf("toilet"))
        addRoom("exit", "Front Door", listOf("vase", "clock"))

        connect("living_room", "kitchen", "forward then right")
        connect("living_room", "bedroom", "left hallway")
        connect("living_room", "exit", "straight ahead to the exit")
        connect("bedroom", "bathroom", "straight ahead")
    }

    private fun addRoom(id: String, name: String, landmarks: List<String>) {
        rooms[id] = Room(id, name, landmarks)
    }

    private fun connect(from: String, to: String, direction: String) {
        connections.add(Connection(from, to, direction))
        connections.add(Connection(to, from, "backwards from $direction"))
    }

    fun getRoomByLandmark(label: String): Room? {
        return rooms.values.find { it.landmarks.contains(label) }
    }

    fun findPath(startRoomId: String, targetRoomId: String): List<Connection>? {
        // Simple BFS for pathfinding in the house graph
        val queue: Queue<List<Connection>> = LinkedList()
        val visited = mutableSetOf<String>()

        queue.add(emptyList())
        visited.add(startRoomId)

        while (queue.isNotEmpty()) {
            val path = queue.poll() ?: continue
            val currentRoomId = if (path.isEmpty()) startRoomId else path.last().to

            if (currentRoomId == targetRoomId) return path

            for (conn in connections.filter { it.from == currentRoomId }) {
                if (conn.to !in visited) {
                    visited.add(conn.to)
                    val newPath = path.toMutableList()
                    newPath.add(conn)
                    queue.add(newPath)
                }
            }
        }
        return null
    }

    fun getAllRooms(): List<Room> = rooms.values.toList()

    // Placeholder for actual image "Scanning" logic
    fun scanBlueprintImage(bitmap: Bitmap) {
        Log.d("BlueprintManager", "Scanning blueprint image of size ${bitmap.width}x${bitmap.height}")
        // Here we would use OCR (Text Recognition) to find room labels 
        // and Line Detection to find walls/doors.
    }
}

// Helper for BFS
typealias Queue<T> = java.util.Queue<T>
typealias LinkedList<T> = java.util.LinkedList<T>

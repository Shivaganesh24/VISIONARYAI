package com.programminghut.realtime_object

data class PerceptionContext(
    val userQuery: String,
    val intent: UserIntent,
    val trackedObstacles: List<NavigationMemory.TrackedObstacle>,
    val currentRoom: String? = null,
    val ocrText: String? = null,
    val isCollisionImminent: Boolean = false,
    val suggestedAction: Action = Action.MOVE_FORWARD
)

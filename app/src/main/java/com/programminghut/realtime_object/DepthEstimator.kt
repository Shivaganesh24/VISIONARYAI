package com.programminghut.realtime_object

import android.graphics.RectF

class DepthEstimator {

    private val realWorldHeightsFeet = mapOf(
        "person" to 5.65f, "car" to 4.75f, "chair" to 2.8f, "bus" to 10.5f,
        "truck" to 9.5f, "bicycle" to 3.4f, "dog" to 1.7f, "dining table" to 2.5f,
        "table" to 2.5f, "couch" to 2.8f, "sofa" to 2.8f, "bed" to 2.2f,
        "stop sign" to 8.0f, "traffic light" to 11.0f, "bench" to 2.5f,
        "potted plant" to 2.2f, "cup" to 0.4f, "bottle" to 0.8f, "tv" to 1.5f,
        "monitor" to 1.5f, "laptop" to 0.8f, "keyboard" to 0.2f, "mouse" to 0.15f,
        "backpack" to 1.5f, "bag" to 1.5f, "handbag" to 1.0f, "suitcase" to 2.0f,
        "cell phone" to 0.5f, "phone" to 0.5f, "remote" to 0.2f, "book" to 0.8f,
        "umbrella" to 3.0f, "tie" to 1.5f, "door" to 6.8f, "window" to 4.0f
    )

    private val defaultHeightFeet = 3.5f
    private val distanceHistory = mutableMapOf<String, MutableList<Float>>()
    private val WINDOW_SIZE = 4 // Smaller window for faster response in tight spaces

    fun estimateDistance(label: String, rect: RectF): Float {
        val objectHeightInImage = (rect.bottom - rect.top).coerceAtLeast(0.01f)
        val knownHeight = realWorldHeightsFeet[label] ?: defaultHeightFeet
        
        // Calibrated Focal Constant for standard mobile camera
        val K = 1.48f 
        
        val rawDistance = (knownHeight * K) / objectHeightInImage
        
        // Compensate for camera tilt
        val tiltCompensation = 0.92f 
        
        return getSmoothedDistance(label, rawDistance * tiltCompensation)
    }

    private fun getSmoothedDistance(id: String, distance: Float): Float {
        val history = distanceHistory.getOrPut(id) { mutableListOf() }
        history.add(distance)
        if (history.size > WINDOW_SIZE) history.removeAt(0)
        return history.average().toFloat()
    }
}

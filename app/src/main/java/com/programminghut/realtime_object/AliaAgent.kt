package com.programminghut.realtime_object

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Alia Agent: Intelligence layer powered by Intent Routing, Spatial Reasoning, and Groq (Llama 3.3 70B).
 */
class AliaAgent {

    private val TAG = "AliaAgent"
    private val intentClassifier = IntentClassifier()

    // Classes supported by the current underlying Object Detection model
    private val supportedModelClasses = setOf(
        "person", "bag", "backpack", "handbag", "suitcase", "laptop", "mouse", 
        "phone", "cell phone", "keyboard", "monitor", "tv", "remote", "chair", 
        "table", "dining table", "bottle", "cup", "book",
        "toilet", "boys toilet", "restroom", "washroom", "exit", "sign"
    )

    private fun getApiKey(): String {
        return System.getenv("GROQ_API_KEY") 
            ?: System.getProperty("GROQ_API_KEY") 
            ?: "YOUR_GROQ_API_KEY_HERE"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class ChatRequest(
        val model: String, 
        val messages: List<ChatMessage>,
        val temperature: Double = 0.3,
        val max_tokens: Int = 150
    )
    data class ChatMessage(val role: String, val content: String)
    data class ChatResponse(val choices: List<Choice>?, val error: ErrorInfo?)
    data class Choice(val message: ChatMessage)
    data class ErrorInfo(val message: String)

    suspend fun processQuery(
        userQuery: String,
        trackedObstacles: List<NavigationMemory.TrackedObstacle>,
        roomName: String? = null,
        ocrText: String? = null
    ): String = withContext(Dispatchers.IO) {

        val intent = intentClassifier.classify(userQuery)
        val context = PerceptionContext(
            userQuery = userQuery,
            intent = intent,
            trackedObstacles = trackedObstacles,
            currentRoom = roomName,
            ocrText = ocrText
        )

        return@withContext when (intent.category) {
            IntentCategory.OBJECT_SEARCH, IntentCategory.OBJECT_LOCATION -> handleObjectSearch(context)
            IntentCategory.NEAREST_OBJECT -> handleNearestObject(context)
            IntentCategory.OBJECT_DISTANCE -> handleObjectDistance(context)
            IntentCategory.PERSON_DESCRIPTION -> handlePersonDescription(context)
            IntentCategory.NAVIGATION_GUIDANCE -> handleNavigationGuidance(context)
            IntentCategory.GENERAL_QUESTION -> handleGeneralQuestion(context)
            IntentCategory.TEXT_READING -> handleTextReading(context)
            IntentCategory.CURRENCY_RECOGNITION -> "Please hold the currency note flat in front of the camera and ask me to identify it."
            IntentCategory.SCENE_DESCRIPTION, IntentCategory.CLARIFICATION_REQUIRED -> handleSceneDescription(context)
        }
    }

    private suspend fun handleSceneDescription(context: PerceptionContext): String {
        val promptPayload = buildRichPromptPayload(context)
        val systemPrompt = """
            You are Alia, a calm, clear, and empathetic spatial assistant for visually impaired users.
            
            GUIDELINES:
            - Answer the user's question directly in concise natural English (max 30 words).
            - Use clock positions (e.g. 'Person at 2 o'clock, 5 feet away') and relative directions ('slightly to your left').
            - NEVER invent objects or locations not present in the perception data.
            - Focus on safety hazards first, then relevant surrounding objects.
        """.trimIndent()

        val cloudResponse = callGroq(promptPayload, systemPrompt)
        return cloudResponse ?: generateLocalSceneSummary(context)
    }

    private fun handleObjectSearch(context: PerceptionContext): String {
        val target = context.intent.targetObject?.lowercase()?.trim() ?: return generateLocalSceneSummary(context)
        val isClassSupported = isSupportedTarget(target)

        val matches = context.trackedObstacles.filter { 
            val label = it.label.lowercase().trim()
            label.contains(target) || target.contains(label) || isLabelMatch(target, label)
        }

        if (matches.isNotEmpty()) {
            if (matches.size == 1) {
                val obs = matches.first()
                val clock = calculateClock(obs.normalizedCenterX)
                val dist = String.format(Locale.US, "%.0f", obs.distance)
                return "The ${obs.label} is at about $clock o'clock, approximately $dist feet away."
            }

            val sorted = matches.sortedBy { it.distance }
            val first = sorted[0]
            val second = sorted[1]
            val clock1 = calculateClock(first.normalizedCenterX)
            val dist1 = String.format(Locale.US, "%.0f", first.distance)
            val clock2 = calculateClock(second.normalizedCenterX)
            val dist2 = String.format(Locale.US, "%.0f", second.distance)

            return "I can see multiple ${first.label}s. One is at about $clock1 o'clock, $dist1 feet away, and another is at about $clock2 o'clock, $dist2 feet away."
        }

        // Check if detected OCR text contains sign/symbol target
        val ocrText = context.ocrText?.lowercase() ?: ""
        if (ocrText.isNotBlank() && (ocrText.contains(target) || isSignMatch(target, ocrText))) {
            return "I can see a sign reading '${context.ocrText}' in front of you."
        }

        if (!isClassSupported) {
            return "I am currently unable to recognize a $target with my visual model. However, I can help you locate items like your laptop, phone, bag, chair, bottle, table, or signs like 'Boys Toilet'."
        }

        return "I can't see a $target in the current camera view. Try slowly turning the camera to scan the area."
    }

    private fun handleNearestObject(context: PerceptionContext): String {
        if (context.trackedObstacles.isEmpty()) {
            return "I don't detect any objects close to you right now. The path ahead appears clear."
        }
        val nearest = context.trackedObstacles.minByOrNull { it.distance }!!
        val clock = calculateClock(nearest.normalizedCenterX)
        val dist = String.format(Locale.US, "%.0f", nearest.distance)
        return "The nearest object I can identify is a ${nearest.label}, at about $clock o'clock, roughly $dist feet away."
    }

    private fun handleObjectDistance(context: PerceptionContext): String {
        val target = context.intent.targetObject?.lowercase()?.trim()
        val matches = if (target != null) {
            context.trackedObstacles.filter { 
                val label = it.label.lowercase().trim()
                label.contains(target) || target.contains(label) || isLabelMatch(target, label)
            }
        } else {
            emptyList()
        }

        val match = matches.minByOrNull { it.distance } ?: if (target == null) context.trackedObstacles.minByOrNull { it.distance } else null

        if (match == null) {
            return if (target != null && !isSupportedTarget(target)) {
                "I am currently unable to estimate distance for $target as it is not in my object detection model."
            } else if (target != null) {
                "I don't see a $target in the current camera view to measure its distance."
            } else {
                "I don't see any nearby objects to estimate distance."
            }
        }

        val clock = calculateClock(match.normalizedCenterX)
        val dist = String.format(Locale.US, "%.0f", match.distance)
        return "The ${match.label} is at about $clock o'clock, roughly $dist feet away."
    }

    private fun handlePersonDescription(context: PerceptionContext): String {
        val people = context.trackedObstacles.filter { it.label.equals("person", ignoreCase = true) }
        if (people.isEmpty()) {
            return "I don't see any person in the current camera view."
        }
        val nearest = people.minByOrNull { it.distance }!!
        val clock = calculateClock(nearest.normalizedCenterX)
        val dist = String.format(Locale.US, "%.0f", nearest.distance)
        return "There is a person at about $clock o'clock, approximately $dist feet away."
    }

    private fun handleNavigationGuidance(context: PerceptionContext): String {
        if (context.isCollisionImminent) {
            return "The path is blocked immediately ahead. Please stop."
        }
        val centerObstacle = context.trackedObstacles.find { it.normalizedCenterX in 0.35f..0.65f && it.distance < 6f }
        if (centerObstacle != null) {
            val clock = calculateClock(centerObstacle.normalizedCenterX)
            return "There is a ${centerObstacle.label} ahead at $clock o'clock. Proceed carefully."
        }
        return "The central path ahead appears clear. You can move ahead."
    }

    private fun handleGeneralQuestion(context: PerceptionContext): String {
        return "Hello, I'm Alia. I can describe your surroundings, find objects, read text, or identify Indian currency notes. What would you like to know?"
    }

    private fun handleTextReading(context: PerceptionContext): String {
        return if (!context.ocrText.isNullOrBlank()) {
            "Extracted text reads: ${context.ocrText}"
        } else {
            "Point your camera directly at the text or document and ask me to read."
        }
    }

    private fun generateLocalSceneSummary(context: PerceptionContext): String {
        val locationStr = if (!context.currentRoom.isNullOrEmpty()) "You are in the ${context.currentRoom}. " else ""
        if (context.trackedObstacles.isEmpty()) {
            return "${locationStr}The path in front of you appears open."
        }

        val sorted = context.trackedObstacles.sortedBy { it.distance }
        val nearest = sorted.first()
        val clock = calculateClock(nearest.normalizedCenterX)
        val dist = String.format(Locale.US, "%.0f", nearest.distance)

        val secondStr = if (sorted.size > 1) {
            val second = sorted[1]
            val clock2 = calculateClock(second.normalizedCenterX)
            " I also see a ${second.label} at $clock2 o'clock."
        } else ""

        return "${locationStr}The main item ahead is a ${nearest.label} at $clock o'clock, about $dist feet away.$secondStr"
    }

    private fun buildRichPromptPayload(context: PerceptionContext): String {
        val obsDesc = if (context.trackedObstacles.isEmpty()) {
            "No objects detected."
        } else {
            context.trackedObstacles.take(5).joinToString("; ") {
                val clock = calculateClock(it.normalizedCenterX)
                "${it.label} at $clock o'clock, ${String.format(Locale.US, "%.1f", it.distance)}ft"
            }
        }
        val roomStr = context.currentRoom ?: "Unknown Area"
        val ocrStr = context.ocrText ?: "None"

        return "User Query: \"${context.userQuery}\" | Location: $roomStr | Detections: $obsDesc | OCR Text: $ocrStr"
    }

    private fun calculateClock(centerX: Float): Int {
        return when {
            centerX < 0.15f -> 9
            centerX < 0.35f -> 10
            centerX < 0.45f -> 11
            centerX < 0.55f -> 12
            centerX < 0.65f -> 1
            centerX < 0.85f -> 2
            else -> 3
        }
    }

    private fun isSupportedTarget(target: String): Boolean {
        if (supportedModelClasses.contains(target)) return true
        return when (target) {
            "bag", "backpack", "handbag", "suitcase" -> true
            "table", "desk", "dining table" -> true
            "monitor", "screen", "tv" -> true
            "phone", "mobile", "cell phone" -> true
            "toilet", "boys toilet", "restroom", "washroom", "gents", "ladies", "exit", "sign", "stairs" -> true
            else -> false
        }
    }

    private fun isLabelMatch(target: String, detectedLabel: String): Boolean {
        val t = target.lowercase().trim()
        val d = detectedLabel.lowercase().trim()
        if (t == d || d.contains(t) || t.contains(d)) return true
        if ((t == "bag" || t == "backpack") && (d == "bag" || d == "backpack" || d == "handbag" || d == "suitcase")) return true
        if ((t == "table" || t == "desk") && (d == "table" || d == "dining table")) return true
        if ((t == "monitor" || t == "tv" || t == "screen") && (d == "monitor" || d == "tv")) return true
        if ((t == "phone" || t == "mobile") && (d == "phone" || d == "cell phone")) return true
        if ((t == "toilet" || t == "boys toilet" || t == "restroom" || t == "washroom" || t == "gents" || t == "ladies") && 
            (d.contains("toilet") || d.contains("restroom") || d.contains("washroom") || d.contains("gents") || d.contains("ladies") || d.contains("men") || d.contains("women"))) return true
        if (t == "exit" && d.contains("exit")) return true
        if (t == "stairs" && (d.contains("stairs") || d.contains("staircase"))) return true
        return false
    }

    private fun isSignMatch(target: String, ocrText: String): Boolean {
        val t = target.lowercase()
        val o = ocrText.lowercase()
        if (t == "toilet" || t == "boys toilet" || t == "restroom" || t == "washroom") {
            return o.contains("toilet") || o.contains("restroom") || o.contains("washroom") || 
                   o.contains("gents") || o.contains("ladies") || o.contains("men") || o.contains("women")
        }
        if (t == "exit") return o.contains("exit")
        if (t == "stairs") return o.contains("stairs") || o.contains("staircase")
        return false
    }

    suspend fun testApiConnection(): Boolean = withContext(Dispatchers.IO) {
        return@withContext callGroq("Ping", "Respond 'Online'")?.contains("Online", true) == true
    }

    private fun callGroq(prompt: String, systemPrompt: String): String? {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) return null

        val messages = listOf(ChatMessage("system", systemPrompt), ChatMessage("user", prompt))
        val requestBody = ChatRequest(model = "llama-3.3-70b-versatile", messages = messages)
        
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBody).toRequestBody(jsonMediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                if (!response.isSuccessful) {
                    Log.e(TAG, "Groq HTTP Error ${response.code}: $body")
                    return null
                }
                val chatResponse = gson.fromJson(body, ChatResponse::class.java)
                chatResponse.choices?.firstOrNull()?.message?.content?.trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq Request Failed", e)
            null
        }
    }
}

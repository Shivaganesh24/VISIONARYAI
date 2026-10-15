package com.programminghut.realtime_object

enum class IntentCategory {
    SCENE_DESCRIPTION,
    OBJECT_LOCATION,
    NEAREST_OBJECT,
    OBJECT_SEARCH,
    OBJECT_DISTANCE,
    CURRENCY_RECOGNITION,
    TEXT_READING,
    PERSON_DESCRIPTION,
    NAVIGATION_GUIDANCE,
    GENERAL_QUESTION,
    CLARIFICATION_REQUIRED
}

data class UserIntent(
    val category: IntentCategory,
    val targetObject: String? = null,
    val rawQuery: String
)

class IntentClassifier {

    private val objectSynonyms = mapOf(
        "chair" to listOf("chair", "seat", "stool", "armchair"),
        "table" to listOf("table", "desk", "dining table"),
        "bag" to listOf("bag", "backpack", "school bag", "handbag", "laptop bag", "purse", "suitcase", "satchel"),
        "wallet" to listOf("wallet", "billfold"),
        "keys" to listOf("keys", "key"),
        "shoes" to listOf("shoes", "shoe", "slippers", "slipper", "footwear"),
        "laptop" to listOf("laptop", "computer", "notebook computer"),
        "mouse" to listOf("mouse", "computer mouse"),
        "phone" to listOf("phone", "mobile", "cell phone", "smartphone", "mobile phone"),
        "charger" to listOf("charger", "phone charger", "power adapter", "adapter", "charging cable", "cable"),
        "keyboard" to listOf("keyboard"),
        "monitor" to listOf("monitor", "screen", "tv", "television"),
        "headphones" to listOf("headphones", "earphones", "headset"),
        "power bank" to listOf("power bank", "portable charger"),
        "remote" to listOf("remote", "remote control"),
        "socket" to listOf("socket", "electrical socket", "outlet", "plug point"),
        "switch" to listOf("switch", "light switch"),
        "bottle" to listOf("bottle", "water bottle", "flask", "container"),
        "cup" to listOf("cup", "glass", "mug"),
        "book" to listOf("book", "notebook"),
        "pen" to listOf("pen", "pencil"),
        "trash bin" to listOf("trash bin", "dustbin", "trash can", "garbage bin"),
        "fan" to listOf("fan", "ceiling fan"),
        "door" to listOf("door", "doorway", "exit", "gate"),
        "window" to listOf("window"),
        "wall" to listOf("wall"),
        "stairs" to listOf("stairs", "staircase", "steps"),
        "person" to listOf("person", "someone", "human", "guy", "man", "woman", "people")
    )

    fun classify(query: String): UserIntent {
        val q = query.lowercase().trim()

        if (q.isEmpty()) {
            return UserIntent(IntentCategory.GENERAL_QUESTION, null, query)
        }

        // 1. Currency Recognition
        val currencyKeywords = listOf("currency", "note", "rupee", "rupees", "money", "cash", "banknote", "denomination")
        if (currencyKeywords.any { q.contains(it) } && !q.contains("read text") && !q.contains("read this sign")) {
            return UserIntent(IntentCategory.CURRENCY_RECOGNITION, null, query)
        }

        // 2. Text Reading / OCR
        val textKeywords = listOf("read", "ocr", "written", "text", "board", "sign", "label", "document")
        if (textKeywords.any { q.contains(it) } && !q.contains("note am i holding") && !q.contains("which note")) {
            return UserIntent(IntentCategory.TEXT_READING, null, query)
        }

        // 3. Nearest Object
        val nearestKeywords = listOf("nearest", "closest", "what is near", "what is close")
        if (nearestKeywords.any { q.contains(it) }) {
            return UserIntent(IntentCategory.NEAREST_OBJECT, null, query)
        }

        // 4. Navigation Guidance / Path Status
        val navKeywords = listOf("is it clear", "path clear", "can i go", "can i move", "move ahead", "safe to walk")
        if (navKeywords.any { q.contains(it) }) {
            return UserIntent(IntentCategory.NAVIGATION_GUIDANCE, null, query)
        }

        // 5. Person Description
        val personKeywords = listOf("who is in front", "is there a person", "do you see anyone", "who is there")
        if (personKeywords.any { q.contains(it) }) {
            return UserIntent(IntentCategory.PERSON_DESCRIPTION, "person", query)
        }

        // 6. Object Distance
        val distanceKeywords = listOf("how far", "distance", "how many feet", "feet away")
        if (distanceKeywords.any { q.contains(it) }) {
            val target = extractTargetObject(q)
            return UserIntent(IntentCategory.OBJECT_DISTANCE, target, query)
        }

        // 7. Object Search / Location
        val locationKeywords = listOf("where is", "where did", "find the", "where's", "is there a", "can you see a")
        if (locationKeywords.any { q.contains(it) }) {
            val target = extractTargetObject(q)
            return UserIntent(IntentCategory.OBJECT_SEARCH, target, query)
        }

        // 8. Scene Description / Overall Scenario
        val sceneKeywords = listOf("scenario", "around me", "what is happening", "describe everything", "what is in this room", "what do you see", "look around", "describe scene")
        if (sceneKeywords.any { q.contains(it) }) {
            return UserIntent(IntentCategory.SCENE_DESCRIPTION, null, query)
        }

        // 9. General Question or Clarification
        val generalKeywords = listOf("hello", "hi", "hey", "who are you", "help", "what can you do")
        if (generalKeywords.any { q.contains(it) }) {
            return UserIntent(IntentCategory.GENERAL_QUESTION, null, query)
        }

        // Fallback: Check if query contains any known object name
        val implicitTarget = extractTargetObject(q)
        return if (implicitTarget != null) {
            UserIntent(IntentCategory.OBJECT_LOCATION, implicitTarget, query)
        } else {
            UserIntent(IntentCategory.SCENE_DESCRIPTION, null, query)
        }
    }

    private fun extractTargetObject(query: String): String? {
        val q = query.lowercase()
        for ((standardLabel, synonyms) in objectSynonyms) {
            for (synonym in synonyms) {
                val regex = Regex("\\b${Regex.escape(synonym.lowercase())}\\b")
                if (regex.containsMatchIn(q)) {
                    return standardLabel
                }
            }
        }
        return null
    }
}

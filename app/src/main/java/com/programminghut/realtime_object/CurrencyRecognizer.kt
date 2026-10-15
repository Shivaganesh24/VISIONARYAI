package com.programminghut.realtime_object

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class CurrencyRecognizer {

    enum class Denomination(val value: Int, val spokenName: String) {
        TEN(10, "ten-rupee"),
        TWENTY(20, "twenty-rupee"),
        FIFTY(50, "fifty-rupee"),
        ONE_HUNDRED(100, "one-hundred-rupee"),
        TWO_HUNDRED(200, "two-hundred-rupee"),
        FIVE_HUNDRED(500, "five-hundred-rupee")
    }

    data class RecognitionResult(
        val denomination: Denomination?,
        val confidence: Float,
        val spokenMessage: String
    )

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    interface CurrencyCallback {
        fun onResult(result: RecognitionResult)
    }

    fun identifyCurrency(bitmap: Bitmap, callback: CurrencyCallback) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text.lowercase()
                val detectedDenom = parseDenominationFromText(fullText)
                val colorMatch = analyzeDominantColor(bitmap)

                if (detectedDenom != null) {
                    val isColorMatching = (colorMatch == detectedDenom)
                    val confidence = if (isColorMatching) 0.90f else 0.75f

                    if (confidence >= 0.80f) {
                        callback.onResult(
                            RecognitionResult(
                                denomination = detectedDenom,
                                confidence = confidence,
                                spokenMessage = "This appears to be a ${detectedDenom.spokenName} note."
                            )
                        )
                    } else {
                        callback.onResult(
                            RecognitionResult(
                                denomination = detectedDenom,
                                confidence = confidence,
                                spokenMessage = "It looks like a ${detectedDenom.spokenName} note, but I'm not confident enough to identify it reliably. Please reposition the note under good light and try again."
                            )
                        )
                    }
                } else if (colorMatch != null) {
                    callback.onResult(
                        RecognitionResult(
                            denomination = colorMatch,
                            confidence = 0.65f,
                            spokenMessage = "It looks like a ${colorMatch.spokenName} note, but I'm not confident enough to identify it reliably. Please reposition the note and try again."
                        )
                    )
                } else {
                    callback.onResult(
                        RecognitionResult(
                            denomination = null,
                            confidence = 0f,
                            spokenMessage = "I couldn't clearly identify an Indian currency note. Please hold the banknote flat in front of the camera under good light and try again."
                        )
                    )
                }
            }
            .addOnFailureListener { e ->
                callback.onResult(
                    RecognitionResult(
                        denomination = null,
                        confidence = 0f,
                        spokenMessage = "Sorry, I had trouble processing the currency image."
                    )
                )
            }
    }

    private fun parseDenominationFromText(text: String): Denomination? {
        val containsRBI = text.contains("reserve") || text.contains("bank") || text.contains("india") || text.contains("rbi") || text.contains("रुपये") || text.contains("₹")

        val nums = Regex("\\b(10|20|50|100|200|500)\\b").findAll(text).map { it.value }.toList()
        if (nums.contains("500")) return Denomination.FIVE_HUNDRED
        if (nums.contains("200")) return Denomination.TWO_HUNDRED
        if (nums.contains("100")) return Denomination.ONE_HUNDRED
        if (nums.contains("50")) return Denomination.FIFTY
        if (nums.contains("20")) return Denomination.TWENTY
        if (nums.contains("10")) return Denomination.TEN

        if (containsRBI) {
            if (text.contains("five hundred") || text.contains("500")) return Denomination.FIVE_HUNDRED
            if (text.contains("two hundred") || text.contains("200")) return Denomination.TWO_HUNDRED
            if (text.contains("one hundred") || text.contains("100")) return Denomination.ONE_HUNDRED
            if (text.contains("fifty") || text.contains("50")) return Denomination.FIFTY
            if (text.contains("twenty") || text.contains("20")) return Denomination.TWENTY
            if (text.contains("ten") || text.contains("10")) return Denomination.TEN
        }

        return null
    }

    private fun analyzeDominantColor(bitmap: Bitmap): Denomination? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null

        var hSum = 0f; var sSum = 0f; var vSum = 0f
        var count = 0
        val stepX = (width / 20).coerceAtLeast(1)
        val stepY = (height / 20).coerceAtLeast(1)

        val hsv = FloatArray(3)
        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                val pixel = bitmap.getPixel(x, y)
                Color.colorToHSV(pixel, hsv)
                hSum += hsv[0]
                sSum += hsv[1]
                vSum += hsv[2]
                count++
            }
        }

        if (count == 0) return null
        val avgH = hSum / count
        val avgS = sSum / count
        val avgV = vSum / count

        return when {
            avgS < 0.25f && avgV in 0.3f..0.85f -> Denomination.FIVE_HUNDRED
            avgH in 40f..65f && avgS > 0.4f -> Denomination.TWO_HUNDRED
            avgH in 230f..295f && avgS > 0.2f -> Denomination.ONE_HUNDRED
            avgH in 170f..225f && avgS > 0.3f -> Denomination.FIFTY
            avgH in 65f..115f && avgS > 0.3f -> Denomination.TWENTY
            avgH in 10f..38f && avgS > 0.3f -> Denomination.TEN
            else -> null
        }
    }
}

package com.programminghut.realtime_object

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class OcrManager {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class DetectedSign(
        val text: String,
        val rect: RectF,
        val confidence: Float = 0.9f
    )

    interface OcrCallback {
        fun onTextDetected(text: String)
        fun onError(e: Exception)
    }

    interface OcrSignCallback {
        fun onSignsDetected(fullText: String, signs: List<DetectedSign>)
        fun onError(e: Exception)
    }

    fun detectText(bitmap: Bitmap, callback: OcrCallback) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val resultText = visionText.text
                if (resultText.isBlank()) {
                    callback.onTextDetected("No text detected.")
                } else {
                    callback.onTextDetected(resultText)
                }
            }
            .addOnFailureListener { e ->
                callback.onError(e)
            }
    }

    fun detectSigns(bitmap: Bitmap, callback: OcrSignCallback) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val w = bitmap.width.toFloat().coerceAtLeast(1f)
        val h = bitmap.height.toFloat().coerceAtLeast(1f)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                val signList = mutableListOf<DetectedSign>()

                for (block in visionText.textBlocks) {
                    val rawText = block.text.trim()
                    if (rawText.length < 2) continue

                    val box = block.boundingBox
                    val rect = if (box != null) {
                        RectF().apply {
                            left = (box.left.toFloat() / w).coerceIn(0f, 1f)
                            top = (box.top.toFloat() / h).coerceIn(0f, 1f)
                            right = (box.right.toFloat() / w).coerceIn(0f, 1f)
                            bottom = (box.bottom.toFloat() / h).coerceIn(0f, 1f)
                        }
                    } else {
                        RectF().apply { left = 0.3f; top = 0.2f; right = 0.7f; bottom = 0.4f }
                    }

                    signList.add(DetectedSign(rawText, rect))
                }

                callback.onSignsDetected(fullText, signList)
            }
            .addOnFailureListener { e ->
                callback.onError(e)
            }
    }
}

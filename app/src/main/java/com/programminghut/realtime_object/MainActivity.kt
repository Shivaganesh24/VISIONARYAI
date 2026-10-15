package com.programminghut.realtime_object

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import androidx.appcompat.app.AppCompatActivity
import android.os.*
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.programminghut.realtime_object.ml.SsdMobilenetV11Metadata1
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.util.*
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.View
import android.widget.TextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener, SensorEventListener {

    private lateinit var labels: List<String>
    private lateinit var imageProcessor: ImageProcessor
    private lateinit var textureView: TextureView
    private lateinit var visionOverlay: VisionOverlayView
    private lateinit var aliaUi: View
    private lateinit var aliaBars: View
    private lateinit var tvAliaSubtitle: TextView
    private lateinit var tvAliaTranscription: TextView
    
    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private lateinit var handler: Handler
    private lateinit var cameraManager: CameraManager
    private lateinit var model: SsdMobilenetV11Metadata1

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var currentAzimuthRad = 0f
    private var currentPitchRad = 0f
    private var currentRollRad = 0f

    // VisionGuide Core
    private val memory = NavigationMemory()
    private val predictor = CollisionPredictor()
    private val depthEstimator = DepthEstimator()
    private val riskEngine = RiskAssessmentEngine()
    private val pathPlanner = SafePathPlanner()
    private val blueprintManager = BlueprintManager()
    private val roomRecognizer = RoomRecognizer(blueprintManager)
    private val sceneContext = SceneContextEngine()
    private val currencyRecognizer = CurrencyRecognizer()
    
    // Latest State for Voice Queries
    private var latestTrackedObstacles: List<NavigationMemory.TrackedObstacle> = emptyList()
    private var latestPlan: PathPlan? = null
    private var latestOcrText: String? = null
    private var lastOcrScanTime = 0L
    
    private lateinit var guidanceManager: GuidanceManager
    private lateinit var ocrManager: OcrManager
    private lateinit var aliaAgent: AliaAgent
    private var tts: TextToSpeech? = null
    
    // Voice/Interactivity
    private var speechRecognizer: SpeechRecognizer? = null
    private var currentDetectedRoom: BlueprintManager.Room? = null
    private var isProcessing = false
    private var isSpeechListening = false
    private var isAliaActive = false 
    private var forceAliaProcess = false
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkPermissions()

        tts = TextToSpeech(this, this)
        guidanceManager = GuidanceManager(tts)
        ocrManager = OcrManager()
        aliaAgent = AliaAgent()
        guidanceManager.setOnArrivalListener(object : GuidanceManager.OnArrivalListener {
            override fun onArrivedAtTarget(roomName: String) {
                // Arrival logic
            }
        })

        // Setup Sensors for Gyroscope Orientation
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        setupSpeechRecognizer()

        labels = FileUtil.loadLabels(this, "labels.txt")
        imageProcessor = ImageProcessor.Builder().add(ResizeOp(300, 300, ResizeOp.ResizeMethod.BILINEAR)).build()
        model = SsdMobilenetV11Metadata1.newInstance(this)
        
        val handlerThread = HandlerThread("visionThread"); handlerThread.start()
        handler = Handler(handlerThread.looper)

        visionOverlay = findViewById(R.id.visionOverlay)
        textureView = findViewById(R.id.textureView)
        aliaUi = findViewById(R.id.aliaUi)
        aliaBars = findViewById(R.id.aliaBars)
        tvAliaSubtitle = findViewById(R.id.tvAliaSubtitle)
        tvAliaTranscription = findViewById(R.id.tvAliaTranscription)

        findViewById<FloatingActionButton>(R.id.btnAliaTrigger).setOnClickListener {
            activateAlia(true)
        }

        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) { openCamera() }
            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {}
            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean = false

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                if (isProcessing) return
                
                val currentBitmap = textureView.bitmap ?: return
                isProcessing = true
                
                handler.post {
                    try {
                        val isBlocked = checkCameraBlockage(currentBitmap)

                        var image = TensorImage.fromBitmap(currentBitmap)
                        image = imageProcessor.process(image)

                        val outputs = model.process(image)
                        val locations = outputs.locationsAsTensorBuffer.floatArray
                        val classes = outputs.classesAsTensorBuffer.floatArray
                        val scores = outputs.scoresAsTensorBuffer.floatArray

                        val candidateList = mutableListOf<DetectionCandidate>()
                        val hazardLabels = setOf("person", "car", "bus", "truck", "motorcycle", "bicycle", "chair", "dining table", "table", "toilet")

                        for (i in scores.indices) {
                            if (scores[i] >= 0.38f) {
                                var rawLabel = labels[classes[i].toInt()]
                                if (rawLabel == "???") rawLabel = "Obstacle"
                                
                                val normalized = normalizeLabel(rawLabel)
                                if (isExcludedClass(rawLabel) || isExcludedClass(normalized)) continue

                                val rect = RectF(locations[i * 4 + 1], locations[i * 4], locations[i * 4 + 3], locations[i * 4 + 2])
                                candidateList.add(DetectionCandidate(rawLabel, normalized, rect, scores[i]))
                            }
                        }

                        val nmsFiltered = applyNMS(candidateList, iouThreshold = 0.42f)
                        val instantaneousObstacles = mutableListOf<VisionObstacle>()

                        for (candidate in nmsFiltered) {
                            val displayLabel = candidate.normalizedLabel
                            val distance = depthEstimator.estimateDistance(displayLabel, candidate.rect)
                            val isHighRisk = (hazardLabels.contains(candidate.rawLabel) || hazardLabels.contains(displayLabel)) && distance < 4.0f || distance < 3.5f
                            
                            if (isHighRisk) {
                                val riskScore = riskEngine.assessRisk(displayLabel, candidate.rect, distance)
                                instantaneousObstacles.add(VisionObstacle(displayLabel, candidate.rect, distance, riskScore + 100f)) 
                            } else if (distance < 14.0f) {
                                val riskScore = riskEngine.assessRisk(displayLabel, candidate.rect, distance)
                                instantaneousObstacles.add(VisionObstacle(displayLabel, candidate.rect, distance, riskScore))
                            }
                        }

                        // Periodic Sign/Symbol OCR detection (every ~1.2 seconds)
                        val now = System.currentTimeMillis()
                        if (now - lastOcrScanTime > 1200L) {
                            lastOcrScanTime = now
                            ocrManager.detectSigns(currentBitmap, object : OcrManager.OcrSignCallback {
                                override fun onSignsDetected(fullText: String, signs: List<OcrManager.DetectedSign>) {
                                    if (fullText.isNotBlank() && fullText != "No text detected.") {
                                        latestOcrText = fullText
                                        for (sign in signs) {
                                            val signLabel = sign.text.trim()
                                            if (signLabel.length in 2..35 && !isExcludedClass(signLabel)) {
                                                val h = (sign.rect.bottom - sign.rect.top).coerceAtLeast(0.04f)
                                                val estDist = ((0.8f * 1.48f) / h).coerceIn(2.0f, 15.0f)
                                                val cleanName = if (signLabel.lowercase().contains("sign")) signLabel.lowercase() else "${signLabel.lowercase()} sign"
                                                instantaneousObstacles.add(VisionObstacle(cleanName, sign.rect, estDist, 10f))
                                            }
                                        }
                                    }
                                }
                                override fun onError(e: Exception) {}
                            })
                        }

                        val trackedObstacles = memory.update(instantaneousObstacles, currentAzimuthRad, currentPitchRad, currentRollRad)
                        latestTrackedObstacles = trackedObstacles
                        currentDetectedRoom = roomRecognizer.processDetections(trackedObstacles)

                        val prediction = predictor.predict(trackedObstacles)
                        val plan = pathPlanner.planPath(trackedObstacles)
                        latestPlan = plan

                        val isAliaBusy = isAliaActive || (tts?.isSpeaking == true) || (aliaUi.visibility == View.VISIBLE)
                        val primaryObstacle = trackedObstacles.maxByOrNull { it.riskScore }
                        
                        guidanceManager.provideGuidance(plan, primaryObstacle, trackedObstacles, prediction, isBlocked, currentDetectedRoom, isAliaBusy)

                        runOnUiThread {
                            visionOverlay.updateOrientation(currentRollRad, currentPitchRad)
                            visionOverlay.updateData(plan, trackedObstacles)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        try {
                            if (!currentBitmap.isRecycled) currentBitmap.recycle()
                        } catch (e: Exception) {
                            // ignore bitmap recycle
                        }
                        isProcessing = false
                    }
                }
            }
        }
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
    }

    private fun setupSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isSpeechListening = true
                }
                override fun onBeginningOfSpeech() {
                    if (aliaUi.visibility == View.VISIBLE) {
                        runOnUiThread { tvAliaSubtitle.text = "Hearing..." }
                    }
                }
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isSpeechListening = false
                    if (aliaUi.visibility == View.VISIBLE) {
                        runOnUiThread { tvAliaSubtitle.text = "Processing..." }
                    }
                }
                override fun onError(error: Int) { 
                    isSpeechListening = false
                    Log.e("MainActivity", "Speech Error: $error")
                    if (isAliaActive) {
                        runOnUiThread { tvAliaSubtitle.text = "Listening..." }
                    }
                    val delay = if (error == 7 || error == 6) 1000L else 2500L
                    Handler(Looper.getMainLooper()).postDelayed({ 
                        if (!isDestroyed && !isFinishing) startListening() 
                    }, delay)
                }

                override fun onResults(results: Bundle?) {
                    isSpeechListening = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.get(0)?.lowercase()?.trim() ?: ""
                    Log.i("MainActivity", "Speech Result: '$text'")
                    
                    if (text.isEmpty()) {
                        if (isAliaActive) startListening()
                        return
                    }

                    val triggers = listOf("alia", "aliyah", "aalia", "alina", "olya", "area", "assistant", "vision", "hey alia", "hi alia", "hello alia")
                    val isTriggered = triggers.any { text.contains(it) }

                    runOnUiThread {
                        if (isAliaActive || isTriggered) {
                            if (aliaUi.visibility != View.VISIBLE) {
                                aliaUi.visibility = View.VISIBLE
                                startAliaAnimation()
                            }
                            tvAliaTranscription.text = text
                            tvAliaSubtitle.text = "Thinking..."
                        }
                    }

                    processVoiceCommand(text)
                    
                    if (!isAliaActive && !isTriggered) {
                        startListening()
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting up speech recognizer", e)
        }
        startListening()
    }

    private fun processVoiceCommand(text: String) {
        val rawText = text.lowercase().trim()
        Log.i("MainActivity", "COMMAND_FLOW: '$rawText'. Active=$isAliaActive, Force=$forceAliaProcess")
        
        val triggers = listOf("alia", "aliyah", "area", "assistant", "vision")
        val isAliaTriggered = triggers.any { rawText.contains(it) }
        
        if (isAliaTriggered || isAliaActive || forceAliaProcess) {
            if (isAliaTriggered || forceAliaProcess) {
                if (!isAliaActive) activateAlia(false)
            }
            forceAliaProcess = false 

            var command = rawText
            triggers.forEach { command = command.replace(it, "") }
            command = command.trim()

            if (command.isEmpty() && isAliaTriggered) {
                speakWithAlia("I'm here. How can I help you?", "ALIA_GREET")
                return
            }

            if (command.contains("currency") || command.contains("note") || command.contains("rupee") || command.contains("money")) {
                performCurrencyRecognition()
                return
            } else if (command.contains("read") || command.contains("ocr") || command.contains("text")) {
                performOCR()
                return
            } else if (command.contains("stop") || command.contains("dismiss") || command.contains("thank you") || command.contains("bye")) {
                speakWithAlia("You're welcome. Returning to navigation.", "ALIA_DISMISS")
                return
            } else if (command.isNotEmpty()) {
                handleAliaQuery(command)
                return
            }
        } 

        if (rawText.contains("move ahead") || rawText.contains("is it clear") || rawText.contains("can i go")) {
            latestPlan?.let { guidanceManager.confirmPathClear(it) }
        } else if (rawText.contains("read this") || rawText.contains("read text")) {
            performOCR()
        } else if (rawText.contains("which note") || rawText.contains("currency note")) {
            performCurrencyRecognition()
        }
    }

    private fun handleAliaQuery(query: String) {
        runOnUiThread { tvAliaSubtitle.text = "Thinking..." }
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val response = aliaAgent.processQuery(
                    userQuery = query,
                    trackedObstacles = latestTrackedObstacles,
                    roomName = currentDetectedRoom?.name,
                    ocrText = latestOcrText
                )
                speakWithAlia(response, "ALIA_RESP")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error processing Alia query", e)
                speakWithAlia("I had trouble answering that question. Please try asking again.", "ALIA_ERR")
            }
        }
    }

    private fun performCurrencyRecognition() {
        val bitmap = textureView.bitmap ?: return
        speakWithAlia("Checking banknote. Please hold still.", "CURRENCY_START")
        currencyRecognizer.identifyCurrency(bitmap, object : CurrencyRecognizer.CurrencyCallback {
            override fun onResult(result: CurrencyRecognizer.RecognitionResult) {
                speakWithAlia(result.spokenMessage, "CURRENCY_RES")
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        })
    }

    private fun performOCR() {
        val bitmap = textureView.bitmap ?: return
        speakWithAlia("Reading text. Please hold still.", "OCR_START")
        ocrManager.detectText(bitmap, object : OcrManager.OcrCallback {
            override fun onTextDetected(text: String) { 
                latestOcrText = text
                guidanceManager.speakOCRResult(text) 
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            override fun onError(e: Exception) { 
                speakWithAlia("Sorry, I couldn't read any text in front of the camera.", "OCR_ERROR") 
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        })
    }

    private fun checkApiStatus() {
        speakWithAlia("Checking connection. Please wait.", "API_CHECK_START")
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val working = aliaAgent.testApiConnection()
                if (working) {
                    speakWithAlia("Connection successful. Vision90 AI is online.", "API_CHECK_SUCCESS")
                } else {
                    speakWithAlia("I'm sorry, I'm having trouble connecting to the cloud server.", "API_CHECK_FAIL")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in checkApiStatus", e)
                speakWithAlia("An error occurred while checking status.", "API_CHECK_ERROR")
            }
        }
    }

    private fun activateAlia(autoListen: Boolean = true) {
        isAliaActive = true
        forceAliaProcess = true
        
        stopListeningInternal()
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 100)
        runOnUiThread {
            if (aliaUi.visibility != View.VISIBLE) {
                aliaUi.visibility = View.VISIBLE
                tvAliaSubtitle.text = "I'm listening..."
                tvAliaTranscription.text = ""
                startAliaAnimation()
            }
        }
        if (autoListen) {
            Handler(Looper.getMainLooper()).postDelayed({ startListening() }, 500)
        }
    }

    private fun startAliaAnimation() {
        if (!isAliaActive) return
        
        aliaBars.animate()
            .scaleY(1.3f)
            .scaleX(1.1f)
            .setDuration(400)
            .withEndAction {
                aliaBars.animate()
                    .scaleY(0.9f)
                    .scaleX(0.95f)
                    .setDuration(450)
                    .withEndAction { startAliaAnimation() }
                    .start()
            }.start()
    }

    private fun speakWithAlia(message: String, id: String) {
        Log.i("MainActivity", "Alia Speaking: $message")
        stopListeningInternal()
        runOnUiThread {
            tvAliaSubtitle.text = "Speaking..."
            tvAliaTranscription.text = message
        }
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, params, id)
    }

    private fun deactivateAlia() {
        isAliaActive = false
        runOnUiThread {
            aliaUi.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction { 
                    aliaUi.visibility = View.GONE
                    aliaUi.alpha = 1f
                }.start()
        }
    }

    private fun stopListeningInternal() {
        try {
            isSpeechListening = false
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping speech recognizer", e)
        }
    }

    private fun startListening() {
        if (isSpeechListening || tts?.isSpeaking == true) return 
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        
        runOnUiThread { 
            try {
                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                isSpeechListening = false
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            val bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, data.data)
            blueprintManager.scanBlueprintImage(bitmap)
            Toast.makeText(this, "Blueprint scanned.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientationAngles = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            currentAzimuthRad = orientationAngles[0]
            currentPitchRad = orientationAngles[1]
            currentRollRad = orientationAngles[2]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() { 
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (textureView.isAvailable) {
            openCamera()
        }
        startListening() 
    }

    override fun onPause() { 
        super.onPause()
        sensorManager.unregisterListener(this)
        stopListeningInternal()
        closeCamera()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { 
                    runOnUiThread { stopListeningInternal() } 
                }
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == "ALIA_DISMISS") {
                        deactivateAlia()
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isDestroyed && !isFinishing) startListening()
                    }, 400)
                }
                override fun onError(utteranceId: String?) { 
                    Log.e("MainActivity", "TTS Error: $utteranceId")
                    runOnUiThread { 
                        tvAliaSubtitle.text = "Error speaking response."
                        startListening() 
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCamera()
        model.close()
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }

    private fun closeCamera() {
        try {
            cameraCaptureSession?.close()
            cameraCaptureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e("MainActivity", "Error closing camera", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        closeCamera()
        val cameraId = cameraManager.cameraIdList.getOrNull(0) ?: return
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(p0: CameraDevice) {
                cameraDevice = p0
                val st = textureView.surfaceTexture ?: return
                val surface = Surface(st)
                val captureRequest = p0.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                captureRequest.addTarget(surface)
                p0.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { 
                        cameraCaptureSession = session
                        try {
                            session.setRepeatingRequest(captureRequest.build(), null, null) 
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error setting repeating request", e)
                        }
                    }
                    override fun onConfigureFailed(p0: CameraCaptureSession) {}
                }, handler)
            }
            override fun onDisconnected(p0: CameraDevice) {
                closeCamera()
            }
            override fun onError(p0: CameraDevice, p1: Int) {
                closeCamera()
            }
        }, handler)
    }

    private fun checkPermissions() {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 101)
    }

    private fun checkCameraBlockage(bitmap: Bitmap): Boolean {
        val step = 40; var total = 0L; var count = 0
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val p = bitmap.getPixel(x, y)
                total += (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toLong()
                count++
            }
        }
        return (if (count > 0) total / count else 255) < 15
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) checkPermissions()
    }

    private data class DetectionCandidate(
        val rawLabel: String,
        val normalizedLabel: String,
        val rect: RectF,
        val score: Float
    )

    private fun normalizeLabel(raw: String): String {
        val l = raw.lowercase().trim()
        return when {
            l in setOf("backpack", "handbag", "suitcase") -> "bag"
            l == "dining table" -> "table"
            l == "couch" -> "sofa"
            l == "tv" -> "monitor"
            l == "cell phone" -> "phone"
            else -> l
        }
    }

    private fun isExcludedClass(label: String): Boolean {
        val l = label.lowercase().trim()
        return l == "bed" || l == "sofa" || l == "couch" || l.contains("carpet") || l.contains("rug") || l.contains("mat") || l.contains("doormat")
    }

    private fun applyNMS(candidates: List<DetectionCandidate>, iouThreshold: Float = 0.42f): List<DetectionCandidate> {
        val sorted = candidates.sortedByDescending { it.score }
        val selected = mutableListOf<DetectionCandidate>()

        for (candidate in sorted) {
            var overlap = false
            for (accepted in selected) {
                if (candidate.normalizedLabel == accepted.normalizedLabel || candidate.rawLabel == accepted.rawLabel) {
                    val iou = calculateIOU(candidate.rect, accepted.rect)
                    if (iou > iouThreshold) {
                        overlap = true
                        break
                    }
                }
            }
            if (!overlap) {
                selected.add(candidate)
            }
        }
        return selected
    }

    private fun calculateIOU(r1: RectF, r2: RectF): Float {
        val interLeft = max(r1.left, r2.left)
        val interTop = max(r1.top, r2.top)
        val interRight = min(r1.right, r2.right)
        val interBottom = min(r1.bottom, r2.bottom)

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        val area1 = max(0f, r1.right - r1.left) * max(0f, r1.bottom - r1.top)
        val area2 = max(0f, r2.right - r2.left) * max(0f, r2.bottom - r2.top)
        val unionArea = area1 + area2 - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }
}

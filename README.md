# VisionGuide AI: Real-time Assistive Navigation for the Blind

VisionGuide AI is a production-grade Android application designed to act as a "Digital Eye" for visually impaired individuals. It transforms real-time camera feeds into high-precision navigation instructions using Computer Vision, Gyroscopic stabilization, and the **Alia Agent** intelligence layer.

## 🧠 Core Technologies & LLMs

This project leverages a hybrid Edge-Cloud architecture:
- **LLM Engine**: Powered by **Groq (Llama 3.3 70B)** for ultra-low latency (<500ms) scene reasoning, complex intent extraction, and spatial awareness.
- **Local Vision**: **SSD MobileNet V1 (TFLite)** running 100% on-device for instantaneous object detection (90+ classes) without internet reliance for safety.
- **OCR Engine**: **Google ML Kit** for real-time text recognition from the environment.
- **Architecture**: Modular "Sense-Think-Act" pipeline.

## 🚀 Advanced Features

- **Alia Intelligent Agent**: A proactive conversational assistant that understands spatial context. Ask: *"What's in front of me?"* or *"Where is the nearest chair?"*.
- **Direct Corridor Navigation**: Monitors the central walking path (33% of feed), ignoring irrelevant peripheral noise.
- **High-Precision Depth Estimation**: A calibrated pinhole camera model providing distance accuracy in **Feet** with temporal smoothing.
- **SafePath Collision Logic**: Automatic "STOP" triggers and haptic pulses if the central corridor is blocked.
- **Smart Uber Integration**: Voice-activated ride booking. Extract destinations naturally from commands like *"Alia, get me a taxi to the airport"*.
- **Visual Feedback**: Real-time camera processing with immediate verbal instructions and spatial audio (panning).
- **Tactile Guidance**: Translates hazard levels into specific vibration patterns using a dedicated Haptic Engine.
- **Performance Optimized**: Intelligent TTS throttling and background thread offloading for a smooth 30FPS experience.

## 🛠 Project Architecture & Functions

### 1. Perception Layer (`DepthEstimator.kt` & `OcrManager.kt`)
- **Distance Logic**: Calculates distance based on object height and camera calibration.
- **Text Recognition**: Extracts text from signs, labels, or documents using Google's ML Kit.

### 2. Decision Engine (`SafePathPlanner.kt` & `RiskAssessmentEngine.kt`)
- **Path Planning**: Searches for a safe gap wide enough for a human (2ft+).
- **Risk Assessment**: Scores hazards based on semantic priority (e.g., a "Bus" is higher risk than a "Chair").

### 3. Guidance System (`GuidanceManager.kt` & `HapticManager.kt`)
- **Tactical Guidance**: Converts path data into sharp, directional speech instructions.
- **Spatial Audio**: Uses audio panning to indicate the direction of obstacles.
- **Navigation Suppression**: Intelligently silences background navigation when the user is interacting with Alia.

### 4. Intelligence Layer (`AliaAgent.kt`)
- **Spatial Reasoning**: Converts raw object coordinates into natural "Clock positions" (e.g., *"Person at 2 o'clock"*).
- **Intent Extraction**: Uses Llama 3.3 to parse user speech into actionable triggers (Uber booking, Scene summary, OCR).

## 📖 Operational Procedure

1.  **Sense**: TFLite detects objects locally; ML Kit scans for text.
2.  **Think**: 
    - `DepthEstimator` calculates precise distance.
    - `AliaAgent` (via Groq) interprets the overall scene context and user queries.
3.  **Act**:
    - **Voice**: Speaks instructions (e.g., *"Doorway ahead, 5 feet, move slightly left"*).
    - **Haptics**: Vibrates if the user is straying toward an obstacle.
    - **UI**: Renders a glowing AR-style "Safe Corridor" (Green = Clear, Red = Blocked).

---
*Developed as a high-accuracy assistive technology solution for the visually impaired.*

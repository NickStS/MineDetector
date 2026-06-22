# MineDetector

<p align="center">
  <b>AI-assisted UAV system for detecting potential landmines and unexploded ordnance from drone imagery.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-brightgreen" alt="Android">
  <img src="https://img.shields.io/badge/Language-Kotlin-blue" alt="Kotlin">
  <img src="https://img.shields.io/badge/ML-YOLO%20%2B%20TFLite-orange" alt="YOLO + TFLite">
  <img src="https://img.shields.io/badge/Drone-DJI%20SDK-lightgrey" alt="DJI SDK">
  <img src="https://img.shields.io/badge/Status-Research%20Prototype-yellow" alt="Research Prototype">
</p>

---

## Overview

**MineDetector** is an Android-based research prototype for detecting potentially dangerous objects from UAV imagery.  
The project combines a DJI drone video pipeline, an on-device YOLO inference module, telemetry handling, map visualization, local detection history, and optional backend synchronization.

The main goal of the project is to demonstrate how drone imagery and mobile AI can support safer inspection of potentially contaminated areas without direct human contact.

> **Safety notice:** This project is not a replacement for professional demining teams. It does not confirm whether an object is dangerous. Any real-world use must be performed only by qualified specialists and according to local laws, aviation rules, and explosive ordnance safety procedures.

---

## Key Features

- Live drone camera view through DJI Mobile SDK
- On-device object detection using YOLO exported to TensorFlow Lite
- Optional ONNX inference module
- Detection overlay with bounding boxes and confidence values
- Drone telemetry panel: battery, GPS, altitude, distance, signal and flight state
- Map view with Mapbox integration
- Test mode for processing local photos and videos without a drone
- Detection gallery and media viewer
- Local data storage using Room
- Photo metadata handling with EXIF support
- Dataset collection and training helper scripts
- Optional FastAPI backend for model updates and detection upload

---

## Tech Stack

### Android App

- **Language:** Kotlin
- **Build system:** Gradle Kotlin DSL
- **Minimum SDK:** Android 7.0 / API 24
- **Target SDK:** API 34
- **Architecture:** Activity-based Android app with repositories, managers and local database layer
- **Drone SDK:** DJI Mobile SDK v4
- **ML:** TensorFlow Lite, YOLO, optional ONNX Runtime module
- **Maps:** Mapbox Maps SDK
- **Database:** Room
- **Async:** Kotlin Coroutines
- **Media:** Media3 / ExoPlayer, MediaCodec
- **Networking:** Retrofit, OkHttp, Gson
- **Background jobs:** WorkManager

### Backend

- **Framework:** FastAPI
- **Language:** Python
- **Containerization:** Docker / Docker Compose
- **Optional services:** PostgreSQL, Redis
- **Purpose:** detection upload, model metadata, model update endpoint, feedback endpoint

---

## Repository Structure

```text
MineDetector/
├── app/
│   ├── backend/                  # Optional FastAPI backend
│   ├── scripts/                  # Dataset, training and deployment helpers
│   ├── src/main/
│   │   ├── assets/               # labels.txt; model files are not included
│   │   ├── java/com/minedetector/
│   │   │   ├── data/             # Room database, entities, DAO, repositories
│   │   │   ├── dji/              # DJI connection, camera, telemetry and stream managers
│   │   │   ├── ml/               # YOLO/TFLite/ONNX detection pipeline
│   │   │   ├── network/          # API client and network models
│   │   │   ├── services/         # Sync and model update services
│   │   │   ├── ui/               # Activities, fragments and custom views
│   │   │   ├── utils/            # Helpers and constants
│   │   │   └── video/            # Video frame processing
│   │   └── res/                  # Layouts, drawables, strings and themes
│   └── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── local.properties.example
└── README.md
```

---

## What Is Not Included

The repository intentionally does **not** include:

- trained model files: `*.tflite`, `*.onnx`, `*.pt`, `*.pth`
- datasets and calibration images
- generated APK/AAB files
- local API keys
- local Android Studio configuration
- Gradle build outputs

This keeps the repository lightweight and prevents accidental publishing of private models, datasets or credentials.

---

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/YOUR_USERNAME/MineDetector.git
cd MineDetector
```

### 2. Open in Android Studio

Open the project root folder in Android Studio.

Recommended environment:

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 34
- Physical Android device for DJI testing

### 3. Create `local.properties`

Copy the example file:

```bash
cp local.properties.example local.properties
```

Then fill in your local values:

```properties
sdk.dir=/path/to/Android/Sdk

DJI_API_KEY=YOUR_DJI_API_KEY_HERE
MAPBOX_ACCESS_TOKEN=YOUR_MAPBOX_ACCESS_TOKEN_HERE
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_KEY_HERE

SERVER_URL=https://api.minedetector.example.com/
```

`local.properties` is ignored by Git and must never be committed.

### 4. Configure the Mapbox downloads token

Mapbox SDK dependencies may require a downloads token.  
Set it globally in your user Gradle config or as an environment variable:

```properties
MAPBOX_DOWNLOADS_TOKEN=YOUR_MAPBOX_DOWNLOADS_TOKEN_HERE
```

Recommended location:

```text
~/.gradle/gradle.properties
```

or environment variable:

```bash
export MAPBOX_DOWNLOADS_TOKEN=YOUR_MAPBOX_DOWNLOADS_TOKEN_HERE
```

### 5. Add a local model file

The trained model is not included in the repository.

Place your exported model locally into:

```text
app/src/main/assets/yolo_mine_detector.tflite
```

Optional ONNX model path:

```text
app/src/main/assets/yolo_mine_detector.onnx
```

The labels file should be placed here:

```text
app/src/main/assets/labels.txt
```

Example:

```text
class_1
class_2
class_3
class_4
```

---

## Build

### Linux / macOS

```bash
./gradlew assembleDebug
```

### Windows

```bat
gradlew.bat assembleDebug
```

The debug APK will be generated under:

```text
app/build/outputs/apk/debug/
```

Build outputs are ignored by Git.

---

## Running the App

### Drone Mode

1. Connect the DJI remote controller to the Android device via USB.
2. Power on the drone and controller.
3. Launch MineDetector.
4. Open the drone control screen.
5. Wait for SDK registration and drone connection.
6. Enable detection after the video stream becomes available.

### Test Mode

Test mode can be used without a drone:

1. Launch the app.
2. Open test mode.
3. Select a local photo or video.
4. Run detection.
5. Review the overlay and saved results.

---

## ML Pipeline

The repository includes helper scripts for working with the model pipeline:

```text
app/scripts/collect_dataset.py
app/scripts/train_model.py
app/scripts/deploy_model.sh
```

Typical workflow:

1. Collect images from UAV footage or test data.
2. Annotate images in YOLO format.
3. Train a YOLO model.
4. Export the model to TensorFlow Lite.
5. Place the exported model into Android assets locally.
6. Build and test on a physical device.

Model files, datasets and training outputs are ignored by Git.

---

## Optional Backend

The backend is located in:

```text
app/backend/
```

Run with Docker Compose:

```bash
cd app/backend
docker compose up --build
```

Available backend features include:

- model metadata endpoint
- model download endpoint
- detection upload endpoint
- batch detection upload
- alert creation endpoint
- feedback endpoint
- basic statistics endpoint

The backend is optional. The Android app can be used as a local prototype without deploying the backend.

---

## Security Notes

Do not commit:

```text
local.properties
.env
*.jks
*.keystore
*.apk
*.aab
*.tflite
*.onnx
*.pt
*.pth
datasets/
models/
runs/
app/build/
build/
.gradle/
.idea/
```

Before pushing, scan the repository for secrets:

```bash
grep -RniE "API_KEY|TOKEN|SECRET|PASSWORD|AIza|sk-|pk\." .
```

If a real API key was ever pushed publicly, revoke it and create a new one.

---

## Current Status

This project is a research / educational prototype.  
The core Android structure, DJI integration layer, detection pipeline, UI screens, telemetry handling, local database and optional backend are included.

The trained model and private dataset are intentionally excluded from the public repository.

---

## Disclaimer

MineDetector is intended only for research, educational and humanitarian technology demonstration purposes.  
It must not be used as the only decision-making tool in real-world explosive ordnance scenarios. Always involve certified professionals and follow official safety procedures.

---

## Author

**Nick Startsev**  
Portfolio: https://nicksts.github.io/my-resume/

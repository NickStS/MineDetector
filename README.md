# MineDetector

AI-powered Android application for detecting potential mine threats from DJI drone imagery in real time.

MineDetector combines a DJI drone video pipeline, an on-device YOLO detector, telemetry, maps, local storage and an optional backend into one research prototype for safer remote inspection of potentially dangerous areas.

<p align="center">
  <img src="https://img.shields.io/badge/Android-API%2024%2B-brightgreen" alt="Android API 24+">
  <img src="https://img.shields.io/badge/Kotlin-2.0.21-blue" alt="Kotlin">
  <img src="https://img.shields.io/badge/DJI%20Mobile%20SDK-v4-lightgrey" alt="DJI Mobile SDK v4">
  <img src="https://img.shields.io/badge/YOLO-TensorFlow%20Lite-orange" alt="YOLO TensorFlow Lite">
  <img src="https://img.shields.io/badge/License-MIT-green" alt="MIT License">
</p>

---

## Overview

MineDetector is an Android research project built around one main idea: use a drone camera stream and an on-device neural network to help detect suspicious mine-like objects from the air.

The app receives imagery from a DJI drone, processes frames through a YOLO-based detector, draws detection boxes over the video, stores results locally and can optionally sync detections with a backend server.

The project was created as a bachelor thesis / research prototype and is not intended to replace professional explosive ordnance disposal work.

> **Safety notice:** MineDetector does not confirm that an object is a mine or explosive device. It only highlights visual patterns detected by a machine learning model. Real-world inspection must be performed only by trained specialists and according to local law, aviation rules and explosive ordnance safety procedures.

---

## ✨ Features

### 🚁 DJI drone integration

- DJI Mobile SDK v4 integration.
- Drone connection and SDK registration flow.
- Live FPV video stream handling.
- Camera and media controls.
- Telemetry collection: GPS, altitude, distance, battery, gimbal pitch and connection state.
- Separate managers for connection, camera, video stream, telemetry, media and product state.

### 🧠 AI detection pipeline

- YOLO-based object detection pipeline.
- TensorFlow Lite inference on Android.
- Optional ONNX detector module.
- GPU delegate support with CPU fallback.
- Confidence filtering, class filtering and Non-Maximum Suppression.
- Simple ByteTrack-style object tracking.
- Detection overlay with bounding boxes and confidence values.
- Test mode for running detection on local photos and videos without a drone.

### 🗺️ Maps, telemetry and mission UI

- Mapbox-based map screen.
- Detection markers on the map.
- Custom telemetry panel.
- Gimbal pitch indicator.
- Detection history and statistics.
- Media gallery for drone and local files.
- Annotation screen for reviewing and labeling images.

### 💾 Local storage and sync

- Room database for detections, flight logs and annotations.
- Repository layer for local data access.
- Background synchronization using WorkManager.
- Optional backend upload for detections.
- Model update service for checking remote model metadata.

### 🐍 Optional backend

- FastAPI backend.
- Docker / Docker Compose support.
- Endpoints for detection upload, model metadata, model download, feedback and statistics.
- Separate training / deployment helper scripts.

---

## 🧭 Application workflow

1. Connect a supported DJI drone and remote controller to an Android device.
2. Launch MineDetector and wait for DJI SDK registration.
3. Open the drone control screen.
4. Start receiving the FPV video stream.
5. Run AI detection on incoming frames.
6. Review detections directly on the video overlay.
7. Save detections with metadata and telemetry.
8. View results in the gallery, database history or map screen.
9. Optionally sync results with the backend server.

---

## 📦 What is not included

This public repository intentionally does **not** include trained models, datasets or private keys.

Not included:

- `*.tflite`
- `*.onnx`
- `*.pt`
- `*.pth`
- training datasets
- calibration images
- generated APK / AAB files
- `local.properties`
- API keys and access tokens
- Android Studio local files
- Gradle build outputs

This keeps the repository clean and prevents accidental publishing of private models, datasets or credentials.

---

## ⚙️ Installation

### Requirements

- Android Studio Hedgehog or newer
- JDK 17
- Android SDK 34
- Android device with API 24+
- DJI developer account
- DJI Mobile SDK API key
- Mapbox access token
- Optional: Google Maps API key for DJI map compatibility

### Clone the repository

```bash
git clone https://github.com/NickStS/MineDetector.git
cd MineDetector
```

### Create local configuration

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

### Configure Mapbox downloads token

Mapbox SDK dependencies may require a downloads token.

Recommended location:

```text
~/.gradle/gradle.properties
```

Example:

```properties
MAPBOX_DOWNLOADS_TOKEN=YOUR_MAPBOX_DOWNLOADS_TOKEN_HERE
```

---

## 🧠 Add a local model

The trained model is not stored in this repository.

Place your exported model locally into:

```text
app/src/main/assets/yolo_mine_detector.tflite
```

Optional ONNX model:

```text
app/src/main/assets/yolo_mine_detector.onnx
```

Labels file:

```text
app/src/main/assets/labels.txt
```

Example labels:

```text
class_1
class_2
class_3
class_4
class_5
class_6
```

---

## 🔨 Build from source

### Windows

```bat
gradlew.bat assembleDebug
```

### Linux / macOS

```bash
./gradlew assembleDebug
```

The generated debug APK will appear in:

```text
app/build/outputs/apk/debug/
```

To create a release build:

```bash
./gradlew assembleRelease
```

---

## 🧪 Test mode

MineDetector can be tested without a drone.

1. Open the app.
2. Go to the test video / image screen.
3. Select a local photo or video.
4. Run detection.
5. Review the overlay, confidence values and saved results.

This mode is useful for debugging the ML pipeline before testing with real DJI hardware.

---

## 🖼️ Screenshots

Add screenshots or GIFs here after uploading them to the repository.

```text
docs/screenshots/main_menu.png
docs/screenshots/drone_control.png
docs/screenshots/detection_overlay.png
docs/screenshots/map_view.png
```

Example Markdown:

```md
<p align="center">
  <img src="docs/screenshots/drone_control.png" width="45%">
  <img src="docs/screenshots/detection_overlay.png" width="45%">
</p>
```

---

## 🗂️ Project structure

```text
MineDetector/
├── app/
│   ├── backend/                    # Optional FastAPI backend
│   ├── scripts/                    # Dataset, training and deployment helpers
│   └── src/main/
│       ├── assets/                 # labels.txt; model files are local only
│       ├── java/com/minedetector/
│       │   ├── data/               # Room DB, DAO, entities, repositories
│       │   ├── dji/                # DJI connection, camera, stream and telemetry managers
│       │   ├── ml/                 # TFLite / ONNX detection pipeline
│       │   ├── network/            # Retrofit API layer
│       │   ├── services/           # Sync and model update services
│       │   ├── ui/                 # Activities, fragments and custom views
│       │   ├── utils/              # Constants, permissions, EXIF and image helpers
│       │   └── video/              # Video decoding and frame processing
│       └── res/                    # Layouts, drawables, strings and themes
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── local.properties.example
├── LICENSE
└── README.md
```

---

## 🧱 Tech stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Platform | Android API 24+ |
| Build | Gradle Kotlin DSL |
| Drone SDK | DJI Mobile SDK v4 |
| AI inference | TensorFlow Lite |
| Optional inference | ONNX Runtime module |
| Model family | YOLO |
| Maps | Mapbox Maps SDK |
| Local database | Room |
| Async | Kotlin Coroutines |
| Background jobs | WorkManager |
| Networking | Retrofit, OkHttp, Gson |
| Media | Media3 / ExoPlayer |
| Backend | FastAPI |
| Backend runtime | Docker / Docker Compose |

---

## 🔐 Security notes

Never commit:

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

Before pushing, scan the repository:

```bash
grep -RniE "API_KEY|TOKEN|SECRET|PASSWORD|AIza|sk-|pk\." .
```

If a real key was ever pushed publicly, revoke it and create a new one.

---

## 🧪 Current status

MineDetector is a research / educational prototype.

Implemented parts include:

- Android app structure
- DJI connection layer
- video stream pipeline
- telemetry layer
- TFLite / YOLO detection pipeline
- detection overlay
- local database
- media gallery
- annotation screen
- map module
- optional backend structure

Model files and datasets are intentionally excluded from the repository.

---

## 📄 License

Released under the [MIT License](LICENSE).

---

## 👤 Author

**Nick Startsev**

- GitHub: [@NickStS](https://github.com/NickStS)
- Portfolio: [nicksts.github.io/my-resume](https://nicksts.github.io/my-resume/)

Built with Kotlin, DJI Mobile SDK, YOLO and TensorFlow Lite.

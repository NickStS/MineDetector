# MineDetector

Android prototype for detecting potential mine-like objects from DJI drone imagery using on-device computer vision.

The project combines DJI Mobile SDK, a YOLO-based detection pipeline, TensorFlow Lite inference, drone telemetry, map visualization, local storage and an optional backend for synchronization.

MineDetector was developed as a research and bachelor thesis project. The goal is to test how UAV imagery and mobile AI can be used for remote inspection of potentially dangerous areas.

## Main idea

The application receives an image or video stream from a DJI drone, processes frames locally on an Android device and highlights objects that look similar to predefined dangerous object classes.

The system is designed as a support tool for visual inspection. It does not confirm that an object is explosive or safe.

## Features

- DJI drone connection through DJI Mobile SDK v4
- live camera stream processing
- YOLO-based object detection
- TensorFlow Lite inference on Android
- optional ONNX detector module
- detection overlay with bounding boxes and confidence values
- drone telemetry display
- map view with detection markers
- local test mode for images and videos
- detection history stored locally
- Room database integration
- optional backend synchronization
- helper scripts for dataset processing, training and deployment

## Tech stack

| Part | Technology |
| --- | --- |
| App | Android |
| Language | Kotlin |
| Build system | Gradle Kotlin DSL |
| Drone SDK | DJI Mobile SDK v4 |
| Detection | YOLO |
| Inference | TensorFlow Lite |
| Optional inference | ONNX Runtime |
| Maps | Mapbox |
| Database | Room |
| Networking | Retrofit, OkHttp |
| Background jobs | WorkManager |
| Backend | FastAPI |
| Backend services | Docker, PostgreSQL, Redis |

## Repository structure

```text
MineDetector/
├── app/
│   ├── backend/                  # Optional FastAPI backend
│   ├── scripts/                  # Dataset, training and deployment scripts
│   └── src/main/
│       ├── assets/               # Labels and local model files
│       ├── java/com/minedetector/
│       │   ├── data/             # Room database, DAO, entities, repositories
│       │   ├── dji/              # DJI connection, camera, stream and telemetry logic
│       │   ├── ml/               # Detection pipeline
│       │   ├── network/          # API client and network models
│       │   ├── services/         # Sync and model update services
│       │   ├── ui/               # Activities, fragments and custom views
│       │   ├── utils/            # Utility classes
│       │   └── video/            # Video frame processing
│       └── res/                  # Android resources
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

## Setup

Clone the repository:

```bash
git clone https://github.com/NickStS/MineDetector.git
cd MineDetector
```

Open the project in Android Studio.

Recommended environment:

```text
Android Studio Hedgehog or newer
JDK 17
Android SDK 34
Android device with API 24+
```

Create a local configuration file:

```bash
cp local.properties.example local.properties
```

Example `local.properties`:

```properties
sdk.dir=/path/to/Android/Sdk

DJI_API_KEY=YOUR_DJI_API_KEY_HERE
MAPBOX_ACCESS_TOKEN=YOUR_MAPBOX_ACCESS_TOKEN_HERE
GOOGLE_MAPS_API_KEY=YOUR_GOOGLE_MAPS_KEY_HERE

SERVER_URL=https://api.example.com/
```

`local.properties` is ignored by Git and should not be committed.

## Model files

Trained model files are not included in this repository.

Place the local TensorFlow Lite model here:

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

Model files, datasets and training outputs are excluded from Git because they may be large or private.

## Build

Windows:

```bat
gradlew.bat assembleDebug
```

Linux / macOS:

```bash
./gradlew assembleDebug
```

Debug build output:

```text
app/build/outputs/apk/debug/
```

## Optional backend

The backend is located in:

```text
app/backend/
```

Run it with Docker Compose:

```bash
cd app/backend
docker compose up --build
```

The backend can be used for detection upload, model metadata, model download, feedback and statistics.

The Android app can also be tested without the backend.

## Notes

This repository does not include:

```text
local.properties
API keys
trained models
datasets
APK/AAB builds
Gradle build outputs
Android Studio local files
```

Before pushing changes, check that no private data is included:

```bash
grep -RniE "API_KEY|TOKEN|SECRET|PASSWORD|AIza|sk-|pk\." .
```

## Status

This is a research prototype. The main purpose of the project is to demonstrate the integration of UAV imagery, Android-based AI inference and detection result visualization.

The project is not intended for direct operational use without further testing, validation and professional review.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## Author

Nick Startsev

GitHub: [@NickStS](https://github.com/NickStS)  
Portfolio: [nicksts.github.io/my-resume](https://nicksts.github.io/my-resume/)

from fastapi import FastAPI, File, UploadFile, HTTPException, Depends
from fastapi.responses import FileResponse
from pydantic import BaseModel
from typing import List, Optional
import uvicorn
import hashlib
from datetime import datetime
import os

app = FastAPI(title="Mine Detector API", version="1.0.0")

class ModelInfo(BaseModel):
    version: str
    url: str
    size_bytes: int
    checksum: str
    release_date: str
    changelog: str

class DetectionUpload(BaseModel):
    latitude: float
    longitude: float
    altitude: float
    detection_type: str
    confidence: float
    timestamp: int
    device_id: str

class Alert(BaseModel):
    id: str
    latitude: float
    longitude: float
    type: str
    severity: str
    description: str
    created_at: int
    verified: bool

class Feedback(BaseModel):
    detection_id: str
    is_correct: bool
    actual_type: Optional[str] = None
    comments: Optional[str] = None

# In-memory storage (use proper database in production)
detections_db = []
alerts_db = []
models_db = {
    "1.0.0": ModelInfo(
        version="1.0.0",
        url="https://api.minedetector.example.com/models/yolo_v1.0.0.tflite",
        size_bytes=3845120,
        checksum="abc123def456",
        release_date="2024-01-01",
        changelog="Initial release"
    )
}

@app.get("/")
async def root():
    return {"message": "Mine Detector API", "version": "1.0.0"}

@app.get("/api/v1/models/latest", response_model=ModelInfo)
async def get_latest_model():
    """Get information about the latest model version"""
    latest_version = sorted(models_db.keys())[-1]
    return models_db[latest_version]

@app.get("/api/v1/models/{version}")
async def download_model(version: str):
    """Download a specific model version"""
    model_path = f"models/yolo_{version}.tflite"
    if not os.path.exists(model_path):
        raise HTTPException(status_code=404, detail="Model not found")
    return FileResponse(model_path, media_type="application/octet-stream")

@app.post("/api/v1/detections/upload")
async def upload_detection(
    photo: UploadFile = File(...),
    metadata: str = None
):
    """Upload a detection with photo"""
    # Save photo
    photo_path = f"uploads/{datetime.now().timestamp()}_{photo.filename}"
    os.makedirs("uploads", exist_ok=True)

    with open(photo_path, "wb") as f:
        f.write(await photo.read())

    return {
        "success": True,
        "detection_id": hashlib.md5(photo_path.encode()).hexdigest(),
        "message": "Detection uploaded successfully"
    }

@app.post("/api/v1/detections/batch")
async def upload_detections_batch(detections: List[DetectionUpload]):
    """Upload multiple detections at once"""
    uploaded_count = 0
    failed_count = 0

    for detection in detections:
        try:
            detections_db.append(detection.dict())
            uploaded_count += 1
        except Exception:
            failed_count += 1

    return {
        "success": True,
        "uploaded_count": uploaded_count,
        "failed_count": failed_count
    }

@app.get("/api/v1/alerts", response_model=List[Alert])
async def get_alerts(lat: float, lon: float, radius: float = 10.0):
    """Get alerts within radius (km) of location"""
    # Filter alerts by distance (simplified)
    nearby_alerts = [
        alert for alert in alerts_db
        if abs(alert["latitude"] - lat) < radius / 111 and
           abs(alert["longitude"] - lon) < radius / 111
    ]
    return nearby_alerts

@app.post("/api/v1/alerts/create", response_model=Alert)
async def create_alert(
    latitude: float,
    longitude: float,
    type: str,
    description: str,
    photo_urls: List[str] = []
):
    """Create a new alert"""
    alert = Alert(
        id=hashlib.md5(f"{latitude}{longitude}{datetime.now()}".encode()).hexdigest(),
        latitude=latitude,
        longitude=longitude,
        type=type,
        severity="high",
        description=description,
        created_at=int(datetime.now().timestamp()),
        verified=False
    )
    alerts_db.append(alert.dict())
    return alert

@app.get("/api/v1/statistics")
async def get_statistics():
    """Get overall statistics"""
    return {
        "total_detections": len(detections_db),
        "verified_mines": len([d for d in detections_db if d.get("verified", False)]),
        "false_positives": 0,
        "areas_cleared": 0,
        "active_users": 0
    }

@app.post("/api/v1/feedback")
async def submit_feedback(feedback: Feedback):
    """Submit feedback on a detection"""
    # Store feedback for model improvement
    return {
        "success": True,
        "message": "Feedback received. Thank you!"
    }

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
#!/usr/bin/env python3
"""
Training service for updating YOLO models
"""

from ultralytics import YOLO
import os
import shutil
from datetime import datetime

class TrainingService:
    def __init__(self, data_dir="datasets", models_dir="models"):
        self.data_dir = data_dir
        self.models_dir = models_dir
        os.makedirs(models_dir, exist_ok=True)

    def train_model(self, dataset_yaml, epochs=100, imgsz=640):
        """Train a new YOLO model"""
        print(f"Starting training with {dataset_yaml}")

        # Load base model
        model = YOLO('yolov8n.pt')

        # Train
        results = model.train(
            data=dataset_yaml,
            epochs=epochs,
            imgsz=imgsz,
            batch=16,
            device=0,
            project=self.models_dir,
            name=f"train_{datetime.now().strftime('%Y%m%d_%H%M%S')}",
            augment=True,
            patience=50
        )

        print("Training completed!")
        return results

    def export_model(self, model_path, version):
        """Export model to TFLite"""
        model = YOLO(model_path)

        # Export to TFLite with int8 quantization
        model.export(format='tflite', int8=True, imgsz=640)

        # Move to models directory
        tflite_path = model_path.replace('.pt', '.tflite')
        final_path = os.path.join(self.models_dir, f"yolo_{version}.tflite")
        shutil.move(tflite_path, final_path)

        print(f"Model exported to {final_path}")
        return final_path

    def calculate_checksum(self, file_path):
        """Calculate SHA-256 checksum"""
        import hashlib
        sha256 = hashlib.sha256()
        with open(file_path, 'rb') as f:
            for chunk in iter(lambda: f.read(4096), b""):
                sha256.update(chunk)
        return sha256.hexdigest()

if __name__ == "__main__":
    service = TrainingService()

    # Train new model
    results = service.train_model("datasets/mines/dataset.yaml", epochs=100)

    # Export to TFLite
    best_model = "runs/detect/train/weights/best.pt"
    version = "1.1.0"
    tflite_path = service.export_model(best_model, version)

    # Calculate checksum
    checksum = service.calculate_checksum(tflite_path)
    print(f"Checksum: {checksum}")

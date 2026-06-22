#!/usr/bin/env python3
"""
Complete training script for Mine Detection YOLO model
"""

import os
import yaml
import shutil
from pathlib import Path
from ultralytics import YOLO
from datetime import datetime
import hashlib

class MineDetectorTrainer:
    def __init__(self, data_dir="datasets/mines", models_dir="models", output_dir="runs"):
        self.data_dir = Path(data_dir)
        self.models_dir = Path(models_dir)
        self.output_dir = Path(output_dir)

        # Create directories
        self.models_dir.mkdir(parents=True, exist_ok=True)
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def prepare_dataset(self, images_dir, labels_dir):
        """Prepare dataset in YOLO format"""
        print("📁 Preparing dataset...")

        # Create dataset structure
        dataset_root = self.data_dir / "dataset"
        dataset_root.mkdir(parents=True, exist_ok=True)

        # Create train/val/test splits (80/15/5)
        splits = {
            'train': 0.80,
            'val': 0.15,
            'test': 0.05
        }

        # Copy and split data
        images = list(Path(images_dir).glob("*.jpg"))
        total = len(images)

        current_idx = 0
        for split_name, ratio in splits.items():
            split_dir = dataset_root / split_name
            (split_dir / "images").mkdir(parents=True, exist_ok=True)
            (split_dir / "labels").mkdir(parents=True, exist_ok=True)

            count = int(total * ratio)
            split_images = images[current_idx:current_idx + count]

            for img_path in split_images:
                # Copy image
                shutil.copy(img_path, split_dir / "images" / img_path.name)

                # Copy label
                label_path = Path(labels_dir) / f"{img_path.stem}.txt"
                if label_path.exists():
                    shutil.copy(label_path, split_dir / "labels" / label_path.name)

            current_idx += count
            print(f"  ✓ {split_name}: {len(split_images)} images")

        # Create dataset.yaml
        dataset_yaml = {
            'path': str(dataset_root.absolute()),
            'train': 'train/images',
            'val': 'val/images',
            'test': 'test/images',
            'nc': 4,
            'names': ['Mine Type A', 'Mine Type B', 'Mine Type C', 'Unexploded Ordnance']
        }

        yaml_path = dataset_root / "dataset.yaml"
        with open(yaml_path, 'w') as f:
            yaml.dump(dataset_yaml, f, default_flow_style=False)

        print(f"✅ Dataset prepared: {yaml_path}")
        return yaml_path

    def train(self, dataset_yaml, epochs=100, imgsz=640, batch=16, device=0):
        """Train YOLO model"""
        print(f"\n🚀 Starting training...")
        print(f"  Dataset: {dataset_yaml}")
        print(f"  Epochs: {epochs}")
        print(f"  Image size: {imgsz}")
        print(f"  Batch size: {batch}")

        # Load base model
        model = YOLO('yolov8n.pt')

        # Train
        results = model.train(
            data=str(dataset_yaml),
            epochs=epochs,
            imgsz=imgsz,
            batch=batch,
            device=device,
            project=str(self.output_dir),
            name=f"mine_detector_{datetime.now().strftime('%Y%m%d_%H%M%S')}",
            augment=True,
            patience=50,
            save=True,
            plots=True,
            verbose=True,
            # Hyperparameters optimized for mine detection
            lr0=0.01,
            lrf=0.01,
            momentum=0.937,
            weight_decay=0.0005,
            warmup_epochs=3.0,
            box=7.5,
            cls=0.5,
            dfl=1.5,
            hsv_h=0.015,
            hsv_s=0.7,
            hsv_v=0.4,
            degrees=0.0,
            translate=0.1,
            scale=0.5,
            shear=0.0,
            perspective=0.0,
            flipud=0.0,
            fliplr=0.5,
            mosaic=1.0,
            mixup=0.0
        )

        print("✅ Training completed!")
        return results

    def validate(self, model_path, dataset_yaml):
        """Validate model"""
        print(f"\n📊 Validating model...")

        model = YOLO(model_path)
        results = model.val(data=str(dataset_yaml))

        print(f"  mAP@50: {results.results_dict['metrics/mAP50(B)']:.4f}")
        print(f"  mAP@50-95: {results.results_dict['metrics/mAP50-95(B)']:.4f}")
        print(f"  Precision: {results.results_dict['metrics/precision(B)']:.4f}")
        print(f"  Recall: {results.results_dict['metrics/recall(B)']:.4f}")

        return results

    def export_tflite(self, model_path, version):
        """Export model to TFLite format"""
        print(f"\n📦 Exporting to TFLite...")

        model = YOLO(model_path)

        # Export with int8 quantization for mobile
        export_path = model.export(
            format='tflite',
            int8=True,
            imgsz=640
        )

        # Move to models directory with version
        final_path = self.models_dir / f"yolo_mine_detector_{version}.tflite"
        shutil.move(export_path, final_path)

        # Calculate checksum
        checksum = self.calculate_checksum(final_path)

        # Save model info
        model_info = {
            'version': version,
            'path': str(final_path),
            'size_bytes': final_path.stat().st_size,
            'checksum': checksum,
            'created_at': datetime.now().isoformat()
        }

        info_path = self.models_dir / f"model_info_{version}.yaml"
        with open(info_path, 'w') as f:
            yaml.dump(model_info, f)

        print(f"✅ Model exported: {final_path}")
        print(f"  Size: {final_path.stat().st_size / 1024 / 1024:.2f} MB")
        print(f"  Checksum: {checksum}")

        return final_path, checksum

    def calculate_checksum(self, file_path):
        """Calculate SHA-256 checksum"""
        sha256 = hashlib.sha256()
        with open(file_path, 'rb') as f:
            for chunk in iter(lambda: f.read(4096), b""):
                sha256.update(chunk)
        return sha256.hexdigest()

def main():
    """Main training pipeline"""
    print("=" * 60)
    print("🎯 Mine Detector - YOLO Training Pipeline")
    print("=" * 60)

    trainer = MineDetectorTrainer()

    # 1. Prepare dataset
    images_dir = "datasets/raw_images"
    labels_dir = "datasets/raw_labels"

    if not Path(images_dir).exists():
        print("❌ Images directory not found!")
        print("Please prepare your dataset first:")
        print(f"  1. Place images in: {images_dir}/")
        print(f"  2. Place labels in: {labels_dir}/")
        return

    dataset_yaml = trainer.prepare_dataset(images_dir, labels_dir)

    # 2. Train model
    results = trainer.train(
        dataset_yaml=dataset_yaml,
        epochs=100,
        imgsz=640,
        batch=16,
        device=0  # Use GPU 0, set to 'cpu' for CPU training
    )

    # 3. Find best model
    best_model = sorted(
        Path(trainer.output_dir).rglob("best.pt"),
        key=lambda p: p.stat().st_mtime
    )[-1]

    print(f"\n🏆 Best model: {best_model}")

    # 4. Validate
    val_results = trainer.validate(best_model, dataset_yaml)

    # 5. Export to TFLite
    version = input("\n📝 Enter model version (e.g., 1.0.0): ").strip()
    if not version:
        version = "1.0.0"

    tflite_path, checksum = trainer.export_tflite(best_model, version)

    print("\n" + "=" * 60)
    print("✅ Training pipeline completed!")
    print("=" * 60)
    print(f"\n📱 Deploy to Android:")
    print(f"  1. Copy {tflite_path}")
    print(f"  2. To: app/src/main/assets/yolo_mine_detector.tflite")
    print(f"  3. Update API with checksum: {checksum}")

if __name__ == "__main__":
    main()
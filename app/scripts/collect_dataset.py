#!/usr/bin/env python3
"""
Collect and organize dataset from phone/drone photos
"""

import os
import json
import shutil
from pathlib import Path
from PIL import Image
import piexif
from datetime import datetime

class DatasetCollector:
    def __init__(self, output_dir="datasets"):
        self.output_dir = Path(output_dir)
        self.raw_images_dir = self.output_dir / "raw_images"
        self.raw_labels_dir = self.output_dir / "raw_labels"
        self.metadata_file = self.output_dir / "metadata.json"

        # Create directories
        self.raw_images_dir.mkdir(parents=True, exist_ok=True)
        self.raw_labels_dir.mkdir(parents=True, exist_ok=True)

        self.metadata = self.load_metadata()

    def load_metadata(self):
        """Load existing metadata"""
        if self.metadata_file.exists():
            with open(self.metadata_file, 'r') as f:
                return json.load(f)
        return {'images': [], 'statistics': {}}

    def save_metadata(self):
        """Save metadata"""
        with open(self.metadata_file, 'w') as f:
            json.dump(self.metadata, f, indent=2)

    def collect_from_phone(self, phone_dcim_path):
        """Collect photos from phone DCIM folder"""
        print(f"📱 Collecting images from: {phone_dcim_path}")

        phone_path = Path(phone_dcim_path)
        if not phone_path.exists():
            print("❌ Phone path not found!")
            print("Connect phone via USB and enable file transfer")
            return

        # Find all JPEG images
        image_files = list(phone_path.rglob("*.jpg")) + list(phone_path.rglob("*.jpeg"))
        print(f"Found {len(image_files)} images")

        copied_count = 0
        for img_path in image_files:
            # Check if already processed
            if str(img_path) in [m['original_path'] for m in self.metadata['images']]:
                continue

            # Extract EXIF metadata
            metadata = self.extract_exif(img_path)

            # Generate new filename
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
            new_filename = f"photo_{timestamp}.jpg"
            new_path = self.raw_images_dir / new_filename

            # Copy image
            shutil.copy2(img_path, new_path)

            # Save metadata
            self.metadata['images'].append({
                'filename': new_filename,
                'original_path': str(img_path),
                'gps': metadata.get('gps'),
                'altitude': metadata.get('altitude'),
                'timestamp': metadata.get('timestamp'),
                'needs_annotation': True
            })

            copied_count += 1

            if copied_count % 10 == 0:
                print(f"  Copied {copied_count} images...")

        self.save_metadata()
        print(f"✅ Collected {copied_count} new images")
        print(f"📍 Total images in dataset: {len(self.metadata['images'])}")

    def extract_exif(self, image_path):
        """Extract EXIF metadata from image"""
        try:
            img = Image.open(image_path)
            exif_dict = piexif.load(img.info.get('exif', b''))

            metadata = {}

            # GPS data
            if piexif.GPSIFD.GPSLatitude in exif_dict['GPS']:
                lat = self.dms_to_decimal(exif_dict['GPS'][piexif.GPSIFD.GPSLatitude])
                lon = self.dms_to_decimal(exif_dict['GPS'][piexif.GPSIFD.GPSLongitude])
                metadata['gps'] = {'latitude': lat, 'longitude': lon}

            # Altitude
            if piexif.GPSIFD.GPSAltitude in exif_dict['GPS']:
                altitude = exif_dict['GPS'][piexif.GPSIFD.GPSAltitude]
                metadata['altitude'] = altitude[0] / altitude[1]

            # Timestamp
            if piexif.ImageIFD.DateTime in exif_dict['0th']:
                metadata['timestamp'] = exif_dict['0th'][piexif.ImageIFD.DateTime].decode()

            # UserComment (custom telemetry from app)
            if piexif.ExifIFD.UserComment in exif_dict['Exif']:
                user_comment = exif_dict['Exif'][piexif.ExifIFD.UserComment].decode()
                try:
                    custom_data = json.loads(user_comment)
                    metadata['telemetry'] = custom_data
                except:
                    pass

            return metadata
        except Exception as e:
            print(f"Warning: Could not extract EXIF from {image_path}: {e}")
            return {}

    def dms_to_decimal(self, dms):
        """Convert DMS (degrees, minutes, seconds) to decimal"""
        degrees = dms[0][0] / dms[0][1]
        minutes = dms[1][0] / dms[1][1]
        seconds = dms[2][0] / dms[2][1]
        return degrees + (minutes / 60.0) + (seconds / 3600.0)

    def import_annotations(self, annotations_export_path):
        """Import annotations from app export"""
        print(f"📋 Importing annotations from: {annotations_export_path}")

        annotations_path = Path(annotations_export_path)
        if not annotations_path.exists():
            print("❌ Annotations file not found!")
            return

        with open(annotations_path, 'r') as f:
            annotations_data = json.load(f)

        imported_count = 0
        for annotation in annotations_data:
            photo_filename = Path(annotation['photo_path']).name

            # Check if image exists
            img_path = self.raw_images_dir / photo_filename
            if not img_path.exists():
                print(f"⚠️  Image not found: {photo_filename}")
                continue

            # Convert to YOLO format
            yolo_annotations = self.convert_to_yolo(
                annotation['bounding_boxes'],
                img_path
            )

            # Save YOLO label file
            label_filename = photo_filename.replace('.jpg', '.txt').replace('.jpeg', '.txt')
            label_path = self.raw_labels_dir / label_filename

            with open(label_path, 'w') as f:
                f.write('\n'.join(yolo_annotations))

            # Update metadata
            for img_meta in self.metadata['images']:
                if img_meta['filename'] == photo_filename:
                    img_meta['needs_annotation'] = False
                    img_meta['annotation_count'] = len(yolo_annotations)
                    break

            imported_count += 1

        self.save_metadata()
        print(f"✅ Imported {imported_count} annotations")

    def convert_to_yolo(self, bounding_boxes, image_path):
        """Convert bounding boxes to YOLO format"""
        img = Image.open(image_path)
        img_width, img_height = img.size

        yolo_lines = []
        for bbox in bounding_boxes:
            class_id = bbox['class_id']
            x_center = (bbox['x_min'] + bbox['x_max']) / 2 / img_width
            y_center = (bbox['y_min'] + bbox['y_max']) / 2 / img_height
            width = (bbox['x_max'] - bbox['x_min']) / img_width
            height = (bbox['y_max'] - bbox['y_min']) / img_height

            yolo_lines.append(f"{class_id} {x_center:.6f} {y_center:.6f} {width:.6f} {height:.6f}")

        return yolo_lines

    def generate_statistics(self):
        """Generate dataset statistics"""
        print("\n📊 Dataset Statistics:")
        print("=" * 50)

        total_images = len(self.metadata['images'])
        annotated_images = sum(1 for img in self.metadata['images'] if not img['needs_annotation'])

        print(f"Total images: {total_images}")
        print(f"Annotated: {annotated_images} ({annotated_images/total_images*100:.1f}%)")
        print(f"Need annotation: {total_images - annotated_images}")

        # Count detections per class
        class_counts = {0: 0, 1: 0, 2: 0, 3: 0}
        for label_file in self.raw_labels_dir.glob("*.txt"):
            with open(label_file, 'r') as f:
                for line in f:
                    class_id = int(line.split()[0])
                    class_counts[class_id] += 1

        print(f"\nDetections by class:")
        classes = ['Mine Type A', 'Mine Type B', 'Mine Type C', 'UXO']
        for class_id, count in class_counts.items():
            print(f"  {classes[class_id]}: {count}")

        # GPS coverage
        images_with_gps = sum(1 for img in self.metadata['images'] if img.get('gps'))
        print(f"\nImages with GPS: {images_with_gps} ({images_with_gps/total_images*100:.1f}%)")

        print("=" * 50)

def main():
    import argparse

    parser = argparse.ArgumentParser(description='Collect dataset from phone/drone')
    parser.add_argument('phone_path', help='Path to phone DCIM/MineDetector folder')
    parser.add_argument('--annotations', help='Path to annotations export JSON')
    parser.add_argument('--output', default='datasets', help='Output directory')

    args = parser.parse_args()

    print("=" * 60)
    print("📸 Mine Detector - Dataset Collection Tool")
    print("=" * 60)

    collector = DatasetCollector(output_dir=args.output)

    # Collect images
    collector.collect_from_phone(args.phone_path)

    # Import annotations if provided
    if args.annotations:
        collector.import_annotations(args.annotations)

    # Show statistics
    collector.generate_statistics()

    print("\n✅ Collection completed!")
    print(f"\n📁 Next steps:")
    print(f"  1. Annotate remaining images using CVAT or LabelImg")
    print(f"  2. Or use app's annotation feature and export")
    print(f"  3. Run training: python scripts/train_model.py")

if __name__ == "__main__":
    main()
#!/bin/bash

# Mine Detector - Model Deployment Script
# Deploys trained TFLite model to Android app and backend API

set -e  # Exit on error

echo "========================================"
echo "🚀 Mine Detector - Model Deployment"
echo "========================================"

# Configuration
MODEL_VERSION=${1:-"1.0.0"}
MODEL_FILE="models/yolo_mine_detector_${MODEL_VERSION}.tflite"
ANDROID_ASSETS="app/src/main/assets"
API_MODELS_DIR="backend/models"
API_URL="https://api.minedetector.example.com"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Functions
check_file() {
    if [ ! -f "$1" ]; then
        echo -e "${RED}❌ Error: File not found: $1${NC}"
        exit 1
    fi
}

calculate_checksum() {
    if command -v sha256sum &> /dev/null; then
        sha256sum "$1" | awk '{print $1}'
    else
        shasum -a 256 "$1" | awk '{print $1}'
    fi
}

# Check if model exists
echo -e "\n${YELLOW}📦 Checking model file...${NC}"
check_file "$MODEL_FILE"
echo -e "${GREEN}✓ Model file found${NC}"

# Calculate checksum
echo -e "\n${YELLOW}🔐 Calculating checksum...${NC}"
CHECKSUM=$(calculate_checksum "$MODEL_FILE")
echo -e "${GREEN}✓ Checksum: $CHECKSUM${NC}"

# Get file size
FILE_SIZE=$(stat -f%z "$MODEL_FILE" 2>/dev/null || stat -c%s "$MODEL_FILE" 2>/dev/null)
FILE_SIZE_MB=$(echo "scale=2; $FILE_SIZE / 1024 / 1024" | bc)
echo -e "${GREEN}✓ File size: ${FILE_SIZE_MB} MB${NC}"

# Deploy to Android app
echo -e "\n${YELLOW}📱 Deploying to Android app...${NC}"

if [ -d "$ANDROID_ASSETS" ]; then
    # Backup old model if exists
    if [ -f "$ANDROID_ASSETS/yolo_mine_detector.tflite" ]; then
        BACKUP_FILE="$ANDROID_ASSETS/yolo_mine_detector.tflite.backup_$(date +%Y%m%d_%H%M%S)"
        echo "  Creating backup: $(basename $BACKUP_FILE)"
        cp "$ANDROID_ASSETS/yolo_mine_detector.tflite" "$BACKUP_FILE"
    fi

    # Copy new model
    cp "$MODEL_FILE" "$ANDROID_ASSETS/yolo_mine_detector.tflite"
    echo -e "${GREEN}✓ Model deployed to Android assets${NC}"

    # Update version info
    VERSION_FILE="$ANDROID_ASSETS/model_version.txt"
    echo "$MODEL_VERSION" > "$VERSION_FILE"
    echo "$CHECKSUM" >> "$VERSION_FILE"
    echo "$FILE_SIZE" >> "$VERSION_FILE"
    echo -e "${GREEN}✓ Version info updated${NC}"
else
    echo -e "${RED}⚠️  Android assets directory not found: $ANDROID_ASSETS${NC}"
fi

# Deploy to backend API
echo -e "\n${YELLOW}🌐 Deploying to backend API...${NC}"

if [ -d "$API_MODELS_DIR" ]; then
    # Copy to API models directory
    mkdir -p "$API_MODELS_DIR"
    cp "$MODEL_FILE" "$API_MODELS_DIR/"

    # Create model metadata
    cat > "$API_MODELS_DIR/model_${MODEL_VERSION}.json" << EOF
{
  "version": "$MODEL_VERSION",
  "filename": "$(basename $MODEL_FILE)",
  "size_bytes": $FILE_SIZE,
  "checksum": "$CHECKSUM",
  "release_date": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "changelog": "Model version $MODEL_VERSION",
  "url": "$API_URL/models/$(basename $MODEL_FILE)"
}
EOF

    echo -e "${GREEN}✓ Model deployed to backend${NC}"
else
    echo -e "${YELLOW}⚠️  Backend directory not found: $API_MODELS_DIR${NC}"
fi

# Update API latest version
echo -e "\n${YELLOW}🔄 Updating API latest version...${NC}"
if [ -d "$API_MODELS_DIR" ]; then
    echo "$MODEL_VERSION" > "$API_MODELS_DIR/latest_version.txt"
    echo -e "${GREEN}✓ Latest version updated${NC}"
fi

# Rebuild Android app (optional)
read -p "📱 Rebuild Android app? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo -e "\n${YELLOW}🔨 Building Android app...${NC}"
    if [ -f "gradlew" ]; then
        ./gradlew assembleDebug
        echo -e "${GREEN}✓ Build completed${NC}"

        # Copy APK to releases
        mkdir -p releases
        APK_FILE=$(find app/build/outputs/apk/debug -name "*.apk" | head -n 1)
        if [ -f "$APK_FILE" ]; then
            RELEASE_APK="releases/MineDetector_v${MODEL_VERSION}_$(date +%Y%m%d).apk"
            cp "$APK_FILE" "$RELEASE_APK"
            echo -e "${GREEN}✓ APK saved: $RELEASE_APK${NC}"
        fi
    else
        echo -e "${RED}⚠️  gradlew not found${NC}"
    fi
fi

# Upload to API server (optional)
read -p "🌐 Upload to API server? (y/n) " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo -e "\n${YELLOW}📤 Uploading to API server...${NC}"

    # Check if curl is available
    if command -v curl &> /dev/null; then
        # Upload model file
        curl -X POST "$API_URL/api/v1/models/upload" \
             -H "Content-Type: multipart/form-data" \
             -F "version=$MODEL_VERSION" \
             -F "file=@$MODEL_FILE" \
             -F "checksum=$CHECKSUM"

        echo -e "\n${GREEN}✓ Upload completed${NC}"
    else
        echo -e "${RED}⚠️  curl not available${NC}"
    fi
fi

# Generate deployment report
echo -e "\n${YELLOW}📊 Generating deployment report...${NC}"
REPORT_FILE="deployments/deployment_${MODEL_VERSION}_$(date +%Y%m%d_%H%M%S).txt"
mkdir -p deployments

cat > "$REPORT_FILE" << EOF
========================================
Mine Detector - Deployment Report
========================================

Model Version: $MODEL_VERSION
Deployment Date: $(date)
Model File: $MODEL_FILE
File Size: ${FILE_SIZE_MB} MB
Checksum: $CHECKSUM

Deployment Status:
- Android App: $([ -f "$ANDROID_ASSETS/yolo_mine_detector.tflite" ] && echo "✓ Deployed" || echo "✗ Failed")
- Backend API: $([ -f "$API_MODELS_DIR/$(basename $MODEL_FILE)" ] && echo "✓ Deployed" || echo "✗ Failed")

Next Steps:
1. Test model on device
2. Validate detection accuracy
3. Monitor performance metrics
4. Update changelog

========================================
EOF

echo -e "${GREEN}✓ Report saved: $REPORT_FILE${NC}"

# Summary
echo -e "\n========================================"
echo -e "${GREEN}✅ Deployment completed successfully!${NC}"
echo "========================================"
echo -e "\n📋 Summary:"
echo "  Model Version: $MODEL_VERSION"
echo "  File Size: ${FILE_SIZE_MB} MB"
echo "  Checksum: $CHECKSUM"
echo ""
echo "📱 Test on device:"
echo "  adb install -r $RELEASE_APK"
echo ""
echo "🌐 API endpoint:"
echo "  GET $API_URL/api/v1/models/latest"
echo ""
echo "📊 View report:"
echo "  cat $REPORT_FILE"

exit 0
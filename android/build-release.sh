#!/bin/bash
# RavenTag Release Build Script
# Builds signed release APKs and AABs for both Consumer (Verify) and Brand apps

set -e

echo "========================================"
echo "RavenTag Release Build Script"
echo "========================================"
echo ""

cd /home/ale/Projects/RavenTag/android

# Step 1: Clean previous builds
echo "[1/5] Cleaning previous builds..."
./gradlew clean

# Step 2: Build Consumer Release (Verify app)
echo "[2/5] Building RavenTag Verify (Consumer Release)..."
./gradlew assembleConsumerRelease

# Step 3: Build Brand Release (Brand Manager app)
echo "[3/5] Building RavenTag Brand Manager (Brand Release)..."
./gradlew assembleBrandRelease

# Step 4: Copy and rename APKs
echo "[4/5] Copying and renaming APKs..."
mkdir -p /tmp/raventag-release

# Consumer APK
cp app/build/outputs/apk/consumer/release/app-consumer-release.apk \
   /tmp/raventag-release/RavenTag-Verify-v1.0.0.apk

# Brand APK
cp app/build/outputs/apk/brand/release/app-brand-release.apk \
   /tmp/raventag-release/RavenTag-Brand-v1.0.0.apk

# Step 5: Copy AABs
echo "[5/5] Copying AAB files..."
cp app/build/outputs/bundle/consumerRelease/app-consumer-release.aab \
   /tmp/raventag-release/RavenTag-Verify-v1.0.0.aab

cp app/build/outputs/bundle/brandRelease/app-brand-release.aab \
   /tmp/raventag-release/RavenTag-Brand-v1.0.0.aab

# Show results
echo ""
echo "========================================"
echo "Build Complete!"
echo "========================================"
echo ""
echo "Files created in /tmp/raventag-release/:"
ls -lh /tmp/raventag-release/
echo ""
echo "SHA-256 fingerprints:"
for file in /tmp/raventag-release/*.apk; do
    echo "$(basename $file):"
    sha256sum "$file" | cut -d' ' -f1
    echo ""
done

echo "========================================"
echo "Next Steps:"
echo "========================================"
echo "1. Upload APKs to GitHub Releases:"
echo "   cd /tmp/raventag-release"
echo "   gh release upload v1.0.0 *.apk *.aab"
echo ""
echo "2. Or manually upload via GitHub web interface"
echo ""

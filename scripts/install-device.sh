#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
DEVICE_SERIAL="ce09171998abec250b7e"
APK_PATH="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"

cd "$PROJECT_ROOT"

echo "=== Building Edge Debug APK ==="
./gradlew assembleDebug

echo "=== Verifying Connected Device ==="
adb devices -l

echo "=== Installing Edge APK to $DEVICE_SERIAL ==="
adb -s "$DEVICE_SERIAL" install -r "$APK_PATH"

echo "=== Launching MainActivity ==="
adb -s "$DEVICE_SERIAL" shell am start -n com.edge.reception/.MainActivity

echo "=== Deployment Successful ==="

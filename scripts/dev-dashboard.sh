#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BACKEND_DIR="$PROJECT_ROOT/backend"

echo "=== Starting Edge Founder Dashboard & Control Plane ==="
DEVICE_SERIAL="ce09171998abec250b7e"

# Configure ADB reverse for physical phone
if command -v adb >/dev/null 2>&1; then
    echo "Configuring adb reverse tcp:8000 tcp:8000 for device $DEVICE_SERIAL..."
    adb -s "$DEVICE_SERIAL" reverse tcp:8000 tcp:8000 || echo "Warning: Device not connected or unauthorized; continuing without reverse."
fi

cd "$BACKEND_DIR"

# Check Python environment
if [ ! -d "venv" ]; then
    echo "Creating Python virtual environment in $BACKEND_DIR/venv..."
    python3 -m venv venv
    ./venv/bin/pip install --upgrade pip
    ./venv/bin/pip install -r requirements.txt
fi

echo "Launching FastAPI Control Plane on http://127.0.0.1:8000..."
./venv/bin/uvicorn app.main:app --host 127.0.0.1 --port 8000

#!/usr/bin/env bash
set -e

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
cd "$DIR"

echo "========================================================"
echo "    Edge AI Local Voice Lab (localhost:3000)"
echo "    On-Device AI Receptionist • Android 16 USB Relay"
echo "========================================================"

# 1. Check Node & Python
if ! command -v node >/dev/null 2>&1; then
    echo "[-] Error: node is required but not installed."
    exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
    echo "[-] Error: python3 is required but not installed."
    exit 1
fi

# 2. Check Android Device via ADB
echo "[1/5] Checking connected Android handset..."
if command -v adb >/dev/null 2>&1; then
    DEVICE_SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
    if [ -n "$DEVICE_SERIAL" ]; then
        DEVICE_MODEL=$(adb -s "$DEVICE_SERIAL" shell getprop ro.product.model 2>/dev/null || echo "Android Device")
        echo " [+] Detected Android device: $DEVICE_MODEL ($DEVICE_SERIAL)"
        echo "[2/5] Setting up ADB reverse tunnel (tcp:8000 -> tcp:8000)..."
        adb -s "$DEVICE_SERIAL" reverse tcp:8000 tcp:8000
        echo " [+] Reverse port forwarding active: phone can connect to Mac backend via 127.0.0.1:8000"
    else
        echo " [!] Warning: No authorized physical Android phone detected via ADB."
        echo "     Connect your phone via USB and enable USB Debugging."
    fi
else
    echo " [!] Warning: adb command not found in PATH."
fi

# 3. Check or Start Backend (Port 8000)
echo "[3/5] Checking Edge FastAPI Backend on port 8000..."
if lsof -i :8000 >/dev/null 2>&1; then
    echo " [+] Backend process already running on port 8000 (reusing healthy instance)."
else
    echo " [*] Starting FastAPI backend on http://127.0.0.1:8000..."
    python3 -m uvicorn app.main:app --app-dir backend --host 127.0.0.1 --port 8000 >/dev/null 2>&1 &
    sleep 2
    if lsof -i :8000 >/dev/null 2>&1; then
        echo " [+] Backend started successfully."
    else
        echo "[-] Error: Failed to start backend on port 8000."
        exit 1
    fi
fi

# 4. Check Backend Health
HEALTH=$(curl -s http://127.0.0.1:8000/v1/health || echo "{}")
if echo "$HEALTH" | grep -q '"status":"ok"'; then
    echo " [+] Backend health verified: 200 OK"
else
    echo " [!] Warning: Backend health check did not return status ok: $HEALTH"
fi

# 5. Check Port 3000 & Start Frontend
echo "[4/5] Checking port 3000 for Frontend..."
if lsof -i :3000 >/dev/null 2>&1; then
    echo "[-] Error: Port 3000 is already in use by another process:"
    lsof -i :3000
    echo "    Please stop the conflicting process or verify it is the Edge Voice Lab."
    exit 1
fi

echo "[5/5] Starting Edge Voice Lab Frontend on http://localhost:3000..."
cd "$DIR/frontend"

# Build if dist missing
if [ ! -d "dist" ]; then
    echo " [*] Building frontend production bundle..."
    npm run build
fi

# Start Vite dev server on port 3000 with strictPort
echo "========================================================"
echo "    Edge Voice Lab is starting on:"
echo "    -> http://localhost:3000"
echo "========================================================"

# Try opening browser if on macOS
if command -v open >/dev/null 2>&1; then
    open "http://localhost:3000" || true
fi

exec npx vite --host 127.0.0.1 --port 3000 --strictPort

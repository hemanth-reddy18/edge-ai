#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "=== Running Edge Local Models & Multi-language Verification ==="
cd "$PROJECT_ROOT"

# Run Android Unit Tests
./gradlew testDebugUnitTest --info

# Run Backend Python Tests
cd "$PROJECT_ROOT/backend"
if [ -d "venv" ]; then
    ./venv/bin/pytest tests/
else
    echo "Running pytest with system python..."
    python3 -m pytest tests/
fi

echo "=== All Model & Language Verification Tests Passed ==="

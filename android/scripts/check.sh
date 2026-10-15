#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 scripts/verify-apk.py

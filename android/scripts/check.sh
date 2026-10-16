#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
offline_view_dir="${1:?Usage: scripts/check.sh /absolute/pinned-offline-wallet-view-build}"
python3 -m unittest discover -s scripts -p 'test_apk_launcher.py'
python3 -m unittest discover -s scripts -p 'test_release_checks.py'
./gradlew -PofflineWalletViewDir="$offline_view_dir" testDebugUnitTest lintDebug assembleDebug
python3 scripts/verify-apk.py --offline-wallet-view-dir "$offline_view_dir"

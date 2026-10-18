#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
mobile_core_dir="${1:?Usage: scripts/check.sh /absolute/mobile-core-build /absolute/offline-wallet-view-build}"
offline_view_dir="${2:?A pinned offline wallet reader build is required}"
python3 -m unittest discover -s scripts -p 'test_apk_launcher.py'
python3 -m unittest discover -s scripts -p 'test_release_checks.py'
python3 -m unittest discover -s scripts -p 'test_localization.py'
./gradlew -PcoreMobileDir="$mobile_core_dir" -PofflineWalletViewDir="$offline_view_dir" testDebugUnitTest lintDebug assembleDebug
python3 scripts/verify-apk.py --core-mobile-dir "$mobile_core_dir" --offline-wallet-view-dir "$offline_view_dir"

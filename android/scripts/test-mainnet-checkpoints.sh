#!/usr/bin/env bash
# Physical mainnet checkpoint acceptance. Never creates or loads wallets.
# Stop the existing app node gracefully before running: APK/instrumentation replaces its process.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
device_serial="${1:?Usage: test-mainnet-checkpoints.sh PHYSICAL_ADB_SERIAL [PREVIOUS_CHAIN_HEIGHT]}"
previous_height="${2:-0}"
[[ "$device_serial" != emulator-* ]] || { printf 'A physical ARM64 device is required.\n' >&2; exit 1; }
[[ "$previous_height" =~ ^[0-9]+$ ]] || { printf 'Previous chain height must be a nonnegative integer.\n' >&2; exit 1; }
adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then adb_bin="$(command -v adb)"; fi
[[ "$("$adb_bin" -s "$device_serial" get-state)" = device ]]
[[ "$("$adb_bin" -s "$device_serial" shell uname -m | tr -d '\r')" = aarch64 ]]
# This only checks ownership; it never stops or kills the existing process.
assert_mainnet_stopped() {
  "$adb_bin" -s "$device_serial" shell run-as com.blocknet.wallet sh -c \
    "'if [ -r no_backup/blocknet/mainnet/core.pid ]; then p=\$(cat no_backup/blocknet/mainnet/core.pid); if kill -0 \"\$p\" 2>/dev/null; then echo Existing_mainnet_node_must_be_stopped_gracefully_before_install >&2; exit 42; fi; fi'"
}
assert_mainnet_stopped
./gradlew -PcheckpointIntegration assembleDebug assembleDebugAndroidTest
assert_mainnet_stopped
"$adb_bin" -s "$device_serial" install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$device_serial" install --user 0 -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sdk_level="$("$adb_bin" -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if ((sdk_level>=33)); then "$adb_bin" -s "$device_serial" shell pm grant --user 0 com.blocknet.wallet android.permission.POST_NOTIFICATIONS; fi
device_result="$("$adb_bin" -s "$device_serial" shell am instrument --user 0 -w -e previousHeight "$previous_height" com.blocknet.wallet.test/com.blocknet.wallet.WalletCheckpointInstrumentation)"
printf '%s\n' "$device_result"
[[ "$device_result" == *'OK ('* && "$device_result" != *'FAIL'* ]]
printf 'Mainnet core is stopped. Relaunch the wallet normally and start its mainnet node.\n'

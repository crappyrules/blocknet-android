#!/usr/bin/env bash
# Real ARM64 app/service integration. Refuses existing testnet data; never broadcasts.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
device_serial="${1:?Usage: test-device.sh PHYSICAL_ADB_SERIAL}"
[[ "$device_serial" != emulator-* ]] || { printf 'A physical ARM64 device serial is required.\n' >&2; exit 1; }
adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then adb_bin="$(command -v adb)"; fi
[[ "$("$adb_bin" -s "$device_serial" get-state)" = device ]]
[[ "$("$adb_bin" -s "$device_serial" shell uname -m | tr -d '\r')" = aarch64 ]]
./gradlew -PdeviceIntegration assembleDebug assembleDebugAndroidTest
"$adb_bin" -s "$device_serial" install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$device_serial" install --user 0 -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
sdk_level="$("$adb_bin" -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
if ((sdk_level>=33)); then "$adb_bin" -s "$device_serial" shell pm grant --user 0 com.blocknet.wallet android.permission.POST_NOTIFICATIONS; fi
device_result="$("$adb_bin" -s "$device_serial" shell am instrument --user 0 -w com.blocknet.wallet.test/com.blocknet.wallet.WalletDeviceInstrumentation)"
printf '%s\n' "$device_result"
[[ "$device_result" == *'OK ('* && "$device_result" != *'FAIL'* ]]

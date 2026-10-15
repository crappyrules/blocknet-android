#!/usr/bin/env bash
# Run only against an isolated testnet core with no funds and no loaded wallet.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
cookie_file="${1:?Usage: test-android-transport.sh /readable/testnet/api.cookie [port] [ADB_SERIAL]}"
core_port="${2:-18392}"
[[ "$core_port" =~ ^[0-9]+$ ]] && ((core_port>0 && core_port<65536))
[[ -r "$cookie_file" ]] || { printf 'Cookie file must be readable.\n' >&2; exit 1; }
adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then adb_bin="$(command -v adb)"; fi
target_serial="${3:-${ANDROID_SERIAL:-}}"
if [[ -z "$target_serial" ]]; then
  mapfile -t available_devices < <("$adb_bin" devices | awk '$2 == "device" {print $1}')
  ((${#available_devices[@]}==1)) || { printf 'Specify ADB_SERIAL or ANDROID_SERIAL when exactly one authorized target is not available.\n' >&2; exit 1; }
  target_serial="${available_devices[0]}"
fi
[[ "$("$adb_bin" -s "$target_serial" get-state)" = device ]]
./gradlew -PapiIntegration assembleDebug assembleDebugAndroidTest
"$adb_bin" -s "$target_serial" install --abi arm64-v8a -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$target_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$adb_bin" -s "$target_serial" reverse "tcp:$core_port" "tcp:$core_port"
trap '"$adb_bin" -s "$target_serial" shell run-as com.blocknet.wallet rm -f files/integration-cookie; "$adb_bin" -s "$target_serial" reverse --remove "tcp:$core_port"' EXIT
cat -- "$cookie_file" | "$adb_bin" -s "$target_serial" shell run-as com.blocknet.wallet sh -c '"cat > files/integration-cookie"'
test_result="$("$adb_bin" -s "$target_serial" shell am instrument -w -e port "$core_port" com.blocknet.wallet.test/com.blocknet.wallet.WalletApiInstrumentation)"
printf '%s\n' "$test_result"
[[ "$test_result" == *'OK ('* && "$test_result" != *'FAIL'* ]]

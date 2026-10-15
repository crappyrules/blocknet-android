#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then adb_bin="$(command -v adb)"; fi
emulator_serial="${1:-${ANDROID_SERIAL:-}}"
if [[ -z "$emulator_serial" ]]; then
  mapfile -t available_emulators < <("$adb_bin" devices | awk '$1 ~ /^emulator-/ && $2 == "device" {print $1}')
  ((${#available_emulators[@]}==1)) || { printf 'Specify exactly one emulator serial: smoke-emulator.sh emulator-PORT\n' >&2; exit 1; }
  emulator_serial="${available_emulators[0]}"
fi
[[ "$emulator_serial" = emulator-* ]] || { printf 'This script accepts emulator serials only.\n' >&2; exit 1; }
[[ "$("$adb_bin" -s "$emulator_serial" get-state)" = device ]]
./gradlew assembleDebug assembleDebugAndroidTest
# This override validates the Java UI on x86; it does not emulate the ARM64 executables.
"$adb_bin" -s "$emulator_serial" install --abi arm64-v8a -r app/build/outputs/apk/debug/app-debug.apk
"$adb_bin" -s "$emulator_serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
smoke_result="$("$adb_bin" -s "$emulator_serial" shell am instrument -w com.blocknet.wallet.test/com.blocknet.wallet.WalletSmokeInstrumentation)"
printf '%s\n' "$smoke_result"
if [[ "$smoke_result" != *'OK ('* || "$smoke_result" == *'FAIL'* ]]; then exit 1; fi
mkdir -p screenshots
"$adb_bin" -s "$emulator_serial" exec-out run-as com.blocknet.wallet cat files/smoke-onboarding.png > screenshots/onboarding.png
for page in overview receive settings; do
  "$adb_bin" -s "$emulator_serial" exec-out run-as com.blocknet.wallet cat "files/fixture-$page.png" > "screenshots/$page.png"
done

"$adb_bin" -s "$emulator_serial" exec-out run-as com.blocknet.wallet cat files/fixture-onboarding.png > screenshots/onboarding-sync.png

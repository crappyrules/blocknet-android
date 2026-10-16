#!/usr/bin/env bash
# Reproducible, read-only helper built from the pinned Core wallet package.
set -euo pipefail
source_checkout="${1:?Usage: build-offline-wallet-view.sh CORE_CHECKOUT NEW_OUTPUT_DIRECTORY}"
output_directory="${2:?A fresh output directory is required}"
baseline=5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e
script_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
source_checkout="$(realpath -- "$source_checkout")"
output_directory="$(realpath -m -- "$output_directory")"
[[ ! -e "$output_directory" ]] || { printf 'Output directory must not already exist.\n' >&2; exit 1; }
[[ "$output_directory" != "$source_checkout" && "$output_directory" != "$source_checkout/"* ]] || { printf 'Output must be outside the Core source checkout.\n' >&2; exit 1; }
[[ "$(git -C "$source_checkout" rev-parse HEAD)" = "$baseline" ]] || { printf 'Core checkout is not at the pinned baseline.\n' >&2; exit 1; }
for tool in git tar go python3 realpath; do command -v "$tool" >/dev/null; done
mkdir -p "$output_directory/source/cmd/offline-wallet-view"
git -C "$source_checkout" archive "$baseline" | tar -x -C "$output_directory/source"
cp "$script_root/offline-wallet-view/main.go" "$output_directory/source/cmd/offline-wallet-view/main.go"
cp "$script_root/offline-wallet-view/main_test.go" "$output_directory/source/cmd/offline-wallet-view/main_test.go"
(
  cd "$output_directory/source"
  GOTOOLCHAIN=local go test ./cmd/offline-wallet-view
  GOTOOLCHAIN=local CGO_ENABLED=0 GOOS=android GOARCH=arm64 GOARM64=v8.0 \
    go build -mod=readonly -trimpath -buildvcs=false -buildmode=pie -ldflags='-s -w' \
    -o "$output_directory/blocknet-wallet-view-arm64-android" ./cmd/offline-wallet-view
)
python3 - "$output_directory" "$baseline" "$script_root" <<'PY'
import hashlib,json,subprocess,sys
from pathlib import Path
out=Path(sys.argv[1]);root=Path(sys.argv[3]);binary=out/'blocknet-wallet-view-arm64-android'
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
record={'baseline_commit':sys.argv[2],
        'helper_source_sha256':sha(root/'offline-wallet-view/main.go'),
        'helper_test_sha256':sha(root/'offline-wallet-view/main_test.go'),
        'binary_sha256':sha(binary),
        'go_version':subprocess.check_output(['go','version'],text=True).strip(),
        'scope':'Read-only existing-wallet display; no daemon, network, key export or broadcast',
        'limitation':'Pinned Core source may differ from dirty supplied Core binary; physical wallet compatibility requires acceptance testing.'}
(out/'build-provenance.json').write_text(json.dumps(record,indent=2)+'\n')
PY

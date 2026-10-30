#!/usr/bin/env bash
# Derive a disposable controlled-peer testnet Core from a verified mobile build.
# This never installs an APK or modifies the pinned upstream checkout.
set -euo pipefail
base="$(realpath -- "${1:?Usage: build-core-bootstrap-qa.sh REVIEWED_MOBILE_BUILD NEW_OUTPUT_DIRECTORY}")"
out="$(realpath -m -- "${2:?A fresh output directory is required}")"
root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
qa_patch="$root/qa/controlled-peer.patch"
[[ ! -e "$out" ]] || { printf 'Output directory must not already exist.\n' >&2; exit 1; }
[[ "$out" != "$base" && "$out" != "$base/"* ]] || { printf 'Output must be outside the reviewed build.\n' >&2; exit 1; }
[[ -r "$qa_patch" && -r "$base/build-provenance.json" && -r "$base/source/daemon.go" ]]
[[ -r "$base/host-rust-target/release/libblocknet_crypto.a" &&
   -r "$base/source/crypto-rs/target/release/libblocknet_crypto.a" ]]
export BLOCKNET_QA_BASE="$base" BLOCKNET_QA_ROOT="$root"
python3 - <<'PY'
import hashlib,json,os
from pathlib import Path
base=Path(os.environ['BLOCKNET_QA_BASE']);root=Path(os.environ['BLOCKNET_QA_ROOT'])
record=json.loads((base/'build-provenance.json').read_text())
names=[f'{i:04d}-{suffix}.patch' for i,suffix in enumerate((
    'locked-wallet-activity','android-cgo-link-flags','onchain-announcement-scan',
    'mobile-client-policy','core-wallet-persistence','core-wallet-scan-serialization',
    'core-wallet-send-readiness','core-wallet-send-reorg-activity',
    'core-wallet-notification-overflow','bootstrap-progress',
    'fetched-block-wallet-scan','wallet-birth-rescan'),1)]
fields=['patch_sha256','android_link_shim_sha256','announcement_scan_patch_sha256',
    'mobile_client_patch_sha256','wallet_persistence_patch_sha256',
    'wallet_scan_patch_sha256','wallet_send_readiness_patch_sha256',
    'wallet_followup_patch_sha256','wallet_notification_overflow_patch_sha256',
    'bootstrap_progress_patch_sha256','fetched_block_wallet_scan_patch_sha256',
    'wallet_birth_rescan_patch_sha256']
digest=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
assert record.get('experimental') is True and record.get('qa_only') is not True
assert record.get('baseline_commit')=='5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e'
assert record.get('android_api')==24
assert record.get('patch_order')==names and record.get('mobile_client_capability')==1
assert record.get('binary_sha256')==digest(base/'blocknet-core-arm64-android-experimental')
for name,field in zip(names,fields):
    assert record.get(field)==digest(root/'core-patches'/name),name
assert record.get('cargo_lock_sha256')==digest(base/'Cargo.lock')==digest(root/'core-patches/core-activity.Cargo.lock')
PY
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/30.0.16248370}}"
[[ -r "$ndk/source.properties" ]] || { printf 'Android NDK r30 is required.\n' >&2; exit 1; }
ndk="$(realpath -- "$ndk")"
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) host_tag=linux-x86_64 ;;
  Darwin-x86_64|Darwin-arm64) host_tag=darwin-x86_64 ;;
  *) printf 'Unsupported NDK build host.\n' >&2; exit 1 ;;
esac
toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag/bin"
api=24
cc="$toolchain/aarch64-linux-android${api}-clang"
cxx="$toolchain/aarch64-linux-android${api}-clang++"
[[ -x "$cc" && -x "$cxx" && -x "$toolchain/llvm-ar" && -x "$toolchain/llvm-readelf" ]]
mkdir -p -- "$out"
cp -a -- "$base/source" "$out/source"
work="$out/source"
for name in Cargo.lock applied.patch android-cgo-link-flags.patch onchain-announcement-scan.patch \
    mobile-client-policy.patch 0005-core-wallet-persistence.patch \
    0006-core-wallet-scan-serialization.patch 0007-core-wallet-send-readiness.patch \
    0008-core-wallet-send-reorg-activity.patch 0009-core-wallet-notification-overflow.patch \
    0010-bootstrap-progress.patch 0011-fetched-block-wallet-scan.patch \
    0012-wallet-birth-rescan.patch; do
    cp -- "$base/$name" "$out/$name"
done
cp -- "$base/build-provenance.json" "$out/reviewed-build-provenance.json"
git -C "$work" apply --check "$qa_patch"
git -C "$work" apply "$qa_patch"
git -C "$work" apply --reverse --check "$qa_patch"
cp -- "$qa_patch" "$out/controlled-peer.patch"
# Run QA host tests using the source-built host Rust library, then restore the
# Android library before cross-compilation. A failed test leaves no valid output.
install -m 0644 "$base/host-rust-target/release/libblocknet_crypto.a" \
    "$work/crypto-rs/target/release/libblocknet_crypto.a"
(cd "$work" && CGO_ENABLED=1 GOTOOLCHAIN=local go test -mod=readonly -run '^TestQA' -count=1 . ./p2p)
install -m 0644 "$base/source/crypto-rs/target/release/libblocknet_crypto.a" \
    "$work/crypto-rs/target/release/libblocknet_crypto.a"
core="$out/blocknet-core-arm64-android-experimental"
(cd "$work" && CC="$cc" CXX="$cxx" AR="$toolchain/llvm-ar" CGO_ENABLED=1 \
    GOOS=android GOARCH=arm64 GOARM64=v8.0 GOTOOLCHAIN=local \
    go build -mod=readonly -trimpath -buildvcs=false -buildmode=pie \
    -ldflags='-s -w -checklinkname=0' -o "$core" .)
"$toolchain/llvm-readelf" -h "$core" > "$out/elf-header.txt"
"$toolchain/llvm-readelf" -l "$core" > "$out/elf-program-headers.txt"
export BLOCKNET_QA_OUTPUT="$out"
python3 - <<'PY'
import hashlib,json,os,subprocess
from pathlib import Path
out=Path(os.environ['BLOCKNET_QA_OUTPUT']);base=Path(os.environ['BLOCKNET_QA_BASE'])
root=Path(os.environ['BLOCKNET_QA_ROOT']);binary=out/'blocknet-core-arm64-android-experimental'
sha=lambda p:hashlib.sha256(Path(p).read_bytes()).hexdigest()
record=json.loads((out/'reviewed-build-provenance.json').read_text())
assert 'AArch64' in (out/'elf-header.txt').read_text() and 'DYN' in (out/'elf-header.txt').read_text()
assert '/system/bin/linker64' in (out/'elf-program-headers.txt').read_text()
record.update({'patch_order':record['patch_order']+['qa/controlled-peer.patch'],
    'qa_only':True,'qa_peer_only_capability':1,
    'qa_peer_only_patch_sha256':sha(root/'qa/controlled-peer.patch'),
    'reviewed_base_provenance_sha256':sha(out/'reviewed-build-provenance.json'),
    'base_binary_sha256':record['binary_sha256'],'binary_sha256':sha(binary),
    'limitation':'Disposable OFFLINETEST QA build only: testnet connects to one private fixture peer; not for production or release.'})
(out/'build-provenance.json').write_text(json.dumps(record,indent=2)+'\n')
(out/'go-buildinfo.txt').write_text(subprocess.check_output(['go','version','-m',str(binary)],text=True))
(out/'SHA256SUMS').write_text(record['binary_sha256']+'  '+binary.name+'\n')
print('Isolated ARM64 controlled-peer QA Core complete: '+str(binary))
PY

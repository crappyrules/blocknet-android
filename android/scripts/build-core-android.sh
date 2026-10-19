#!/usr/bin/env bash
# Rebuild the pinned mobile Core from reviewed patches; never installs anything.
set -euo pipefail
source_checkout="${1:?Usage: build-core-android.sh SOURCE_CHECKOUT NEW_OUTPUT_DIRECTORY ACTIVITY_PATCH MOBILE_PATCH}"
output_directory="${2:?A fresh output directory is required}"
patch_file="${3:?The reviewed activity patch is required}"
mobile_patch="${4:?The reviewed mobile patch is required}"
baseline=5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e
ndk_revision=30.0.16248370
android_api="${ANDROID_API:-24}"
script_root="$(cd -- "$(dirname -- "$0")/.." && pwd)"
source_checkout="$(realpath -- "$source_checkout")"
output_directory="$(realpath -m -- "$output_directory")"
patch_file="$(realpath -- "$patch_file")"
mobile_patch="$(realpath -- "$mobile_patch")"
[[ -r "$patch_file" && -r "$mobile_patch" ]]
[[ ! -e "$output_directory" ]] || { printf 'Output directory must not already exist.\n' >&2; exit 1; }
[[ "$output_directory" != "$source_checkout" && "$output_directory" != "$source_checkout/"* ]] || { printf 'Output must be isolated from source.\n' >&2; exit 1; }
[[ "$(git -C "$source_checkout" rev-parse HEAD)" = "$baseline" ]] || { printf 'Source checkout must be at the pinned Core commit.\n' >&2; exit 1; }
for tool in git tar python3 cargo rustc rustup go install grep realpath; do
    command -v "$tool" >/dev/null || { printf 'Required build tool is missing: %s\n' "$tool" >&2; exit 1; }
done
[[ "$android_api" =~ ^[0-9]+$ ]] && ((android_api>=24)) || { printf 'ANDROID_API must be an integer >=24.\n' >&2; exit 1; }
ndk_root="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk_root" ]]; then ndk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}/ndk/$ndk_revision"; fi
[[ -r "$ndk_root/source.properties" ]] || { printf 'Set ANDROID_NDK_HOME to installed NDK %s.\n' "$ndk_revision" >&2; exit 1; }
ndk_root="$(realpath -- "$ndk_root")"
export BLOCKNET_BUILD_NDK="$ndk_root" BLOCKNET_BUILD_API="$android_api"
python3 - <<'PY'
import json,os,re,subprocess
from pathlib import Path
ndk=Path(os.environ['BLOCKNET_BUILD_NDK'])
properties=(ndk/'source.properties').read_text()
assert re.search(r'^Pkg.Revision\s*=\s*30\.0\.16248370\s*$',properties,re.M), 'NDK revision mismatch'
platforms=json.loads((ndk/'meta/platforms.json').read_text());api=int(os.environ['BLOCKNET_BUILD_API'])
assert platforms['min']<=api<=platforms['max'], 'Android API is outside installed NDK platform range'
version=subprocess.check_output(['go','env','GOVERSION'],text=True).strip()
match=re.fullmatch(r'go(\d+)\.(\d+)(?:\..*)?',version)
assert match and tuple(map(int,match.groups()))>=(1,26), 'Go1.26+ is required by baseline go.mod'
PY
case "$(uname -s)-$(uname -m)" in
  Linux-x86_64) host_tag=linux-x86_64 ;;
  Darwin-x86_64|Darwin-arm64) host_tag=darwin-x86_64 ;;
  *) printf 'Unsupported NDK build host.\n' >&2; exit 1 ;;
esac
toolchain="$ndk_root/toolchains/llvm/prebuilt/$host_tag/bin"
cc="$toolchain/aarch64-linux-android${android_api}-clang"
cxx="$toolchain/aarch64-linux-android${android_api}-clang++"
[[ -x "$cc" && -x "$cxx" && -x "$toolchain/llvm-ar" && -x "$toolchain/llvm-readelf" ]]
rustup target list --installed | grep -Fxq 'aarch64-linux-android' || { printf 'Install the Rust aarch64-linux-android target before running this script.\n' >&2; exit 1; }
cargo_lock="${BLOCKNET_CARGO_LOCK:-$script_root/core-patches/core-activity.Cargo.lock}"
[[ -r "$cargo_lock" ]] || { printf 'The baseline ignores Cargo.lock; set BLOCKNET_CARGO_LOCK to a reviewed resolved lockfile.\n' >&2; exit 1; }
cargo_lock="$(realpath -- "$cargo_lock")"
mkdir -p -- "$output_directory/source"
work="$output_directory/source"
git -C "$source_checkout" archive "$baseline" | tar -x -C "$work"
git init --quiet "$work"
[[ "$(git -C "$work" rev-parse --show-toplevel)" = "$work" ]]
# Upstream excludes Cargo.lock. Preserve the explicit reviewed dependency resolution.
cp -- "$cargo_lock" "$work/crypto-rs/Cargo.lock"
cp -- "$cargo_lock" "$output_directory/Cargo.lock"
if [[ -n "$patch_file" ]]; then
    git -C "$work" apply --check "$patch_file"
    git -C "$work" apply "$patch_file"
    git -C "$work" apply --reverse --check "$patch_file"
    cp -- "$patch_file" "$output_directory/applied.patch"
fi
android_shim="$script_root/core-patches/0002-android-cgo-link-flags.patch"
[[ -r "$android_shim" ]]
git -C "$work" apply --check "$android_shim"
git -C "$work" apply "$android_shim"
git -C "$work" apply --reverse --check "$android_shim"
cp -- "$android_shim" "$output_directory/android-cgo-link-flags.patch"
announcement_patch="$script_root/core-patches/0003-onchain-announcement-scan.patch"
[[ -r "$announcement_patch" ]]
git -C "$work" apply --check "$announcement_patch"
git -C "$work" apply "$announcement_patch"
git -C "$work" apply --reverse --check "$announcement_patch"
cp -- "$announcement_patch" "$output_directory/onchain-announcement-scan.patch"
if [[ -n "$mobile_patch" ]]; then
    [[ "$mobile_patch" = "$(realpath -- "$script_root/core-patches/0004-mobile-client-policy.patch")" ]] || { printf 'Mobile patch must be the reviewed 0004 file.\n' >&2; exit 1; }
    git -C "$work" apply --check "$mobile_patch"
    git -C "$work" apply "$mobile_patch"
    git -C "$work" apply --reverse --check "$mobile_patch"
    cp -- "$mobile_patch" "$output_directory/mobile-client-policy.patch"
    for name in 0005-core-wallet-persistence.patch 0006-core-wallet-scan-serialization.patch 0007-core-wallet-send-readiness.patch 0008-core-wallet-send-reorg-activity.patch 0009-core-wallet-notification-overflow.patch 0010-bootstrap-progress.patch 0011-fetched-block-wallet-scan.patch; do
        reviewed="$script_root/core-patches/$name"
        [[ -r "$reviewed" ]] || { printf 'Missing reviewed mobile Core patch: %s\n' "$name" >&2; exit 1; }
        git -C "$work" apply --check "$reviewed"
        git -C "$work" apply "$reviewed"
        git -C "$work" apply --reverse --check "$reviewed"
        cp -- "$reviewed" "$output_directory/$name"
    done
fi
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$cc"
export CC_aarch64_linux_android="$cc" AR_aarch64_linux_android="$toolchain/llvm-ar"
export GOTOOLCHAIN=local
# Validate the bounded scanner against the pinned source before cross packaging.
cargo build --manifest-path "$work/crypto-rs/Cargo.toml" --target-dir "$output_directory/host-rust-target" --locked --release
mkdir -p "$work/crypto-rs/target/release"
install -m 0644 "$output_directory/host-rust-target/release/libblocknet_crypto.a" "$work/crypto-rs/target/release/libblocknet_crypto.a"
(cd "$work" && CGO_ENABLED=1 go test -run '^TestAnnouncement' -count=1 .)
if [[ -n "$mobile_patch" ]]; then
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^TestMobile' -count=1 .)
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^TestMobileClient' -count=1 ./p2p)
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^TestPeerTipEvidenceRequiresFreshConnectedWorkComparison$' -count=1 ./p2p)
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^TestWalletBootstrapLockedProgressIsNarrowAndExact$' -count=1 .)
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^Test(ApplyBlockToWalletIgnoresStaleCanonicalEvent|CatchUpAnchorsUnhashedGenesisForReadiness|ExplicitSyncRepairsSameHeightForkForReadiness|CatchUpAndLiveScanSerializeAndReload|WalletScanTipRequiresExactCanonicalTip|WalletReadinessHandlersGateLagAndAllowSynced|CreateWalletAnchorsExactTipForReadiness)$' -count=1 .)
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^Test(SendAndAdvancedSendHoldWalletThroughBroadcastAndPersistence|WalletActivityScanningMatchesCanonicalTip|QueuedForwardBlockRepairsCanonicalTipWithoutCorrectionEvent|CatchUpReorgBetweenFetchAndScanRepairsCanonicalTip)$' -count=1 .)
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^Test(WalletBlockQueueRetainsNewestInOrder|WalletAutoScanRecoversDroppedCanonicalBurst|WalletBlockOverflowReorgAndSideBranch)$' -count=1 .)
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^TestFetchedSyncBlocksAdvanceWalletAndPersistOnStop$' -count=1 .)
    (cd "$work" && CGO_ENABLED=1 go test -mod=readonly -run '^Test(AtomicReplaceWalletFilePreservesOldBytesOnInterruptedWrite|WalletSaveRejectsMnemonicRereadFailureAndPreservesFile|WalletConcurrentSavesAndMutationReloadNewestState)$' -count=1 ./wallet)
fi
export CC="$cc" CXX="$cxx" AR="$toolchain/llvm-ar"
cargo build --manifest-path "$work/crypto-rs/Cargo.toml" --target-dir "$output_directory/rust-target" --locked --release --target aarch64-linux-android
mkdir -p "$work/crypto-rs/target/release"
install -m 0644 "$output_directory/rust-target/aarch64-linux-android/release/libblocknet_crypto.a" "$work/crypto-rs/target/release/libblocknet_crypto.a"
core="$output_directory/blocknet-core-arm64-android-experimental"
(cd "$work" && CGO_ENABLED=1 GOOS=android GOARCH=arm64 GOARM64=v8.0 go build -mod=readonly -trimpath -buildvcs=false -buildmode=pie -ldflags='-s -w -checklinkname=0' -o "$core" .)
"$toolchain/llvm-readelf" -h "$core" > "$output_directory/elf-header.txt"
"$toolchain/llvm-readelf" -l "$core" > "$output_directory/elf-program-headers.txt"
export BLOCKNET_BUILD_OUTPUT="$output_directory" BLOCKNET_BUILD_BASELINE="$baseline"
python3 - <<'PY'
import hashlib,json,os,subprocess
from pathlib import Path
out=Path(os.environ['BLOCKNET_BUILD_OUTPUT']);binary=out/'blocknet-core-arm64-android-experimental'
header=(out/'elf-header.txt').read_text();program=(out/'elf-program-headers.txt').read_text()
assert 'AArch64' in header and 'DYN' in header, 'Output is not an ARM64 PIE executable'
assert '/system/bin/linker64' in program, 'Output does not use the Android64 interpreter'
sha=lambda path:hashlib.sha256(Path(path).read_bytes()).hexdigest()
patch=out/'applied.patch'
versions={name:subprocess.check_output(command,text=True).strip() for name,command in {'go':['go','version'],'rustc':['rustc','--version'],'cargo':['cargo','--version']}.items()}
mobile_patches=['0004-mobile-client-policy.patch','0005-core-wallet-persistence.patch','0006-core-wallet-scan-serialization.patch','0007-core-wallet-send-readiness.patch','0008-core-wallet-send-reorg-activity.patch','0009-core-wallet-notification-overflow.patch','0010-bootstrap-progress.patch','0011-fetched-block-wallet-scan.patch'] if (out/'mobile-client-policy.patch').exists() else []
provenance={'experimental':True,'baseline_repository':'https://github.com/blocknetprivacy/core','baseline_commit':os.environ['BLOCKNET_BUILD_BASELINE'],'baseline_export':'git archive of commit; input working-tree modifications excluded','patch_sha256':sha(patch),'android_link_shim_sha256':sha(out/'android-cgo-link-flags.patch'),'announcement_scan_patch_sha256':sha(out/'onchain-announcement-scan.patch'),'mobile_client_patch_sha256':sha(out/'mobile-client-policy.patch'),'mobile_client_capability':1,'wallet_persistence_patch_sha256':sha(out/mobile_patches[1]),'wallet_scan_patch_sha256':sha(out/mobile_patches[2]),'wallet_send_readiness_patch_sha256':sha(out/mobile_patches[3]),'wallet_followup_patch_sha256':sha(out/mobile_patches[4]),'wallet_notification_overflow_patch_sha256':sha(out/mobile_patches[5]),'bootstrap_progress_patch_sha256':sha(out/mobile_patches[6]),'fetched_block_wallet_scan_patch_sha256':sha(out/mobile_patches[7]),'patch_order':['0001-locked-wallet-activity.patch','0002-android-cgo-link-flags.patch','0003-onchain-announcement-scan.patch']+mobile_patches,'ndk_revision':'30.0.16248370','android_api':int(os.environ['BLOCKNET_BUILD_API']),'versions':versions,'go_flags':'CGO_ENABLED=1 GOOS=android GOARCH=arm64 GOARM64=v8.0 -mod=readonly -trimpath -buildvcs=false -buildmode=pie -ldflags=-s -w -checklinkname=0','cargo_flags':'--locked --release --target aarch64-linux-android','cargo_lock_sha256':sha(out/'Cargo.lock'),'binary_sha256':sha(binary),'limitation':'Pinned upstream Core plus ordered reviewed mobile patches; physical battery savings and funded-wallet migration remain unmeasured.'}
(out/'build-provenance.json').write_text(json.dumps(provenance,indent=2)+'\n')
(out/'go-buildinfo.txt').write_text(subprocess.check_output(['go','version','-m',str(binary)],text=True))
(out/'SHA256SUMS').write_text(provenance['binary_sha256']+'  '+binary.name+'\n')
print('Experimental ARM64 source build complete: '+str(binary))
print('Review build-provenance.json and the packaged APK before release.')
PY

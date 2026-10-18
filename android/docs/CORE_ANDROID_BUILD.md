# Pinned Android mobile Core build

Every Android APK requires an explicit ARM64 mobile Core built from pinned
upstream commit `5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e`. The build
script exports that committed source into a new directory, applies repository
patches 0001–0009 in order, builds Rust crypto and the Go PIE executable, runs
focused host regressions, and records exact dependency and patch hashes. It
never modifies the upstream checkout or installs an APK. There is no bundled
legacy Core binary or fallback build path.

Requirements: Android NDK `30.0.16248370`, Go 1.26 or newer, Rust toolchain
1.89.0 with `aarch64-linux-android`, Git, Python 3, tar, and the reviewed
`core-patches/core-activity.Cargo.lock`. The build uses Cargo `--locked` and
Go `-mod=readonly`. Missing cached dependencies may be fetched at their pinned
versions.

```sh
ANDROID_NDK_HOME=/absolute/android-sdk/ndk/30.0.16248370 \
RUSTUP_TOOLCHAIN=1.89.0 \
android/scripts/build-core-android.sh \
  /absolute/path/to/pinned-core-checkout \
  /absolute/new/mobile-build \
  android/core-patches/0001-locked-wallet-activity.patch \
  android/core-patches/0004-mobile-client-policy.patch
```

The script applies 0002 and 0003 and then the remaining mobile patches 0005–0009
from `android/core-patches`. Its output includes the ARM64 PIE executable,
reviewed patch copies, Cargo lock, ELF checks, Go build information,
`SHA256SUMS`, and `build-provenance.json`. Gradle requires
`-PcoreMobileDir=/absolute/new/mobile-build`; `verify-apk.py` requires the
matching `--core-mobile-dir`. Both reject mismatched source, patch, lockfile,
binary, or packaged provenance bytes. `ANDROID_API` defaults to 24; the app
itself requires Android 26 or newer. Build and package checks do not replace
physical wallet compatibility, radio transition, or battery measurements.

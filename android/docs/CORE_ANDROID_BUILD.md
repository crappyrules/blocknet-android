# Experimental Android Core source build

The shipped app normally packages the supplied Core unchanged. `scripts/build-core-android.sh` is an explicit experimental source-build path; it exports a baseline into a new directory, optionally applies a reviewed patch there, builds Android Rust crypto and the Go executable, and records provenance. It does not replace the provided binary, change APK packaging or install anything.

The supplied executable reports Go 1.27.0, Android ARM64, commit `5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e` and `vcs.modified=true`. Its unknown original working-tree changes prevent claiming a byte-identical recreation. Source rebuilds must pass API, cryptographic and Android runtime acceptance before any packaging change.

Requirements:

- A checkout at that exact Core baseline commit. Only committed baseline files are exported; working-tree edits are excluded.
- Android NDK `30.0.16248370`, matching the supplied executable's NDK build identification, and a supported Linux x86-64 or macOS host.
- Rust with the `aarch64-linux-android` target and Cargo, Go 1.26 or newer, Git, Python 3, tar, ripgrep and normal shell utilities.
- An explicit reviewed `Cargo.lock`: upstream ignores this file. The build uses `--locked`, saves the supplied resolution and records its hash. Missing cached dependencies may be fetched by the build tools; versions remain constrained by Cargo.lock/go.sum.
- A fresh output directory and, for an extension, a reviewed complete patch including new files.

Example, after those prerequisites are installed:

```sh
ANDROID_NDK_HOME=/absolute/path/to/android-sdk/ndk/30.0.16248370 \
BLOCKNET_CARGO_LOCK=/absolute/path/to/reviewed/crypto-rs/Cargo.lock \
android/scripts/build-core-android.sh \
  /absolute/path/to/baseline-checkout \
  /absolute/path/to/new-isolated-output \
  android/core-patches/0001-locked-wallet-activity.patch
```

`ANDROID_API` defaults to 24, matching the original executable's minimum native API; the app itself requires Android 26 or newer. The script checks the NDK's supported API range. Rust's static library is copied only into the exported source tree at the path required by Core's existing cgo declaration. A previously built host crypto library is not modified.

The output includes the experimental executable, applied patch, Cargo.lock, ELF checks, Go build information, SHA256SUMS and `build-provenance.json` with exact source, patch/dependency hashes and tool versions. The script automatically applies the separate `0002-android-cgo-link-flags.patch`: Android uses `-ldl` and excludes the Linux-only `-lpthread` flag because Bionic provides pthread functions in libc. Linux, macOS and Windows link settings are preserved. The shim digest is recorded separately from the API extension patch in build provenance. Compilation and ELF shape alone do not prove wallet compatibility or transaction correctness.

The validated dependency resolution is included with the Android source as `core-patches/core-activity.Cargo.lock`; it is the build script default. Both Gradle and APK verification require its digest to match the candidate provenance. This preserves the exact reviewed crypto dependency graph even when ignored build artifacts are removed.

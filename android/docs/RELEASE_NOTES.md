## Blocknet Android wallet preview

A native ARM64 Android wallet for Blocknet mainnet. Requires Android 8 or newer.

### Download

- **blocknet-wallet-arm64-activity-debug.apk** — includes the source-built activity Core;
  transaction alerts can continue while a loaded wallet is locked after opening it once
  following a node restart. Choose this build for the full feature preview.
- **blocknet-wallet-arm64-supplied-debug.apk** — compatibility fallback using the original
  supplied Core. Incoming transaction alerts require an unlocked wallet.
- **SHA256SUMS** — verify the downloaded APK with `sha256sum --check SHA256SUMS`.
- **core-activity-build-provenance.json** — pinned source, patch, toolchain and binary hashes.

### Features

- Encrypted local wallets, seed recovery with offline word completion, and encrypted file backups.
- Optional strong biometric unlock using Android Keystore.
- Blocknet ID shortname sending, branded payment QR codes and camera scanning.
- Wi-Fi-only or mobile-data sync, bundled mainnet checkpoints, and transaction notifications.
- Transaction history links to the Blocknet explorer.

### Preview status

These APKs are debug builds signed with a disposable CI debug key. Signing keys may differ
between runs and from a locally installed build. Android may refuse an in-place update.
**Export an encrypted wallet backup and confirm your recovery material before uninstalling;
uninstalling deletes the app's local wallet data.** Stable release signing is not configured yet.

CI runs unit tests, Android lint and APK payload verification for both variants. CI does not
perform physical biometric, camera, audible notification or real-funds acceptance tests.
See [acceptance details](https://github.com/crappyrules/blocknet-android/blob/master/android/docs/ACCEPTANCE.md).

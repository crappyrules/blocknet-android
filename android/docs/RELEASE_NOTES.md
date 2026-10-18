## Historical debug-signed Blocknet Android previews

These notes describe the debug-signed previews published before
`v1.0.0-preview.2`. Preview.2 introduced the owner certificate; a newer APK
with the same package name and certificate can update it in place. See the
[current owner-signed release notes](OWNER_RELEASE_NOTES.md).

A native ARM64 Android wallet for Blocknet mainnet. Requires Android 8 or newer.

### Migration from these debug-signed APKs

Published preview APKs used disposable signing certificates, including different
certificates for the supplied and activity variants. Android rejects an in-place
update signed with a different certificate. **Do not uninstall the old wallet or
clear its app data to make a new APK install.** Keep the old installation until
you have verified recovery on a separate installation. A phrase restores only
wallets created from a phrase; wallets imported from spend/view keys have no
phrase. Those wallets require their original keys or an encrypted wallet backup
and its password. If every usable recovery copy is lost, access to the coins
can be lost permanently. Signing and phone migration checks are still pending.

### Download

- **blocknet-wallet-arm64-activity-debug.apk** — includes the source-built activity Core;
  transaction alerts can continue while a loaded wallet is locked after opening it once
  following a node restart. Choose this build for the full feature preview.
- **blocknet-wallet-arm64-supplied-debug.apk** — compatibility fallback using the original
  supplied Core. Incoming transaction alerts require an unlocked wallet.
- **SHA256SUMS** — verify the downloaded APK with `sha256sum --check SHA256SUMS`.
- **core-activity-build-provenance.json** — pinned source, patch, toolchain and binary hashes.
- **offline-wallet-view-build-provenance.json** — pinned source and binary hash for the read-only local wallet reader.

### Features

- Encrypted local wallets, seed recovery with offline word completion, and encrypted file backups.
- Optional strong biometric unlock using Android Keystore.
- Blocknet ID shortname sending, branded payment QR codes and camera scanning.
- Wi-Fi-only or mobile-data sync, bundled mainnet checkpoints, and transaction notifications.
- Transaction history links to the Blocknet explorer.

### Preview status

These APKs are debug builds signed with disposable CI keys. A separate local
release key has been used for a local test build, but no signed APK has been
published and no safe migration has been completed. Keep any existing installation until
recovery is verified.

CI runs unit tests, Android lint and APK payload verification for both variants. CI does not
perform physical biometric, camera, audible notification or real-funds acceptance tests.
See [acceptance details](https://github.com/crappyrules/blocknet-android/blob/master/android/docs/ACCEPTANCE.md).

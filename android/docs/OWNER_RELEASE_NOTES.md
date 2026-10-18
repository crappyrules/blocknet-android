# Blocknet Android v1.0.0-preview.5

This owner-signed preview hardens language switching on Android 12 and other
pre-Android 13 versions. An explicit English choice now uses a fresh English
resource context, English interface copy, and English date formatting after
an app update or screen recreation. Simplified Chinese and Follow phone
language remain available. A language change still locks a loaded wallet
first. If running Core does not confirm the lock, the change does not proceed.

The cause of a report that an Android 12 installation remained Chinese after
preview.4 has not been confirmed. The earlier code already switched to English
on an isolated Android 11 fixture. The updated build passed host tests and
localization checks; an isolated Android 11 sidecar switched from saved Chinese
to visible English and stayed English after process restart. Android 12 device
validation remains open. These tests did not access a production wallet or
send funds.

Preview.5 carries forward preview.3's authenticated offline view of the last
saved wallet state. Balances and activity show a saved time and may be stale.
Sending and broadcasting require a running, caught-up node and a permitted
connection. It also retains the encrypted local address book and Simplified
Chinese wallet interface. The mobile Core profile does not listen for incoming
peers or serve chain data and peer lists; it retains transaction stem
forwarding for Dandelion++ privacy. Core normally stops when the app goes into
the background and restarts on return, so live alerts stop while Core is down.
Restart can take time, and synced-phone battery benefit has not been measured.

This release keeps the owner signing certificate and advances Android
versionCode to 5. It can update the owner-signed preview.2, preview.3 and
preview.4 in place while retaining app data. The address book is encrypted
with a separate device-bound Android Keystore key; it is not a transferable
backup. Record important recipient addresses separately before replacing or
clearing a device. A fluent review of the Chinese wording and a production
wallet upgrade test remain open.

## Older debug-signed previews

Preview.1 and earlier public previews used different debug certificates.
Android cannot update those installations in place with this owner-signed APK.
**Do not uninstall an existing wallet or clear its data just to install this
build.** First restore on a separate device or Android profile, unlock the
restored wallet, and verify its receive address against your records. Keep the
old installation until recovery is confirmed. A phrase-created wallet needs
its recovery phrase. A wallet imported directly from spend/view keys has no
recovery phrase and needs those original keys or an encrypted wallet backup
and its password. Losing every usable recovery copy can permanently lose
access to the coins.

## Download and verification

- `blocknet-wallet-arm64-activity-release.apk` is the owner-signed ARM64 APK.
  The `activity` asset name preserves the signed-update URL contract.
- `SHA256SUMS` verifies the downloaded files with `sha256sum --check SHA256SUMS`.
- Core and offline-reader build provenance files and `SOURCE_COMMIT` identify
  the native inputs and tagged source commit.

The expected APK signing-certificate SHA-256 is
`4a307e802e3504f7850c546ab8b38c292211ea5d5c5be3342107edaec1255fff`.
Check the package, version, certificate, and APK checksum before installation.
Publishing this APK does not broadcast an on-chain update announcement.

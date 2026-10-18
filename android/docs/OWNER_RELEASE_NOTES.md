# Blocknet Android v1.0.0-preview.6 test candidate

This is an owner-signed phone-acceptance candidate, not a public release. Its
workflow uploads a private Actions artifact for review and blocks publication
of preview.6. It makes the requested initial chain and wallet scan continue
after screen-off on a permitted connection until a verified saved completion.
Manual Stop sync and the Wi-Fi-only setting still take precedence. An Android
process kill, Doze, missing peers, or unavailable Wi-Fi can interrupt progress.

Settings chain height, wallet height, and peer counts now update during polling
without replacing the current Settings viewport. Passive redraws wait for a
touch or focused edit to finish; lock and saved-view transitions still redact
live wallet content. Offline balances and activity remain explicitly dated;
they cannot be used to send or broadcast. This candidate also reduces repeated
cache writes and avoids history polling when transaction alerts are disabled.

The candidate retains the encrypted local address book, language selection,
mobile Core client policy, and Dandelion++ transaction forwarding. The mobile
Core does not listen for incoming peers or serve chain data or peer lists.
Physical screen-off catch-up, restored-wallet upgrade, battery use, and
announcement delivery remain to be checked. No funded send was tested.

This candidate keeps the owner signing certificate and advances Android
versionCode to 6. It can update the owner-signed preview.2 through preview.5
in place while retaining app data. The address book is encrypted
with a separate device-bound Android Keystore key; it is not a transferable
backup. Record important recipient addresses separately before replacing or
clearing a device. A fluent review of the Chinese wording remains open.

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
Signing this test APK does not broadcast an on-chain update announcement.

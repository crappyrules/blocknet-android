# Blocknet Android owner-signed preview

### BACK UP BEFORE ANY UPGRADE — EXISTING WALLET DATA IS AT RISK

Earlier published previews were signed with different debug certificates.
Android cannot update those installations in place with this owner-signed APK.
**Do not uninstall an existing wallet or clear its data just to install this
build.** First restore on a separate device or Android profile, unlock the
restored wallet, and verify that its receive address matches your records.
Keep the old installation until recovery is confirmed. A phrase-created
wallet needs its recovery phrase. A wallet imported directly from spend/view
keys has **no recovery phrase** and needs the original keys or an encrypted
wallet backup **and its password**. If every usable recovery copy is lost,
access to the coins can be lost permanently. This release has no automatic
migration from an earlier preview certificate.

### Download and verification

- `blocknet-wallet-arm64-activity-release.apk` — owner-signed ARM64 APK with
  the reviewed activity Core and offline wallet reader.
- `SHA256SUMS` — run `sha256sum --check SHA256SUMS` on the downloaded release files.
- `core-activity-build-provenance.json` and
  `offline-wallet-view-build-provenance.json` — native build provenance.
- `SOURCE_COMMIT` — source commit checked against the release tag during signing
  and publication.

The expected APK signing-certificate SHA-256 is
`4a307e802e3504f7850c546ab8b38c292211ea5d5c5be3342107edaec1255fff`.
Check the package, version, certificate, and APK checksum before installation.

### Preview status

Offline viewing of a saved wallet state requires normal password or enrolled
biometric authentication. Saved balances and activity are dated and may be
stale; broadcasting and refresh require a permitted network and a running
node. The signed on-chain update scanner is configured for a dedicated
public-view channel, but no owner update announcement has been broadcast.
Physical ARM64 device acceptance and a verified migration procedure are
required before publishing this release.

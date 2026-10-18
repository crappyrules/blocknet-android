# Blocknet Android owner-signed preview

`v1.0.0-preview.3` fixes opening a saved wallet view when no permitted network
is available. After password or enrolled biometric authentication, the last
saved balance and activity are shown with their saved time; they may be stale.
Sending and broadcasting still require a running, caught-up node and a
permitted connection.

This release uses the reviewed mobile Core build. Its peer profile does not
listen for incoming peers or serve chain data and peer lists. It still handles
status and blocks and retains transaction stem forwarding for Dandelion++
privacy. By default, backgrounding gracefully stops Core and returning to the
app restarts it; an explicit continuous-sync setting remains available. A
stopped node cannot deliver live payment alerts. Restart may take time while
Core rebuilds its local ring index. Battery benefit has **not** been measured
on a synced phone, so this release makes no battery-saving claim.

The release also adds an encrypted local address book and simplifies the
backup reminder. Save a full recipient address with an optional verified
Blocknet ID shortname, view saved recipients after offline unlock, and choose
a saved address in the payment form. A name check cannot silently change its
saved full address. A missing, changed, or unavailable name requires an
explicit choice before payment review. Offline balances and address-book
check times are last-saved values, not live network information; sending
still requires the running node and a permitted connection.

This release also bundles Simplified Chinese for the wallet screens,
dialogs, notifications, and relevant errors. It follows the phone language
by default; a selector on the locked screen and in Settings offers phone
language, English, and 简体中文. Changing it locks the wallet. English remains
the fallback, while addresses, amounts, shortnames, keys, and recovery words
retain their protocol forms. A fluent human review of the Chinese wording
remains open.

The address book uses a separate device-bound Android Keystore key and
app-private encrypted storage. Contacts remain on the device through a
same-signer update but are not a transferable backup. Export or record
important recipient addresses separately before replacing or clearing a
device. This release keeps the existing owner signing certificate, so it can
update owner-signed `v1.0.0-preview.2` in place. The older debug-signed
previews still require the migration precautions below.

### Migrating from a debug-signed older preview

`v1.0.0-preview.1` and earlier published previews were signed with different
debug certificates. Android cannot update those installations in place with
the owner-signed `v1.0.0-preview.2` APK.
**Do not uninstall an existing wallet or clear its data just to install this
build.** First restore on a separate device or Android profile, unlock the
restored wallet, and verify that its receive address matches your records.
Keep the old installation until recovery is confirmed. A phrase-created
wallet needs its recovery phrase. A wallet imported directly from spend/view
keys has **no recovery phrase** and needs the original keys or an encrypted
wallet backup **and its password**. If every usable recovery copy is lost,
access to the coins can be lost permanently. This release has no automatic
migration from an earlier preview certificate.

Preview.2 introduced the owner certificate. A newer release with the same
package name and certificate can update it in place and retain its app data.
Keeping a separately verified recovery copy is still prudent.

### Download and verification

- `blocknet-wallet-arm64-activity-release.apk` — owner-signed ARM64 APK with
  the reviewed mobile Core and offline wallet reader. The `activity` asset
  name preserves the existing signed-update protocol variant and URL contract.
- `SHA256SUMS` — run `sha256sum --check SHA256SUMS` on the downloaded release files.
- `core-mobile-build-provenance.json` and
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
The owner-signed preview.2 APK was published. On an isolated, unfunded S22
sidecar, preview.3 passed encrypted-cache binding checks and a foreground
lifecycle test: Core stopped in the background, password unlock displayed a
dated saved view with Core stopped, and foreground return reconciled it.
Manual Stop remained stopped after app-scoped process death and relaunch.
No funds were sent or broadcast. A startup longer than five seconds, rapid
foreground-return race, Wi-Fi-to-cellular transition, synced-phone battery
consumption, and funded-wallet migration have not been measured on the phone.
The owner update announcement has not yet been broadcast.

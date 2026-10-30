# Blocknet Android v1.0.0-preview.9

This owner-signed preview improves unattended synchronization and wallet rescans.
When background sync is enabled, Android schedules chain catch-up on permitted
Wi-Fi; the existing Allow mobile data choice also permits cellular catch-up.
Android controls the timing of background jobs, so the 15-minute minimum
interval is not a promise that the wallet will be current every 15 minutes.
Manual Stop sync remains effective. A locked wallet is not automatically
unlocked by a background job.

Rescan now rewinds the wallet scan cursor so previously missed outputs can be
found. A newly created wallet records a conservative starting height when the
chain tip is known; imported, restored, and older wallets scan from genesis
when their history is unknown. Rescan and initial scan progress remain tied to
the wallet and its normal lock rules. The wallet shows chain and wallet scan
progress separately. Do not treat a cached balance as current or spendable
until catch-up and wallet scanning complete.

The balance can be tapped to switch between BNT and an approximate USDT value
based on SafeTrade's public BNT/USDT quote. This is an indicative estimate,
not an exchange offer. The quote may be stale or unavailable and is requested
only while the wallet is visible and unlocked. The app does not send wallet
addresses or balances to SafeTrade.

Disposable testnet acceptance recovered a deliberately missed older output
through a real rescan, observed chain and wallet progress during a 16-minute
screen-off session, and observed one natural post-reboot chain-only job reach
the test tip. These tests used a small synthetic chain and no real funds.
Large mainnet backlogs, funded sends, restored-wallet upgrades, live price
display with a loaded mainnet wallet, battery use, and timing across different
Android devices remain unverified. Users should confirm both sync heights and
their expected activity before relying on the displayed balance.

This APK keeps the owner signing certificate, package `com.blocknet.wallet`,
and advances Android versionCode to 9. It can update owner-signed preview.2
through preview.7 in place while retaining app data. The encrypted local
address book uses a device-bound Android Keystore key and is not a transferable
backup. Keep independent wallet recovery material and copies of important
recipient addresses before replacing or clearing a device.

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

![Blocknet Android — Your money. Your keys. In your pocket.](android/screenshots/readme-hero.svg)

<p align="center">
  <a href="https://github.com/crappyrules/blocknet-android/actions/workflows/android.yml"><img alt="Android APK build" src="https://github.com/crappyrules/blocknet-android/actions/workflows/android.yml/badge.svg"></a>
  <img alt="Android 8 and newer" src="https://img.shields.io/badge/Android-8%2B-AAFC31?style=flat-square&amp;labelColor=11150d">
  <img alt="ARM64" src="https://img.shields.io/badge/Architecture-ARM64-AAFC31?style=flat-square&amp;labelColor=11150d">
  <img alt="Mainnet" src="https://img.shields.io/badge/Network-Mainnet-AAFC31?style=flat-square&amp;labelColor=11150d">
</p>

# Blocknet Android Wallet

Native Android wallet for Blocknet mainnet, using Blocknet Core and Mantle.

## Private money. Native experience.

<table>
  <tr>
    <th>Your wallet, at a glance</th>
    <th>Receive with a scan</th>
    <th>Security in your hands</th>
  </tr>
  <tr>
    <td align="center"><img src="android/screenshots/overview.png" alt="Wallet overview with sample balance and recovery controls" width="260"></td>
    <td align="center"><img src="android/screenshots/receive.png" alt="Receive screen with lime Blocknet QR code" width="260"></td>
    <td align="center"><img src="android/screenshots/settings.png" alt="Wallet security settings with biometric unlock and encrypted backup controls" width="260"></td>
  </tr>
  <tr>
    <td>Balances, sync progress and recovery tools in one place.</td>
    <td>Branded payment QR codes, sharing and verified shortnames.</td>
    <td>Optional biometric unlock, encrypted backups and seed recovery.</td>
  </tr>
</table>

*Screenshots are app-rendered design fixtures with synthetic wallet data; shown balances and addresses are examples.*

| Feature | What you can do |
| --- | --- |
| **Keys on your phone** | Create or restore an encrypted wallet; import private keys or a view-only account. |
| **Faster recovery entry** | Tap offline word suggestions while typing your seed phrase. |
| **Biometric unlock** | Unlock with supported strong biometrics using Android Keystore protection. |
| **Send by shortname** | Resolve signed Blocknet ID `$shortnames` and review the destination before sending. |
| **Scan and share** | Use Blocknet-branded QR codes and payment requests with optional amount and memo. |
| **Control your connection** | Choose Wi-Fi-only sync or allow mobile data; bootstrap with bundled mainnet checkpoints. |
| **Stay informed** | Receive transaction notifications with optional sound; open hashes in the explorer. |

Locked-wallet alerts require the **activity** Core build and opening the wallet once after each node restart.

## Build, install and explore

- [Build and setup](android/README.md)
- [Acceptance results](android/docs/ACCEPTANCE.md)
- [Core build instructions](android/docs/CORE_ANDROID_BUILD.md)
- [Download a CI build](https://github.com/crappyrules/blocknet-android/actions/workflows/android.yml)

The `android-test` directory contains the supplied ARM64 build inputs.

## GitHub Actions APK builds

[Build Android APK](https://github.com/crappyrules/blocknet-android/actions/workflows/android.yml)
runs on pushes to `main`, pull requests, version tags, and manual **Run workflow** requests.
Each run tests, lints, builds and verifies two ARM64 APKs:

- **activity**: rebuilds the pinned Core source with the reviewed activity patches;
  supports transaction alerts while a loaded wallet is locked.
- **supplied**: packages the original supplied Core unchanged as a compatibility fallback.

Open a successful run and download its APK artifact under **Artifacts**. Each includes
the APK and `SHA256SUMS`; the activity variant also includes build provenance.
These are debug APKs signed with the runner's disposable Android debug key.
They are suitable for testing, and may require uninstalling an APK signed by a different
key. Export an encrypted wallet backup before uninstalling. Stable release signing
requires a separately configured signing key; no private signing key is in this repository.

CI does not run a node, access wallets, or perform funded transactions. Device acceptance
results and remaining physical checks are documented in `android/docs/ACCEPTANCE.md`.

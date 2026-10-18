![Blocknet Android — Your money. Your keys. In your pocket.](android/screenshots/readme-hero.svg)

<p align="center">
  <a href="https://github.com/crappyrules/blocknet-android/actions/workflows/android.yml?query=branch%3Amaster"><img alt="Android APK build on master" src="https://github.com/crappyrules/blocknet-android/actions/workflows/android.yml/badge.svg?branch=master&amp;event=push"></a>
  <img alt="Android 8 and newer" src="https://img.shields.io/badge/Android-8%2B-AAFC31?style=flat-square&amp;labelColor=11150d">
  <img alt="ARM64" src="https://img.shields.io/badge/Architecture-ARM64-AAFC31?style=flat-square&amp;labelColor=11150d">
  <img alt="Mainnet" src="https://img.shields.io/badge/Network-Mainnet-AAFC31?style=flat-square&amp;labelColor=11150d">
</p>

# Blocknet Android Wallet

Native Android wallet for Blocknet mainnet, using the bundled Blocknet Core executable.

> [!CAUTION]
> **Existing preview wallet? Verify recovery before changing APKs.** Earlier
> preview APKs used disposable signing certificates. Android cannot install an
> APK signed with the new owner certificate over one of those builds. **Do not
> uninstall your current wallet or clear its app data to make an update work.**
> First verify that you can restore on a separate device or Android profile.
> Phrase-created wallets need the recovery phrase. Wallets imported from
> spend/view keys have **no recovery phrase**; keep the original keys or an
> encrypted wallet backup **and its password**. Losing every usable recovery
> copy can permanently lose access to your coins. Read the
> [migration warning](android/docs/OWNER_RELEASE_NOTES.md) before installing.
> The published `v1.0.0-preview.2` APK uses the owner certificate. A newer APK
> with the same package name and certificate can update it in place and keep
> its app data; the warning above concerns migration from older certificates.

<p align="center"><a href="https://github.com/crappyrules/blocknet-android/releases"><strong>Download the Android preview APK →</strong></a></p>

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
- [Download APKs from Releases](https://github.com/crappyrules/blocknet-android/releases)
- [Browse CI builds and test reports](https://github.com/crappyrules/blocknet-android/actions/workflows/android.yml)

The old bundled Core executables and their test directory are removed from
the current source tree. Android builds Core from pinned upstream source and
the ordered reviewed mobile patches.

## GitHub Actions APK builds

[Build Android APK](https://github.com/crappyrules/blocknet-android/actions/workflows/android.yml)
runs on pushes to `master`, pull requests, version tags, and manual **Run workflow** requests.
The ordinary run builds, tests, lints, and verifies the ARM64 mobile Core APK
from pinned source. There is no bundled-binary fallback.

Open a successful run and download the mobile APK artifact under **Artifacts**.
Each includes a debug APK, checksum and build provenance. CI debug APKs use disposable signing
keys and are only for testing. A version tag does **not** publish a release.
Owner signing and publication are separate manual jobs protected by GitHub
environment approvals and tag checks. No private signing key is in this repository.
See the [release process](android/docs/CI_RELEASE.md) and
[migration warning](android/docs/OWNER_RELEASE_NOTES.md) before using an APK.

CI does not run a node, access wallets, or perform funded transactions. Device acceptance
results and remaining physical checks are documented in `android/docs/ACCEPTANCE.md`.

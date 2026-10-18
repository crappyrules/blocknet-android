# Native wallet acceptance — October 15, 2026

This records the earlier installed activity-Core build. The current mobile
candidate removes Mantle and has not inherited this device acceptance.

## Delivered artifact

`builds/blocknet-wallet-1.0.0-arm64-debug.apk` is installed for user 0 on the Samsung Galaxy S22 (SM-S901U), Android 16/API 36. Its SHA-256 is `1e48f41d35462194af23f34b575623dfc9a4b8d2ef0b0137b820675cf86584a5`; an on-device hash of the installed APK matched the distributable.

This section records a historical activity-Core acceptance run. The current
release tree removes its legacy executable inputs; Git history retains that
earlier evidence. The preview.3 candidate builds pinned upstream Core with
reviewed mobile patches and packages no Mantle executable. Its build
provenance records the source, patches, lockfile, and binary hashes.

Acceptance logs are retained in `builds/acceptance/`. The build directories are ignored artifacts; application source and repeatable tests are under `android/`.

## Requirement evidence

| Requirement | Current evidence |
| --- | --- |
| Full native Android wallet using Core and Mantle | Framework Java Views and native Android activities/service; no WebView or terminal dependency. Both programs execute on the ARM64 phone. Creation, recovery, encryption, lock/unlock, signatures, view-only import, wallet switching and encrypted file backup have device integration coverage. |
| Blocknet website theme and supplied icon | Black/lime native UI; canonical logo in adaptive and monochrome launcher resources. APK resource verifier and rendered fixtures cover layout and packaged icon mappings. |
| Mainnet and checkpoint-based startup | Installed node reported block 66,333, `syncing=false`, two peers; it subsequently advanced to 66,334 with seven peers. New Core loaded all 662 saved checkpoints through 66,200. Existing encrypted wallet file fingerprint remained unchanged during update. No testnet node ran during this continuation. |
| Wi-Fi-only or mobile-data synchronization | User switch defaults to Wi-Fi only. Active validated transport and network changes control actual Core pause/resume; Java HTTPS requests bind to the checked network. Six unit and 14 live emulator network checks cover routing decisions and service transitions. |
| Recovery phrase backup and restore | Password-protected phrase display and backup confirmation; encrypted file import/export. Twenty-five offline original/candidate checks cover deterministic recovery, encrypted wallet interchange and cross-version signatures. |
| Recovery-word completion | Exact Core BIP39 English 2,048-word asset with provenance/license. First-letter suggestions include `pond`; tapping completes only the current token and advances. Four dictionary tests and actual masked UI typing/tap checks passed. |
| Biometric unlock | Optional per-wallet, per-network Android Keystore AES-GCM credential with per-use strong biometric authorization. Eighteen emulator checks exercised real Keystore CryptoObjects, authentication, cancellation, deletion and lifecycle cleanup. Password remains the fallback. |
| Hidden passwords and seed input | Strict masking covers existing text, the most recently typed character, multiline recovery input and Android's enabled show-password preference. The regression reproduced the old failure and passed after correction. |
| Transaction notifications and optional sound | Twenty Android 16 notification checks cover posting, private/public lockscreen content, channels, preference changes, permission denial and durable deduplication. The authenticated Core activity extension works while a loaded wallet is locked; existing spending/seed/signing guards remain intact. Twenty-four Android loopback activity checks enforce wallet identity, scan readiness, paging and error handling. |
| Shortname sending and preferred receive name | Core resolves signed `$name`/`@name` destinations; review pins the resolved address. Receive verifies the signed mapping against the active wallet before using a name. Eighteen Android signature/lookup checks passed, including a live public `$blocknet` lookup. Failed or expired verification falls back to the full address. |
| Branded receive/payment QR | Native black-on-lime QR with centered logo, high error correction and quiet zone. Twelve actual bitmap decode checks cover four rotations, a shortname URI and a long UTF-8 request. |
| Camera scanning and reviewed prefill | Eleven real preview/lifecycle and eleven denial checks passed on API 30. Twelve API 36 camera checks passed with an image-file feed through actual camera callbacks, QR decoding, activity result delivery and recipient/amount/memo prefill. No payment was submitted. |
| Explorer links | History hashes construct the desktop wallet's fixed HTTPS explorer URL only for complete 64-hex transaction IDs. Seven focused intent validity checks passed; card details remain available. |
| Safe payment construction and interrupted-send recovery | Review binds network, wallet, recipient, amount, memo, fee and expiry; durable idempotency precedes send. Six ARM64 in-process tests passed for synthetic funded sends, replay/restart recovery, fee failures, encrypted memos and change outputs. Nine additional ARM64 tests cover activity lock guards, commitments/range proofs, stealth keys, ring signatures, RingCT inflation rejection and recovery vectors. No real transaction was broadcast. |

That historical application gate passed **45 unit tests, lint, APK provenance/resource verification and 60 Android 16 UI checks**. The current build requires an explicit reviewed mobile Core; no legacy fallback is packaged.

## Practical verification limits

A wallet must be opened once after Core restarts before locked-wallet activity can be observed. The node must run and the wallet scan must catch up. The first valid snapshot is a quiet baseline; catchup and deduplication are bounded as described in the README. Incoming mempool notifications are not exposed by Core. Android permission, channel, volume and system process policies govern final notification delivery.

Physical Samsung fingerprint enrollment, real-world camera focus/lighting, physical radio handover and audible playback were not performed by the agent. Their Android API paths have the emulator coverage above. Actual receipt and spending of real mainnet funds remain untested; no user password or seed was accessed and no real funds were moved. These are explicit device and funds acceptance limits of this locally installed development APK.

# Blocknet native Android wallet

This is the standalone native Android application. The desktop Tauri application remains at the repository root. The app uses Android Views and Java 17, with Blocknet Core running as a child process of a foreground Android service. Mantle is bundled alongside the core. There is no Termux dependency.

## Build

Requirements: JDK 17 with `javac`, Android SDK platform 36 and build tools, Python 3 and the SDK’s `aapt2` for packaged payload/resource verification. Set `ANDROID_HOME` or create an ignored `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`.

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
python3 scripts/verify-apk.py
```

Or run `scripts/check.sh` to perform those checks together. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it on an **ARM64 Android 8.0 or newer** phone:

```sh
adb -s PHYSICAL_ADB_SERIAL install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is for development. A distributable release needs an owner-controlled signing key configured outside source control. Never commit signing keys, passwords or wallet backups.

## Supplied native executables

The build stages the original `../android-test/android-test/blocknet` (Mantle v1.2.0) and `blocknet-core-arm64-android` (Core v0.22.0) as `lib/arm64-v8a/libmantle.so` and `libblocknet_core.so`. The `.so` suffix tells Android's package installer to extract them into the executable native library directory; they remain executable programs. The app invokes their absolute paths instead of loading them as libraries.

`extractNativeLibs`, legacy JNI packaging and `keepDebugSymbols` preserve the payload and make execution compatible with Android's restriction on launching executables from writable app storage. `verify-apk.py` compares both packaged programs byte for byte with the supplied originals and prints their SHA-256 hashes. It also verifies the bundled mainnet checkpoint asset against its packaged provenance (source, SHA-256, count and maximum height), plus compiled adaptive launcher resources and the manifest icon mappings. It locates `aapt2` through `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `local.properties` or PATH; an explicit `--aapt2` path is supported. Default builds do not download, upgrade, patch or replace either program. An explicitly selected experimental Core build is described below.

App-private storage holds node data and wallet files. Android backup is disabled. The local API uses loopback only; network security configuration permits cleartext HTTP solely for `127.0.0.1` and `localhost`. Node synchronization continues through a visible foreground notification. Camera access is optional and requested for scanning payment QR codes.

## Testing scope

Host tests and lint are run by the check script. Native Android instrumentation can be run on a clean emulator using `scripts/smoke-emulator.sh emulator-PORT` (requires `adb` on PATH or `ANDROID_HOME`; it also accepts `ANDROID_SERIAL` or discovers exactly one connected emulator and rejects physical phone serials). It verifies installed native payload hashes, screenshot protection, stopped onboarding layout, and that a payment deep link does not launch a node or create an authenticated core session without user confirmation. The test draws the non-sensitive stopped onboarding view into `screenshots/onboarding.png`; production screenshot protection stays enabled. Runtime testing requires an ARM64 Android device for the bundled executables. Actual app-owned Core and Mantle execution plus wallet lifecycle were verified on a Samsung Galaxy S22 (SM-S901U), Android 16/API 36, on 2026-10-15. An x86 emulator can validate the Java interface only if installed using an ABI override; it cannot establish that ARM64 Core or Mantle execute correctly. Android's modern QEMU2 emulator does not boot an ARM64 system image on an x86 host.

Physical acceptance still needs camera scanning/permission denial, background and screen-off behavior, a full mainnet sync, and a funded transaction preview/broadcast. The verified disposable testnet pass covered creation/recovery, lock/unlock, message signing, unfunded dry-run rejection, view-only import, core restart, and encrypted backup export/import. A complete mainnet sync also depends on available storage, network connectivity and peers.

## Verification performed

On 2026-10-15, `assembleDebug`, `assembleDebugAndroidTest`, `testDebugUnitTest` and `lintDebug` passed with JDK 17, API 36 and Gradle 8.13. The APK payload check confirmed both native programs match the supplied originals exactly. The API 30 x86 emulator successfully installed the ARM64 APK with an explicit ABI override and rendered the native Android interface; screenshot protection remained enabled.

The latest host gate passes 45 unit tests, including biometric scope/encoding validation, sync network decisions, checkpoint validation and legacy/versioned encrypted backup envelope checks. All 60 emulator smoke checks pass, covering startup readiness redraw, history pagination invalidation when new transactions arrive, confirmation/status refresh, recovery/configuration controls, and payment links that cannot start the node or submit a payment. These checks use synthetic history and no funds.

Nine additional emulator checks inject an activation failure through an in-process loopback API stub, confirm that the durable wallet identity marker blocks backup and sending, then confirm a successful activation clears it. They start no Core process and create no wallets or transactions. Build the optional test runner with `./gradlew -PidentityIntegration assembleDebug assembleDebugAndroidTest`, install its app and test APKs on an emulator, then run `adb -s EMULATOR_SERIAL shell am instrument -w com.blocknet.wallet.test/com.blocknet.wallet.WalletIdentityInstrumentation`.

The `overview.png`, `receive.png` and `settings.png` screenshots in `screenshots/` are **design fixtures with synthetic wallet data**, clearly watermarked by the instrumentation test. They demonstrate layout and QR rendering, not live balances, synchronization or payment execution. `onboarding.png` shows the actual stopped app. The optional exact ARM core harness is documented in [scripts/ARM_CORE_TESTING.md](scripts/ARM_CORE_TESTING.md).

For Android-to-core transport integration, start the isolated ARM testnet core described in [scripts/ARM_CORE_TESTING.md](scripts/ARM_CORE_TESTING.md), leave its wallet unloaded, then run `scripts/test-android-transport.sh /readable/path/to/api.cookie 18392 ADB_SERIAL`. The target may also come from `ANDROID_SERIAL`; automatic discovery requires exactly one authorized device and otherwise refuses to proceed. This selects the optional `-PapiIntegration` runner, forwards loopback through `adb reverse`, provisions the token without logging it, creates an empty encrypted test wallet, checks status/authentication/balance/message signing, unloads it, and removes the copied token. Seven checks passed against the exact supplied ARM core on 2026-10-15. It does not test app-owned ARM service execution or a funded broadcast.


## Wallet features

- Create an encrypted wallet, recover a 12-word phrase, import private keys or a view-only account, and switch between local wallet files.
- Read spendable and pending balance, scan progress, incoming outputs, and paginated outgoing transactions.
- Preview payment fees, inspect resolved handle destinations, explicitly confirm sends, and reconcile interrupted requests with persisted idempotency keys.
- Generate black-on-lime address and payment QR codes with the Blocknet logo, scan payment requests with the device camera, and handle `blocknet:` links as prefilled forms.
- Send to `$shortname` or `@handle` with signed Blocknet ID resolution and an explicit destination review. Save a verified preferred receive shortname, with full-address fallback.
- Open history transaction hashes in the Blocknet explorer.
- Confirm recovery backups, export/import encrypted wallet files through Android's document picker, lock on screen-off or after 30 seconds away, and sign or verify messages.
- Start/stop the mainnet node, inspect Mantle diagnostics and local logs, and rescan a wallet.

The native interface follows [blocknetcrypto.com](https://blocknetcrypto.com/): black background, lime accents, monospace typography, and restrained outlined panels.

## Sync connection preference

**Node settings → Allow mobile data for sync** is available before opening a wallet; the same switch is in wallet Settings. It defaults to **off (Wi-Fi only)**. Turning it on also permits mobile data. The app shows the current default connection and monitors Android network changes, including when the wallet is in the background. It checks the active route's Wi-Fi/mobile transport and internet validation, rather than treating any nearby or connected Wi-Fi as the route in use. VPNs are accepted in Wi-Fi-only mode only when Android reports Wi-Fi without cellular underneath; unknown VPN routes wait for a verifiable connection.

When a requested sync loses an allowed connection, the foreground service signals the owned Core to shut down, confirms it exits, and waits with a visible paused notification. It automatically starts Core when a permitted connection returns. Core starts with the wallet locked/unloaded, so open it again with the wallet password. Stop sync cancels the waiting session. The supplied unmodified Core has no separate networking pause or offline flag, so local Core wallet operations also become unavailable while it is stopped. Graceful shutdown may take several seconds; this preference is not an operating-system firewall or a guarantee of zero bytes during network handover.

Checkpoint downloads follow the same preference and use `Network.openConnection` on the checked network so a Wi-Fi download cannot silently fall back to cellular. A connection change or Stop cancels the active download; the last validated checkpoint file is retained.

Six host unit tests cover Wi-Fi, cellular, VPN, mixed/unknown transports, unavailable or unvalidated internet, and mobile opt-in decisions. All 14 live network policy checks passed on the x86 emulator, including blocked start, manual stop, mobile opt-in on the unchanged cellular connection, and automatic resume attempts when Wi-Fi returns. The optional `-PnetworkIntegration` runner (`WalletNetworkInstrumentation`) refuses physical devices and restores radio settings afterward. The UI smoke runner also covers the connection controls. These emulator checks prove policy/service transitions; actual ARM phone network handover still requires device acceptance.

## Recovery phrase backup and restore

Use **Settings → Show recovery phrase** (wallet password required) to view the 12 words and confirm an offline written backup. New wallet creation also offers this backup flow. Restore with **Restore recovery phrase** during setup, or **Switch / add wallet → Create / restore another wallet → Restore phrase**. Enter the words in order and choose a new wallet password. Restore shows tappable local completions from the exact 2,048-word BIP39 English list in the supplied Core baseline. Suggestions begin with the first letter (for example `p` includes `pond`); scroll the row or type more letters to narrow it. Tapping replaces only the current word and advances to the next. The entered phrase stays masked, and suggestions clear when the field loses focus or its sensitive dialog closes. No recovery text is sent to a suggestion service or saved as keyboard history. Core still validates the final 12-word phrase and checksum. Encrypted wallet file export/import is also available; wallets imported directly from keys may not contain a recovery phrase.

## Optional biometric unlock

Open an unlocked wallet's **Settings → Biometric unlock for this wallet** to enable it. Verify the wallet password, then authenticate in Android's biometric prompt. The app encrypts the password with a non-exportable Android Keystore AES-256-GCM key that requires strong biometric authentication for each operation. Only ciphertext, its IV and the scoped key alias are stored in private no-backup storage. Keys and authenticated data bind each entry to its wallet filename and network. The supplied Core still receives its normal wallet password after an authorized decryption; biometrics do not change the wallet's encryption format.

Use **Unlock with biometrics** on the locked screen, existing-wallet setup screen, or wallet chooser. The password remains available as a fallback. Seed reveal continues to require the wallet password. Disabling biometric unlock deletes its encrypted credential and key. Enrollment changes invalidate the biometric key; use the wallet password and enable biometrics again. Backgrounding the app cancels prompts, clears sensitive entry fields and in-memory password buffers, and prevents a late callback from leaving the wallet unlocked.

The optional feature requires Android 9/API 28 or newer and enrolled strong biometrics with a secure device screen lock. Android 8 remains supported with password unlock. Android 9/10 use the enrolled fingerprint compatibility path; newer versions request `BIOMETRIC_STRONG` explicitly. Weak face unlock is not used.

All 18 checks in the optional `-PbiometricIntegration` runner (`WalletBiometricInstrumentation`) passed with real Android Keystore keys and biometric CryptoObjects using synthetic credentials on an emulator, without starting Core, creating a wallet, or using funds. It tests actual background password-dialog dismissal/input clearing, authentication gating, exact secret recovery, scope binding, deletion and cancellation. The temporary emulator PIN and fingerprint enrollment were removed afterward, restoring the original no-lock/no-fingerprint state. A real fingerprint unlock on the physical phone still requires user acceptance.

## Password masking

Password and recovery phrase input fields always mask every character, including the most recently typed character. The implementation applies a strict transformation after configuring single-line or multiline input, so Android layout configuration and the system “show passwords” preference cannot reveal the text. The emulator regression first reproduced the masking failure and then verified the corrected behavior for both field types.

## Shortnames and receive QR

Send accepts `$name` and `@name`; the supplied Core verifies the Blocknet ID signature and the review screen shows the resolved address before confirmation. Receive offers **Preferred shortname** with Verify & save and Clear shortname. Saving requires a valid Ed25519-signed response from `https://blocknet.id` whose address matches the active wallet. Preferences are scoped to wallet and network. A saved name is checked again when entering Receive; the full address remains the QR destination until verification succeeds. Runtime verification expires after five minutes. The full address remains visible and selectable; a separate full-address copy action remains available when a shortname is active. Both address and payment-request QR codes use high error correction, a four-module quiet zone and a small centered Blocknet logo.

History hashes open `https://explorer.blocknetcrypto.com/tx/<txid>` through Android's browser handler. Only complete 64-character hexadecimal transaction IDs can create explorer links.

## Transaction alerts

Settings offers **Transaction alerts** (on by default) and optional sound (off by default), plus Android notification settings. Android 13+ requires notification permission. Separate silent and sound channels let Android retain each channel's settings. Private notifications show the payment amount; their public lockscreen version only says “New wallet activity.” Accepted outgoing payments can notify immediately; a separate worker checks new confirmed wallet activity every 15 seconds while the node runs.

**The activity Core build supports alerts while a loaded wallet is locked.** After opening the wallet once following a node restart, an authenticated read-only activity endpoint continues reporting new confirmed outputs and sends. The app binds every page to the public-address identity established while unlocked and waits for the wallet scan to catch up. The original supplied Core requires an unlocked wallet for incoming alerts; the app retains compatibility through its original history endpoints. Neither path retains a background password or automatically unlocks the wallet. Catchup covers unseen activity within the most recent 1,000 incoming outputs and 1,000 sends. The first valid history snapshot is a quiet baseline; old activity is not announced on first use. Deduplication retains 4,096 event identities per wallet and at most eight wallet scopes, so this is bounded best-effort notification delivery, not a complete historical alert service. Stopped Core, an unloaded wallet, disabled alerts or denied permission also prevent delivery. Incoming mempool activity is not exposed by either Core API.

All 18 checks in the emulator-only `-PnotificationIntegration` runner passed on API 30, testing synthetic notifications and channel configuration without using a wallet, sending funds, or playing a sound. All 20 checks also passed on the API 36 emulator, including the Android 13+ permission-denial branch. All 18 checks in `-PshortnameIntegration` passed, including a published signed public fixture and the optional `-e live true` HTTPS lookup of `$blocknet`. Neither runner starts Core.

## Core lifecycle decisions

The app sets the supplied core's `seed` setting so local wallets remain usable when bootstrap peers cannot be reached. Peer exchange remains active; peer count and sync state are shown explicitly. A node with no peers has not established that its chain is current. The app does not enable mining.

Wallet switching restarts the core rather than relying on `wallet/unload`, because the v0.22 baseline can retain a catch-up scanner after unload. Encrypted file export stops the process before copying to prevent concurrent writes. A forced stop or reported wallet-save failure aborts backup export. Keep the recovery phrase independently: the supplied core controls the encrypted wallet file format and disk writes.

Wallet changes are recorded durably before Core opens a file. If the response or activation fails, the app stops the node and blocks spending and backup export until an explicit successful reopen reconciles the selection. Imported encrypted backups are checked for a possible supported envelope before saving; their password and authentication are verified by Core when opened. Wallets imported from keys may have no recovery phrase and can use encrypted file export.

The shipped APK is a debug-signed development build. Physical ARM64 execution and funded payment acceptance must pass before calling the application fully verified; emulator UI screenshots are not proof of those paths.

For actual app-owned core/service verification on a physical ARM64 phone, use `scripts/test-device.sh PHYSICAL_ADB_SERIAL`. The explicit serial is mandatory so an attached emulator cannot be selected accidentally. The runner refuses a running app node, pending transactions, a selected non-mainnet network, or any existing testnet directory. It then creates a fresh disposable testnet directory and uniquely named empty wallets, exercises actual foreground node execution, bundled Mantle, creation/recovery, lock/unlock, message signatures, an insufficient-funds preview, view-only key import, process restart, and encrypted backup export/import. It never invokes the broadcast operation. Cleanup stops the core, removes only the newly owned testnet directory, restores preferences and Mantle config from memory, and leaves mainnet stopped. Existing mainnet wallets are not opened. Test credentials and exception text that could contain secrets are excluded from output. On 2026-10-15 all 22 physical-device integration checks passed on a Samsung Galaxy S22 (SM-S901U), Android 16/API 36, using the unchanged supplied ARM64 binaries. Post-test inspection confirmed no core/Mantle process or disposable testnet files remained. Mainnet remained stopped with the original preferences/config restored.

## Mainnet checkpoints and sync status

On October 15, 2026, all **26 mainnet checkpoint acceptance checks** passed on the Galaxy S22. They verified bundled seed validation and permissions, safe fallback when the official download timed out, actual ARM Core startup and authenticated checkpoint loading, repository status/cache agreement, live refresh/reload, retained chain progress, clean stop, and unchanged encrypted wallet files. This runner opens no wallet and broadcasts no transaction. Run it with `scripts/test-mainnet-checkpoints.sh PHYSICAL_ADB_SERIAL PREVIOUS_CHAIN_HEIGHT`; it requires the node to be stopped and leaves it stopped for normal app relaunch.

Mainnet startup immediately seeds `checkpoints.dat` from the validated checkpoint list bundled inside the APK, preserving any compatible newer local records, then asks Core to load it. The bundled official-pool snapshot contains **662 entries through block 66,200**; `assets/checkpoints-mainnet.provenance.json` records its source, retrieval time and SHA-256. Startup does not wait for an online download, so a newly created wallet can use the saved checkpoint list immediately.

While the node runs, a background task makes a best-effort online refresh from `https://bntpool.com/checkpoints.dat` at most once every 15 minutes. Downloads are validated and saved atomically before Core reloads them. An unavailable provider retains the last validated list and displays a warning; it does not block node startup. During verification on October 15, 2026, the provider accepted TLS promptly but its HTTP response timed out, so the bundled list provided the startup checkpoints. Node settings includes **Refresh checkpoints** for an explicit refresh and reload. This published checkpoint workflow applies to mainnet only.

Onboarding, the wallet network card and Node settings show the core-reported sync height, target and percentage, plus the checkpoint count/highest block. “Checkpoints loaded” appears only when the core confirms a positive loaded count; checkpoint fast sync and full validation are distinguished. These views update with the existing five-second status polling, including an open Node settings dialog. `screenshots/onboarding-sync.png` is an explicitly watermarked synthetic design fixture demonstrating the new-wallet sync display. No synthetic data is used by the running application.


## Explicit experimental Core activity build

The default APK continues to package the original supplied Core and Mantle. There is no automatic detection or selection of experimental binaries.

An additive locked-wallet activity API candidate can be selected explicitly with an **absolute** directory produced by `scripts/build-core-android.sh`:

```sh
cd android
./gradlew -PcoreActivityDir=/absolute/path/to/reviewed-build assembleDebug testDebugUnitTest lintDebug
python3 scripts/verify-apk.py --core-activity-dir /absolute/path/to/reviewed-build
```

The source build is recorded as experimental because the supplied binary includes an unavailable dirty source diff. The selected candidate passed nine ARM64 cryptography/activity tests and six synthetic funded-send/idempotency tests on the Galaxy S22, plus 25 offline original/candidate recovery, signature and encrypted-wallet round-trip checks. The Android client passed 24 real loopback activity checks and 60 API 36 UI smoke checks. Changed candidates require these compatibility checks before activation. It is not a byte-identical recreation of the original dirty build. Build prerequisites, the patch contract and source limitations are recorded in `core-patches/README.md` and the candidate's `build-provenance.json`.

The directory must contain `blocknet-core-arm64-android-experimental`, `build-provenance.json`, `Cargo.lock`, `applied.patch`, and `android-cgo-link-flags.patch`. Build and APK verification reject a different baseline commit, mismatched executable/lockfile hashes, or patch and Android-link-shim hashes that do not match the reviewed repository sources. The baseline is `5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e` from `https://github.com/blocknetprivacy/core`. Mantle remains byte-for-byte identical to the supplied executable.

An explicitly selected candidate is staged as `lib/arm64-v8a/libblocknet_core.so` with exact build metadata at `assets/core-activity-build-provenance.json`. It sets `BuildConfig.CORE_ACTIVITY_API=true` and `BuildConfig.CORE_VERSION="0.22.0-activity1"`. A normal build sets the feature flag to false, uses version `0.22.0`, removes generated experimental provenance, and restores the original Core. Neither option changes the `android-test` input payloads. The verifier requires the same explicit `--core-activity-dir` when checking an experimental APK; its default mode rejects experimental metadata or a different Core.

## Camera acceptance

The optional emulator-only `-PcameraIntegration` runner uses actual Camera API preview frames. API 30 passed 11 lifecycle checks (including releasing the camera to another owner and reacquiring it) and 11 permission-denial checks. Scanner teardown retains the SurfaceTexture until the camera thread releases its owner; system insets keep Cancel and permission controls above the navigation bar. All 12 payment-mode checks passed on API 36 with an image-file camera feed, exercising real camera callbacks, QR decoding, result preservation and recipient/amount/memo prefill without starting Core or sending funds. Initial virtual-scene fixtures were outside view; a padded image-file fixture positioned inside the preview crop provided the verified input. The camera mode is documented by [Android Emulator command-line options](https://developer.android.com/studio/run/emulator-commandline#common). Real phone autofocus, lighting and OEM background behavior remain device acceptance items.

## Activity build verification

`-PactivityIntegration` selects the emulator-only `WalletActivityInstrumentation` runner. Its 24 checks exercise actual loopback HTTP and Android JSON against synthetic locked activity, including wallet identity, per-page binding, scan readiness, truncated pages, metadata validation, authentication and unloaded/unsupported responses. It restores its cookie and port fixture and opens no wallet or node.

`test-core-compatibility.py` requires a prepared ARM Bionic chroot, root and a separate network namespace containing only loopback. It restores a public mnemonic in the supplied binary, loads and saves that encrypted wallet in the candidate, reloads it in the original, and compares independent candidate recovery and message signatures. The verified run used a separate mount namespace for temporary runtime-APEX, device and proc mounts; those mounts disappeared when the run ended. All 25 checks passed with no external routes, mining, funds or network broadcast.

### Installed activity build (October 15, 2026)

The locally installed Galaxy S22 APK uses the explicitly selected activity Core build (Core SHA-256 `60c8af25560aafab2c7f88faf22444e2606d84bed3abf57891be979d781a8640`). APK SHA-256 is `1e48f41d35462194af23f34b575623dfc9a4b8d2ef0b0137b820675cf86584a5`; the distributable and checksum are under the repository's ignored `builds/` directory. Startup verified mainnet height 66,333, `syncing=false`, two peers, the authenticated activity endpoint, and 662 loaded checkpoints through block 66,200. The user wallet remained unloaded and its encrypted file fingerprint was unchanged. The first user unlock after a node restart establishes the activity binding; spending and seed access stay locked when alerts continue.

A fallback APK using the supplied Core is retained as `builds/blocknet-wallet-1.0.0-supplied-core-debug.apk`. Both build modes retain the exact supplied Mantle. No real funds were sent, no user seed/password was accessed, and no testnet node was run during this continuation.

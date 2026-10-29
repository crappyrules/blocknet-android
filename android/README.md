# Blocknet native Android wallet

> [!CAUTION]
> **Moving from a debug-signed older preview? Verify recovery first.**
> `v1.0.0-preview.1` and earlier published previews used disposable signing
> certificates. Android rejects an in-place replacement signed with another
> certificate. **Do not uninstall that older wallet or clear its app data** to
> bypass the error. First restore, unlock, and check its receive address on a
> separate device or Android profile. Phrase-created wallets need the recovery
> phrase; wallets imported from spend/view keys have **no recovery phrase** and
> need the original keys or an encrypted wallet backup **and its password**.
> Losing every usable recovery copy can permanently lose access to the coins.
> The published `v1.0.0-preview.2` uses the owner certificate. A newer APK with
> the same package name and certificate can update it in place and retain data.

This is the standalone native Android application. The desktop Tauri application remains at the repository root. The app uses Android Views and Java 17, with Blocknet Core running as a child process of a foreground Android service. Core is bundled directly; no Mantle or Termux runtime is required.

## Build

Requirements: JDK 17 with `javac`, Android SDK platform 36 and build tools, Python 3, Go 1.26.5 or newer, a checkout of Core at `5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e`, and the SDK’s `aapt2` for packaged payload/resource verification. Set `ANDROID_HOME` or create an ignored `local.properties` containing `sdk.dir=/absolute/path/to/android-sdk`.

```sh
cd android
scripts/build-offline-wallet-view.sh /absolute/path/to/pinned-core /absolute/new/helper-build
scripts/build-core-android.sh /absolute/path/to/pinned-core /absolute/new/mobile-build \
  core-patches/0001-locked-wallet-activity.patch core-patches/0004-mobile-client-policy.patch
./gradlew -PcoreMobileDir=/absolute/new/mobile-build -PofflineWalletViewDir=/absolute/new/helper-build assembleDebug testDebugUnitTest lintDebug
python3 scripts/verify-apk.py --core-mobile-dir /absolute/new/mobile-build --offline-wallet-view-dir /absolute/new/helper-build
```

Or run `scripts/check.sh /absolute/new/mobile-build /absolute/new/helper-build` to perform the Gradle and APK checks together. Emulator/device test scripts take `CORE_MOBILE_DIR` and `OFFLINE_WALLET_VIEW_DIR`. The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it on an **ARM64 Android 8.0 or newer** phone:

```sh
adb -s PHYSICAL_ADB_SERIAL install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build is for development. Its local signing certificate does not match
the published owner-signed preview APK and cannot update that installation in
place. Distributable releases use the owner-controlled signing key configured
outside source control. Never commit signing keys, passwords or wallet backups.

## Bundled native executables

The build stages the pinned, patched mobile Core as `lib/arm64-v8a/libblocknet_core.so` and the separately pinned offline wallet reader as `libblocknet_walletview.so`. The `.so` suffix tells Android's package installer to extract these executables into the native library directory. The app invokes their absolute paths instead of loading them as libraries. No Mantle or legacy Core executable is bundled.

`extractNativeLibs`, legacy JNI packaging and `keepDebugSymbols` preserve the payload and make execution compatible with Android's restriction on launching executables from writable app storage. `verify-apk.py` compares the packaged Core byte for byte with the selected source build, checks ordered patch and lockfile provenance, rejects any Mantle payload, and checks the separately built offline wallet reader. It also verifies the bundled mainnet checkpoint asset and adaptive launcher resources. Every build requires an explicit `-PcoreMobileDir`; there is no fallback executable.

App-private storage holds node data and wallet files. Android backup is disabled. The local API uses loopback only; network security configuration permits cleartext HTTP solely for `127.0.0.1` and `localhost`. Node synchronization continues through a visible foreground notification. Camera access is optional and requested for scanning payment QR codes.

## Testing scope

Host tests and lint are run by the check script. Native Android instrumentation can be run on a clean emulator using `scripts/smoke-emulator.sh emulator-PORT` (requires `adb` on PATH or `ANDROID_HOME`; it also accepts `ANDROID_SERIAL` or discovers exactly one connected emulator and rejects physical phone serials). It verifies installed native payload hashes, screenshot protection, stopped onboarding layout, and that a payment deep link does not launch a node or create an authenticated core session without user confirmation. The test draws the non-sensitive stopped onboarding view into `screenshots/onboarding.png`; production screenshot protection stays enabled. Runtime testing requires an ARM64 Android device for the bundled executables. An earlier Core-and-Mantle build passed app-owned runtime and wallet-lifecycle checks on a Samsung Galaxy S22 (SM-S901U), Android 16/API 36, on 2026-10-15. The reviewed mobile candidate passed disposable offline/cache and foreground lifecycle checks on an S22 on 2026-10-18; funded-wallet migration and battery use remain unmeasured. An x86 emulator can validate the Java interface only if installed using an ABI override; it cannot establish that ARM64 Core executes correctly. Android's modern QEMU2 emulator does not boot an ARM64 system image on an x86 host.

Physical acceptance still needs camera scanning/permission denial, background and screen-off behavior, a full mainnet sync, and a funded transaction preview/broadcast. The verified disposable testnet pass covered creation/recovery, lock/unlock, message signing, unfunded dry-run rejection, view-only import, core restart, and encrypted backup export/import. A complete mainnet sync also depends on available storage, network connectivity and peers.

## Verification performed

The reviewed mobile candidate passed 126 Android host unit tests, lint, APK payload and provenance checks, and disposable S22 offline/lifecycle checks on 2026-10-18. The API 30 x86 emulator previously rendered the native Android interface with an ARM64 ABI override; that does not test execution of ARM64 Core.

The latest host gate passes 126 unit tests, including encrypted address-book persistence, migration, key loss, corruption, stale selection and edit rejection, fair name-check attempts, shared request budgets, and lookup cancellation tokens and deadlines, plus biometric scope/encoding validation, sync network decisions, checkpoint validation, offline file binding, signed announcement parsing, pending manifest retries, manifest scope, and legacy/versioned encrypted backup envelope checks. An earlier baseline passed 60 emulator smoke checks covering startup readiness redraw, history pagination invalidation when new transactions arrive, confirmation/status refresh, recovery/configuration controls, and payment links that cannot start a node or submit a payment. Those checks used synthetic history and no funds. The user reported that the ordinary address-book save, reopen, offline view, recheck, and send-preview/cancel flow worked in the isolated S22 sidecar; changed-name, slow-network, and funded-send paths remain untested on device.

Nine additional emulator checks inject an activation failure through an in-process loopback API stub, confirm that the durable wallet identity marker blocks backup and sending, then confirm a successful activation clears it. They start no Core process and create no wallets or transactions. Build the optional test runner with `./gradlew -PofflineWalletViewDir=/absolute/new/helper-build -PidentityIntegration assembleDebug assembleDebugAndroidTest`, install its app and test APKs on an emulator, then run `adb -s EMULATOR_SERIAL shell am instrument -w com.blocknet.wallet.test/com.blocknet.wallet.WalletIdentityInstrumentation`.

The `overview.png`, `receive.png` and `settings.png` screenshots in `screenshots/` are **design fixtures with synthetic wallet data**, clearly watermarked by the instrumentation test. They demonstrate layout and QR rendering, not live balances, synchronization or payment execution. `onboarding.png` shows the actual stopped app. The optional exact ARM core harness is documented in [scripts/ARM_CORE_TESTING.md](scripts/ARM_CORE_TESTING.md).

## Wallet rescan and indicative price candidate

The Settings **Rescan wallet** action now asks the authenticated local Core to
rewind its saved wallet scan cursor and scan the local chain again. For a newly
generated wallet, Core records a birth height only when a recent connected peer
comparison confirms the tip and no chain catch-up is active. Rescan starts 100
blocks before that height to cover permitted reorganizations. Restored,
imported, older, and unconfirmed-tip wallets scan from genesis. The rewind is
saved before Core acknowledges it; the wallet scan height and an incomplete
status are shown in the app. This does not claim that the local chain is at the
network tip. A locked wallet cannot scan outputs; background chain jobs can
advance the chain, but discovering wallet outputs resumes after unlock. The
existing bounded screen-off scan lease remains in place and a long first scan
may need later unlocked sessions. The app does not store a password for
unattended scanning.

While a mainnet wallet is visible and unlocked on a permitted connection, the
app requests the public SafeTrade `bntusdt` ticker roughly every ten seconds.
It shows the exchange's last trade in **USDT** and an approximate available
balance value. The ticker does not include a trade timestamp; fetch time is
not trade time. The app sends no wallet balance, address, key, or identifier to
the exchange. Polling stops when the activity leaves the foreground or the
wallet locks, and failures back off. This feature has no funded-wallet or
physical-device acceptance result yet.

For Android-to-core transport integration, start the isolated ARM testnet core described in [scripts/ARM_CORE_TESTING.md](scripts/ARM_CORE_TESTING.md), leave its wallet unloaded, then run `scripts/test-android-transport.sh /readable/path/to/api.cookie 18392 ADB_SERIAL`. The target may also come from `ANDROID_SERIAL`; automatic discovery requires exactly one authorized device and otherwise refuses to proceed. This selects the optional `-PapiIntegration` runner, forwards loopback through `adb reverse`, provisions the token without logging it, creates an empty encrypted test wallet, checks status/authentication/balance/message signing, unloads it, and removes the copied token. Seven checks passed against the exact supplied ARM core on 2026-10-15. It does not test app-owned ARM service execution or a funded broadcast.


## Wallet features

- Create an encrypted wallet, recover a 12-word phrase, import private keys or a view-only account, and switch between local wallet files.
- Read spendable and pending balance, scan progress, incoming outputs, and paginated outgoing transactions.
- Preview payment fees, inspect resolved handle destinations, explicitly confirm sends, and reconcile interrupted requests with persisted idempotency keys.
- Generate black-on-lime address and payment QR codes with the Blocknet logo, scan payment requests with the device camera, and handle `blocknet:` links as prefilled forms.
- Send to `$shortname` or `@handle` with signed Blocknet ID resolution and an explicit destination review. Save a verified preferred receive shortname, with full-address fallback.
- Save recipients in a local address book by full address or verified `$shortname`/`@handle`; select a saved full address from the send form.
- Open history transaction hashes in the Blocknet explorer.
- Confirm recovery backups, export/import encrypted wallet files through Android's document picker, lock on screen-off or after 30 seconds away, and sign or verify messages.
- Start/stop the mainnet node, inspect authenticated Core diagnostics and local logs, and rescan a wallet.

The native interface follows [blocknetcrypto.com](https://blocknetcrypto.com/): black background, lime accents, monospace typography, and restrained outlined panels.

### Address book in next-version source

The address book is kept in an AES-256-GCM encrypted, versioned file in
app-private no-backup storage, separated by mainnet/testnet. Its dedicated,
non-exportable Android Keystore key is separate from wallet and biometric keys.
It requires no additional prompt after normal wallet unlock; wallet UI access
still requires unlock. The key is device-bound, and Android Keystore does not
guarantee hardware backing on every device. The encrypted file and its key
survive a same-signer app update, but they are not a transferable contact backup:
uninstall, clear data, or device replacement can permanently lose the address
book. If the key is missing or the file is damaged, the app retains the file
and shows an error instead of silently resetting contacts. A valid pre-release
plaintext file is checked and atomically replaced with encrypted storage.
This protects copied app files; it does not protect contacts from an unlocked
app process or a compromised device. Adding a shortname
uses the existing signed forward resolver to
store its full address. Adding a full address validates its format, checksum
where present, and public keys through the local Core; a known shortname can be
entered separately and verified. The integrated service exposes no confirmed
reverse address-to-name lookup, so the app does not guess a name or submit a
full address to a remote reverse service. Core retains support for legacy
checksum-free addresses, with full public-key validation before saving.

A saved address never changes automatically when a name is reassigned or
disappears. Last check times are shown as historical, and a saved name is
rechecked before a send. A mismatch, missing name, or unavailable resolver
requires an explicit decision to use the saved full address; payment preview
and broadcast both use that full address. A live unlock can check at most two
stale saved names per network once per 24 hours. This is nonblocking, skips
offline unlocks, and uses the same permitted connection as other name lookups.
The signing public key is fetched from the same Blocknet ID HTTPS origin; the
signature checks response integrity against that service key, not an independent
third-party identity assertion. Each name check makes one HTTPS resolve request and may make one additional
signing-key request. The automatic batch stops on an unavailable lookup, so it
makes at most four HTTPS requests per network per day, with each response
capped at 128 KiB (up to 512 KiB of response bodies plus connection overhead).
The lookup code sets a 12-second total deadline and disconnects active HTTPS
requests on app backgrounding, wallet lock, or loss of the permitted network.
Guard tests pass, but slow real HTTPS streaming and lifecycle cancellation still
need phone verification. Failed automatic checks
record an attempt time and rotate behind other stale names, with a six-hour
per-name retry backoff; the shared daily reservation remains in effect.
Full-address-only
contacts are never queried remotely. Manual checks and send-time checks are
additional user actions. These source changes have not been published as an
owner-signed release.

## Language

The wallet follows the phone language by default and includes bundled English
and Simplified Chinese text. **Settings → Language / 语言** also lets you choose
phone language, English, or 简体中文; the choice is available on the locked and
first-run screens. Switching languages locks the wallet, so unlock it again
to continue. The Chinese resources work offline, and English is used when a
translation is unavailable. Amount input keeps the Core's period-decimal
format and BNT units. Addresses, shortnames, keys, and recovery words are
never translated. The initial Chinese wording still needs review by a fluent
human before publication.

## Sync connection preference

**Node settings → Allow mobile data for sync** is available before opening a wallet; the same switch is in wallet Settings. It defaults to **off (Wi-Fi only)**. Turning it on also permits mobile data. The app shows the current default connection and monitors Android network changes, including when the wallet is in the background. It checks the active route's Wi-Fi/mobile transport and internet validation, rather than treating any nearby or connected Wi-Fi as the route in use. VPNs are accepted in Wi-Fi-only mode only when Android reports Wi-Fi without cellular underneath; unknown VPN routes wait for a verifiable connection.

When a requested sync loses an allowed connection, the foreground service signals the owned Core to shut down, confirms it exits, and waits with a visible paused notification. It automatically starts Core when a permitted connection returns. Stop sync cancels the waiting session and scheduled chain checks. The mobile Core can start offline; the app still gates peer traffic on the permitted network. Graceful shutdown may take several seconds; this preference is not an operating-system firewall or a guarantee of zero bytes during network handover.

In the isolated background-sync candidate, a user-started catch-up may continue after the app backgrounds until Core confirms a connected peer tip. Once the first wallet scan is complete, a loaded Core is stopped and restarted without loading the encrypted wallet before chain-only catch-up continues. The default setting requests best-effort chain checks from Android JobScheduler with a 15-minute minimum repeat interval after Core stops. Android may delay or interrupt jobs. The scheduled job persists across reboot and rechecks the saved Start/Stop choice and permitted network before starting Core; it does not start a foreground service at boot. A scheduled check never opens the encrypted wallet, so saved balances and payment alerts remain dated until the user opens and unlocks it. A large backlog may take several jobs to clear; repeated Core startup cost and battery use need physical measurement. Turning off scheduled background sync retains the existing continuous, user-started foreground service. This candidate has host-test coverage but no phone acceptance or release approval.

An existing wallet file can be opened without a previously saved app snapshot. A separate short-lived helper built from the pinned Core wallet package authenticates the password and reads only saved display fields without starting DNS, P2P, a listener, sync, or broadcasting. The password passes through a private stdin pipe, not command arguments. Unsupported wallet formats and wrong passwords fail closed. Funded-wallet migration remains untested.

After a successful wallet unlock, the app saves an encrypted view of that wallet's balance, address, saved height, and recent activity in private app storage. If the node is paused or unavailable, a cold launch offers password or enrolled biometric unlock without requiring Wi-Fi or mobile data. The view is explicitly dated using the wallet file's modification time or the last live snapshot time and may be stale; sending, shortname verification, chain refresh, and other node operations require reconnecting and unlocking the node. A fresh install with no wallet file shows no invented balance. The saved view uses Argon2id password derivation and authenticated encryption, is scoped to the selected wallet and network, and its in-memory key is cleared when the app leaves the screen or the wallet locks. It never stores spending keys or recovery words.

### Software update checks

Builds without a configured on-chain announcement channel check the public [Blocknet Android GitHub releases](https://github.com/crappyrules/blocknet-android/releases) approximately once per day while installed and active, with a periodic Android job and a check when the app next opens. These legacy listings are labeled **unverified**. Requests use the same validated default-network policy as sync and are bound to the permitted Android `Network`: Wi-Fi-only mode never starts a GitHub request over cellular. Offline checks defer until a permitted connection is available; failed requests back off for six hours. Only the check time, release tag, notes and official release-page URL are saved locally. No wallet data or credentials are sent.

When the owner configures the [signed on-chain update channel](docs/ONCHAIN_UPDATES.md), the app suppresses legacy GitHub polling and cached GitHub notices. Its background worker scans current mainnet blocks over a permitted connection, persists signed candidates with the cursor, and retries unavailable manifests without blocking later notices. A full pending inbox pauses cursor progress; corrupted inbox storage triggers a replay from the configured birth height without resetting the finalized sequence floor. Settings can request a bounded scan. The signed memo authenticates the announcement and manifest; opening the official release page does **not** authenticate the APK found there.

The update action opens a confirmed browser handoff to the exact official repository release page after checking that the release has this build's expected APK variant. It does **not** download, authenticate, or install an APK inside the wallet. The older debug-signed previews cannot be replaced in place by the owner-signed `v1.0.0-preview.2` APK. Future APKs with the same package name and owner certificate can update preview.2 in place when their version code is higher. Tag pushes cannot publish a release; owner signing and publication are separate manual approval gates.

### Release signing gate

The [GitHub Actions owner-signing handoff](docs/CI_RELEASE.md) describes the
manual protected signing job, required environment setup, and separate
publication gate. Normal CI and tag pushes upload debug test artifacts only.

`assembleRelease` requires the owner to supply `BLOCKNET_RELEASE_KEYSTORE` (an absolute path outside source control), `BLOCKNET_RELEASE_STORE_PASSWORD`, `BLOCKNET_RELEASE_KEY_ALIAS`, `BLOCKNET_RELEASE_KEY_PASSWORD`, and `BLOCKNET_RELEASE_CERT_SHA256`. Gradle checks that the selected alias exists and its certificate matches the pinned SHA-256 before it can package a release. Missing or mismatched values fail the build; a release build never uses a disposable debug key. Normal CI receives none of the owner secrets. The manual CI signing job receives them only from its protected environment. Do not put them in Git, command-line arguments, logs, or artifacts. The local script prompts for the keystore passphrase; the owner certificate does not match the older debug-signed previews.

The owner Ed25519 announcement signer, Android release keystore, and dedicated mainnet announcement channel were generated locally outside Git. The signer's public key, deliberately public channel view key, channel spend public key, channel birth height, and release certificate are pinned in `app/build.gradle`; the release certificate SHA-256 is `4a307e802e3504f7850c546ab8b38c292211ea5d5c5be3342107edaec1255fff`. The channel spend private key is outside Git. The local owner signing script reads the channel's public config and prompts for the keystore passphrase in a terminal. The owner-signed `v1.0.0-preview.2` APK was verified and published. No on-chain announcement has been broadcast, and a migration from earlier debug certificates has not been verified on a funded wallet.

Checkpoint downloads follow the same preference and use `Network.openConnection` on the checked network so a Wi-Fi download cannot silently fall back to cellular. A connection change or Stop cancels the active download; the last validated checkpoint file is retained.

Six host unit tests cover Wi-Fi, cellular, VPN, mixed/unknown transports, unavailable or unvalidated internet, and mobile opt-in decisions. All 14 live network policy checks passed on the x86 emulator, including blocked start, manual stop, mobile opt-in on the unchanged cellular connection, and automatic resume attempts when Wi-Fi returns. The optional `-PnetworkIntegration` runner (`WalletNetworkInstrumentation`) refuses physical devices and restores radio settings afterward. The UI smoke runner also covers the connection controls. These emulator checks prove policy/service transitions; actual ARM phone network handover still requires device acceptance.

## Recovery phrase backup and restore

Use **Settings → Show recovery phrase** (wallet password required) to view the 12 words and confirm an offline written backup. New wallet creation also offers this backup flow. Restore with **Restore recovery phrase** during setup, or **Switch / add wallet → Create / restore another wallet → Restore phrase**. Enter the words in order and choose a new wallet password. Restore shows tappable local completions from the exact 2,048-word BIP39 English list in the pinned Core baseline. Suggestions begin with the first letter (for example `p` includes `pond`); scroll the row or type more letters to narrow it. Tapping replaces only the current word and advances to the next. The entered phrase stays masked, and suggestions clear when the field loses focus or its sensitive dialog closes. No recovery text is sent to a suggestion service or saved as keyboard history. Core still validates the final 12-word phrase and checksum. Encrypted wallet file export/import is also available; wallets imported directly from keys may not contain a recovery phrase.

## Optional biometric unlock

Open an unlocked wallet's **Settings → Biometric unlock for this wallet** to enable it. Verify the wallet password, then authenticate in Android's biometric prompt. The app encrypts the password with a non-exportable Android Keystore AES-256-GCM key that requires strong biometric authentication for each operation. Only ciphertext, its IV and the scoped key alias are stored in private no-backup storage. Keys and authenticated data bind each entry to its wallet filename and network. Core still receives its normal wallet password after an authorized decryption; biometrics do not change the wallet's encryption format.

Use **Unlock with biometrics** on the locked screen, existing-wallet setup screen, or wallet chooser. The password remains available as a fallback. Seed reveal continues to require the wallet password. Disabling biometric unlock deletes its encrypted credential and key. Enrollment changes invalidate the biometric key; use the wallet password and enable biometrics again. Backgrounding the app cancels prompts, clears sensitive entry fields and in-memory password buffers, and prevents a late callback from leaving the wallet unlocked.

The optional feature requires Android 9/API 28 or newer and enrolled strong biometrics with a secure device screen lock. Android 8 remains supported with password unlock. Android 9/10 use the enrolled fingerprint compatibility path; newer versions request `BIOMETRIC_STRONG` explicitly. Weak face unlock is not used.

All 18 checks in the optional `-PbiometricIntegration` runner (`WalletBiometricInstrumentation`) passed with real Android Keystore keys and biometric CryptoObjects using synthetic credentials on an emulator, without starting Core, creating a wallet, or using funds. It tests actual background password-dialog dismissal/input clearing, authentication gating, exact secret recovery, scope binding, deletion and cancellation. The temporary emulator PIN and fingerprint enrollment were removed afterward, restoring the original no-lock/no-fingerprint state. A real fingerprint unlock on the physical phone still requires user acceptance.

## Password masking

Password and recovery phrase input fields always mask every character, including the most recently typed character. The implementation applies a strict transformation after configuring single-line or multiline input, so Android layout configuration and the system “show passwords” preference cannot reveal the text. The emulator regression first reproduced the masking failure and then verified the corrected behavior for both field types.

## Shortnames and receive QR

Send accepts `$name` and `@name`; the mobile Core verifies the Blocknet ID signature and the review screen shows the resolved address before confirmation. Receive offers **Preferred shortname** with Verify & save and Clear shortname. Saving requires a valid Ed25519-signed response from `https://blocknet.id` whose address matches the active wallet. Preferences are scoped to wallet and network. A saved name is checked again when entering Receive; the full address remains the QR destination until verification succeeds. Runtime verification expires after five minutes. The full address remains visible and selectable; a separate full-address copy action remains available when a shortname is active. Both address and payment-request QR codes use high error correction, a four-module quiet zone and a small centered Blocknet logo.

History hashes open `https://explorer.blocknetcrypto.com/tx/<txid>` through Android's browser handler. Only complete 64-character hexadecimal transaction IDs can create explorer links.

## Transaction alerts

Settings offers **Transaction alerts** (on by default) and optional sound (off by default), plus Android notification settings. Android 13+ requires notification permission. Separate silent and sound channels let Android retain each channel's settings. Private notifications show the payment amount; their public lockscreen version only says “New wallet activity.” Accepted outgoing payments can notify immediately; a separate worker checks new confirmed wallet activity every 15 seconds while the node runs.

**The mobile Core supports alerts while a loaded wallet is locked.** After opening the wallet once following a node restart, an authenticated read-only activity endpoint continues reporting new confirmed outputs and sends. The app binds every page to the public-address identity established while unlocked and waits for the wallet scan to catch up. The mobile activity endpoint is bound to the loaded wallet identity and requires no background password. Neither path retains a background password or automatically unlocks the wallet. Catchup covers unseen activity within the most recent 1,000 incoming outputs and 1,000 sends. The first valid history snapshot is a quiet baseline; old activity is not announced on first use. Deduplication retains 4,096 event identities per wallet and at most eight wallet scopes, so this is bounded best-effort notification delivery, not a complete historical alert service. Stopped Core, an unloaded wallet, disabled alerts or denied permission also prevent delivery. Incoming mempool activity is not exposed by either Core API.

All 18 checks in the emulator-only `-PnotificationIntegration` runner passed on API 30, testing synthetic notifications and channel configuration without using a wallet, sending funds, or playing a sound. All 20 checks also passed on the API 36 emulator, including the Android 13+ permission-denial branch. All 18 checks in `-PshortnameIntegration` passed, including a published signed public fixture and the optional `-e live true` HTTPS lookup of `$blocknet`. Neither runner starts Core.

## Core lifecycle decisions

The mobile Core can start offline without acting as a seed or listening for peers. Peer exchange remains active; peer count and sync state are shown explicitly. A node with no peers has not established that its chain is current. The app does not enable mining.

Wallet switching restarts the core rather than relying on `wallet/unload`, because the v0.22 baseline can retain a catch-up scanner after unload. Encrypted file export stops the process before copying to prevent concurrent writes. A forced stop or reported wallet-save failure aborts backup export. Keep the recovery phrase independently: Core controls the encrypted wallet file format and disk writes.

Wallet changes are recorded durably before Core opens a file. If the response or activation fails, the app stops the node and blocks spending and backup export until an explicit successful reopen reconciles the selection. Imported encrypted backups are checked for a possible supported envelope before saving; their password and authentication are verified by Core when opened. Wallets imported from keys may have no recovery phrase and can use encrypted file export.

The shipped APK is a debug-signed development build. Physical ARM64 execution and funded payment acceptance must pass before calling the application fully verified; emulator UI screenshots are not proof of those paths.

For actual app-owned core/service verification on a physical ARM64 phone, use `scripts/test-device.sh PHYSICAL_ADB_SERIAL`. The explicit serial is mandatory so an attached emulator cannot be selected accidentally. The runner refuses a running app node, pending transactions, a selected non-mainnet network, or any existing testnet directory. It then creates a fresh disposable testnet directory and uniquely named empty wallets, exercises actual foreground node execution, direct Core diagnostics, creation/recovery, lock/unlock, message signatures, an insufficient-funds preview, view-only key import, process restart, and encrypted backup export/import. It never invokes the broadcast operation. Cleanup stops the core, removes only the newly owned testnet directory, restores preferences, and leaves mainnet stopped. Existing mainnet wallets are not opened. Test credentials and exception text that could contain secrets are excluded from output. For the earlier Core-and-Mantle build, on 2026-10-15 all 22 physical-device integration checks passed on a Samsung Galaxy S22 (SM-S901U), Android 16/API 36, using the unchanged supplied ARM64 binaries. Post-test inspection of that earlier build confirmed no Core/Mantle process or disposable testnet files remained. Mainnet remained stopped with the original preferences/config restored.

## Mainnet checkpoints and sync status

On October 15, 2026, all **26 mainnet checkpoint acceptance checks** passed on the Galaxy S22. They verified bundled seed validation and permissions, safe fallback when the official download timed out, actual ARM Core startup and authenticated checkpoint loading, repository status/cache agreement, live refresh/reload, retained chain progress, clean stop, and unchanged encrypted wallet files. This runner opens no wallet and broadcasts no transaction. Run it with `scripts/test-mainnet-checkpoints.sh PHYSICAL_ADB_SERIAL PREVIOUS_CHAIN_HEIGHT`; it requires the node to be stopped and leaves it stopped for normal app relaunch.

Mainnet startup immediately seeds `checkpoints.dat` from the validated checkpoint list bundled inside the APK, preserving any compatible newer local records, then asks Core to load it. The bundled official-pool snapshot contains **662 entries through block 66,200**; `assets/checkpoints-mainnet.provenance.json` records its source, retrieval time and SHA-256. Startup does not wait for an online download, so a newly created wallet can use the saved checkpoint list immediately.

While the node runs, a background task makes a best-effort online refresh from `https://bntpool.com/checkpoints.dat` at most once every 15 minutes. Downloads are validated and saved atomically before Core reloads them. An unavailable provider retains the last validated list and displays a warning; it does not block node startup. During verification on October 15, 2026, the provider accepted TLS promptly but its HTTP response timed out, so the bundled list provided the startup checkpoints. Node settings includes **Refresh checkpoints** for an explicit refresh and reload. This published checkpoint workflow applies to mainnet only.

Onboarding, the wallet network card and Node settings show the core-reported sync height, target and percentage, plus the checkpoint count/highest block. “Checkpoints loaded” appears only when the core confirms a positive loaded count; checkpoint fast sync and full validation are distinguished. These views update with the existing five-second status polling, including an open Node settings dialog. `screenshots/onboarding-sync.png` is an explicitly watermarked synthetic design fixture demonstrating the new-wallet sync display. No synthetic data is used by the running application.


## Pinned mobile Core build

Every APK uses the pinned upstream Core commit plus ordered patches 0001–0011.
Build `blocknet-core-arm64-android-experimental` in an isolated output directory
with `scripts/build-core-android.sh`, then pass that directory through
`-PcoreMobileDir` to Gradle and `--core-mobile-dir` to `verify-apk.py`. The
verified executable is packaged as `lib/arm64-v8a/libblocknet_core.so`, and
its exact provenance is packaged at `assets/core-mobile-build-provenance.json`.
Gradle rejects a missing mobile build or a retired activity-only build. The
release workflow rebuilds Core from the pinned commit and checks the source,
ordered patches, Cargo lock, executable bytes, and APK contents.

The mobile profile has no P2P listener and does not serve chain data or peer
lists. It still fetches blocks, receives status/new-block messages, and retains
Dandelion++ transaction forwarding. Foreground-only sync is the default;
backgrounding stops Core gracefully, and returning to the app restarts it.
This can delay alerts and incur a full-chain ring-index rebuild. Battery benefit
has not been measured on a synced phone.

## Camera acceptance

The optional emulator-only `-PcameraIntegration` runner uses actual Camera API preview frames. API 30 passed 11 lifecycle checks (including releasing the camera to another owner and reacquiring it) and 11 permission-denial checks. Scanner teardown retains the SurfaceTexture until the camera thread releases its owner; system insets keep Cancel and permission controls above the navigation bar. All 12 payment-mode checks passed on API 36 with an image-file camera feed, exercising real camera callbacks, QR decoding, result preservation and recipient/amount/memo prefill without starting Core or sending funds. Initial virtual-scene fixtures were outside view; a padded image-file fixture positioned inside the preview crop provided the verified input. The camera mode is documented by [Android Emulator command-line options](https://developer.android.com/studio/run/emulator-commandline#common). Real phone autofocus, lighting and OEM background behavior remain device acceptance items.

## Activity build verification

`-PactivityIntegration` selects the emulator-only `WalletActivityInstrumentation` runner. Its 24 checks exercise actual loopback HTTP and Android JSON against synthetic locked activity, including wallet identity, per-page binding, scan readiness, truncated pages, metadata validation, authentication and unloaded/unsupported responses. It restores its cookie and port fixture and opens no wallet or node.

`test-core-compatibility.py` requires a prepared ARM Bionic chroot, root and a separate network namespace containing only loopback. It restores a public mnemonic in the supplied binary, loads and saves that encrypted wallet in the candidate, reloads it in the original, and compares independent candidate recovery and message signatures. The verified run used a separate mount namespace for temporary runtime-APEX, device and proc mounts; those mounts disappeared when the run ended. All 25 checks passed with no external routes, mining, funds or network broadcast.

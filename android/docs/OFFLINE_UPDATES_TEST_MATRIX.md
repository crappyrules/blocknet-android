# Offline wallet and update-check acceptance matrix

Published preview.2 baseline checks on 2026-10-16: `testDebugUnitTest` (68 tests), pinned Go helper tests (2 tests), `lintDebug`, `assembleDebug`, and `scripts/verify-apk.py` passed. The prepared `v1.0.0-preview.3` source gate on 2026-10-17 previously passed 90 Android unit tests, lint, both debug Core builds, APK payload checks, and Core public-key address validation tests. The localized source passes 98 Android unit tests, 13 Python checks, lint, an activity-Core debug APK and language instrumentation APK build, and APK payload verification. S22 sidecar instrumentation passed English, Chinese, System fallback, exact amount/address values, app-locale dates, and persisted Chinese/English/System choices across processes. Visible UI rendering and switching remain untested while the phone is locked. Isolated synthetic Keystore instrumentation passed on the S22 across a same-signer sidecar update. The user reported success for the ordinary address-book phone checklist below. Other rows marked **Device** still need their specific device checks.

| Scenario | Expected result | Evidence / remaining check |
| --- | --- | --- |
| Phone language, in-app selection, and fallback | Simplified Chinese is selected for a Chinese phone locale; English remains the default/fallback; switching language locks the wallet, and wallet identifiers, recovery words, and amounts keep their exact values. | Generated catalog and placeholder checks, amount unit test; **S22 sidecar instrumentation passed** resource resolution, exact amount/address values, date locale, and persisted Chinese/English/System choices. **Device** visible locked/setup/settings rendering, selector taps, wallet lock on change, and fluent human wording review remain. |
| Language save fails after wallet lock | Sensitive balance/history/address content is removed before saving the locale; a failed save leaves a redacted view and does not recreate the activity. | `LanguageChangeFlowTest` covers failed and successful save order, and the source guard checks UI redaction before the save. **Device** fault injection remains. |
| Wi-Fi to disallowed cellular while wallet is open | Core sync pauses; authenticated view switches to dated saved values; no send or shortname lookup. | Route, cache crypto, and network policy unit tests; **Device** radio handover and UI. |
| Wi-Fi handover during a wallet API read | A response from the old node session is discarded when sync becomes disallowed, the node stops, or the wallet/network identity changes. The UI requests a fresh local view after the network callback. | `WalletRepositoryOfflineTransitionTest` covers the final policy/session guard; 2026-10-17 debug unit tests, lint, APK build and payload verification passed. **Device** in-flight handover and stale-banner timing. |
| Cold launch on disallowed cellular with saved wallet | Offline password/biometric unlock appears before node startup; correct authentication opens saved view, wrong password fails. | Routing and authenticated-encryption unit tests; **Device** cold launch, biometric, wrong password. |
| Existing wallet with no app snapshot, no permitted network | Password/biometric unlock appears; the Core-derived reader opens the encrypted wallet file without a networked node and displays its dated saved balance and activity. The wallet's last scanned height is shown without inventing a current chain height. A wrong password shows no data. | Route unit test and Go helper synthetic-wallet tests; **Device** real existing wallet compatibility and no-network observation. |
| Fresh install without a wallet file | Stopped-node screen shows no fabricated balance or unlock. | Routing unit test; **Device** fresh install. |
| Reconnect to Wi-Fi | Core resumes if sync was requested; saved view stays marked stale until Core is ready and separately unlocked; live refresh replaces it. | Source flow review; **Device** reconnection, unlock, balance comparison. |
| Wallet lock or screen off | In-memory cache key is cleared; saved view requires authentication again. | Source flow review; **Device** lock, background, screen off. |
| Wallet password changes in Core | The old saved view is invalidated, old biometric enrollment is disabled, and the next successful live refresh writes a view under the new password. | Source flow review; **Device** password rotation, old password and biometric rejection, new password success. |
| Wallet file replaced or password changed outside the app | A saved view whose encrypted wallet file digest changed is locked. The local reader must authenticate the current wallet file before showing balances or replacing the view. | `WalletFileBindingTest` covers same-length replacement and missing file; **Device** external file replacement and password rotation. |
| Local reader fails or times out | The reader process is forcibly stopped and awaited for up to three seconds; no partial view is shown. | `OfflineWalletReaderTest` covers live and already exited process cleanup; **Device** ARM64 failure and timeout injection. |
| Interrupted saved-view write | The previous complete encrypted view opens, or the damaged view is rejected; no partial balances are shown. | Android `AtomicFile` read/write path review; **Device** fault-injection run. |
| Automatic daily release check | At most one successful check in 24 hours; failed request backs off six hours; no request or attempt timestamp while policy blocks network. | `SoftwareUpdateCheckerTest` throttle tests; **Device** JobScheduler timing and radio transitions. |
| Update check during Wi-Fi to blocked cellular handover | The request remains bound to the permitted network and fails if that network stops being allowed; no cellular retry occurs. | `Network.openConnection` and policy checks review; **Device** radio handover while response streams. |
| Manual check, no update, new preview | Settings shows check result; a strictly newer official tag with the expected Core-variant APK shows notes and a release-page action. | Release parser unit tests; **Device** Settings rendering. |
| Malformed GitHub response, missing/foreign asset or URL | Reject result; keep last known valid update and retry after backoff. | Release parser unit tests; **Device** network error behavior. |
| Configured signed channel with an older cached GitHub result | Do not poll GitHub or display its unsigned cache as an update; only an accepted signed notice appears. | Update-source policy unit test; **Device** mode transition. |
| Signed memo with 404 or malformed manifest | Save memo and scan cursor together, retry a bounded pending queue, and keep scanning later notices; only a valid manifest advances acceptance. | Durable queue/restart and signed-record unit tests; **Device** interrupted fetch and reorg. |
| Update action | User confirms opening the exact official release page; the app does not download, install, or request unknown-source permission. | Source review; **Device** browser handoff and cancellation. |
| Preview APK replacement | A debug-signed preview.1 or older APK cannot update in place to owner-signed preview.2. Keep the old app until a separate recovery is verified. A newer APK with the same package name and owner certificate can update preview.2 in place. | Certificate comparison and signing-gate negative test; **Device** legacy wallet migration remains unverified. |
| Ordinary address-book flow in isolated sidecar | Save the user's own full address with a known shortname, reopen and view the saved address offline, recheck after reconnect, and cancel at send preview without broadcasting. | **User-reported S22 success** on 2026-10-17; no independent capture or transaction broadcast. This does not establish changed-name, slow-response, or funded-wallet behavior. |
| Saved recipient edited after selection or while its edit form is open | Send review and final submit reject the old selection; edit save compares the revision captured when the form opened and cannot overwrite a newer edit. | Selection and stale-edit unit tests, send-path source review; **Device** two-activity edit during review. |
| Stale or unavailable shortname | Saved full address never changes; the user must explicitly choose whether to use it after a changed, missing, or unavailable mapping. | Address-book mapping and failed-attempt unit tests; **Device** send-preview dialog without broadcast. |
| Repeated failed automatic name check | A failed attempt records its time, waits at least six hours, and rotates behind other stale names. At most two names per network are attempted in one daily batch. | Fairness and backoff unit test; **Device** intermittent resolver failure. |
| Multiple activity instances request checks | A process-shared lock and durable per-network reservation admit one automatic batch per 24 hours. Manual and send-time checks are separate user actions. | Concurrent reservation unit test; **Device** rapid activity recreation. |
| Slow response, lock, or disallowed network | The code sets a 12-second total lookup deadline and disconnects active HTTPS; lock, app backgrounding, and route changes invalidate queued lookup tokens before resolver startup. | Deadline and queued-token guard unit tests; **Device** real slow `HttpsURLConnection`, lifecycle cancellation, and radio handover remain unverified. |
| Address-book file privacy and migration | Contacts use a versioned AES-GCM file and a separate device-bound Android Keystore key. A valid pre-release plaintext file migrates atomically; missing key, corruption, or failed migration retains the file and fails closed. UI access still requires wallet unlock, with no new prompt. | Crypto and storage unit tests; isolated S22 Keystore encryption, restart, migration, corruption, failed write and same-signer update **passed** with synthetic data. UI lock-screen and backup behavior remain untested. |

On 2026-10-17 the isolated user-0 `com.blocknet.wallet.offlinetest`
sidecar was updated in place to the localized preview.3 test build. Its debug
certificate SHA-256 remained
`2752b74ccd5b7eaf0f81379d833742612e78a9e6565e4a313ddaacf8c979d38c`,
version code remained 3, and its data directory inode remained `65082:462457`.
Only the sidecar and its test package were installed; no main-wallet package
was modified and no wallet was unlocked. Its running test Core stopped during the update
and was not restarted. The sidecar language was restored to its original
System choice (empty Android app-locale override). The S22 keyguard prevented
visible UI inspection; no screen image or wallet password was captured.

This matrix was recorded before the owner-signed preview.2 publication. The
new helper is built from pinned Core source, while the supplied Core binary
has unavailable dirty source changes. An unfunded S22 sidecar test reported
offline unlock and reconnect success. Real funded-wallet compatibility and
migration from an older signing certificate remain unverified. Do not uninstall
a funded wallet to test an update without separately verifying recovery
material and an encrypted backup.

On 2026-10-17 the S22's isolated `com.blocknet.wallet.offlinetest` package
was updated from version code 2 to 3 with the same debug certificate SHA-256
`2752b74ccd5b7eaf0f81379d833742612e78a9e6565e4a313ddaacf8c979d38c`.
Its UID and user-0 data inode remained unchanged. The sidecar node was briefly
force-stopped for the update. The isolated instrumentation's **prepare** phase
passed with a UUID-named synthetic file and separate test Keystore alias:
encryption, restart, plaintext migration, corruption/key-loss rejection, and
failed-write preservation. After the same-signer version 3 install, **verify**
passed; it reopened the encrypted fixture and removed its test file and alias.
No wallet was opened and no network or radio setting was changed. The personal
package was untouched.

The sidecar app process relaunched, but its CoreService did not resume
automatically. Android rejected a direct shell start because the service is
not exported (`Requires permission not exported from uid 10468`); no bypass
was attempted. Its sync had to be restarted with the sidecar app's normal
control. The user subsequently reported that the ordinary address-book phone
checklist worked. Slow HTTPS lifecycle cancellation, changed or missing
shortname warnings, and other rows above remain separate device checks.

## Safe isolated acceptance route

Use a fresh emulator app profile for Java/UI policy tests and synthetic fixture
wallets only. For ARM64 Core and helper checks, use a separate test device or
isolated Android user/profile with a fresh package data directory and an
unfunded fixture wallet. Record the fixture wallet file hash before and after
each scenario; keep the test device offline for cold-launch cases. Check wrong
password, no-cache first run, Wi-Fi to blocked cellular, reconnect, background
lock, and the signed-channel scanner with synthetic Core test blocks. Do not
install over, uninstall, clear data from, or import keys from an existing funded
wallet. A final funded-wallet migration requires verified recovery and an
encrypted backup on another device before touching its installation.

## Final isolated mobile-Core check — 2026-10-18

The final 0001–0009 mobile-Core OFFLINETEST APK (SHA-256
`c97e3d5cc5822cb86d1b5b0b9dc8071de4cb8dacca58be427e94a172b4ae8197`)
passed 126 Android host unit tests and APK provenance verification. On the
unlocked S22, the synthetic cache-binding runner passed all 11 checks. The
unfunded disposable testnet foreground-policy runner passed 18 checks:
background Core stop, password-authorized dated offline view with Core stopped,
foreground restart and reconciliation, explicit continuous preference, and
manual Stop. After an app-scoped force-stop and relaunch, a new-process probe
confirmed no Core or saved sync request resumed. The test fixture was removed,
mainnet preference restored, and Core left stopped. No seed/key was exported,
no funds were sent or broadcast, and no phone-wide network setting changed.
This verifies the debug sidecar, not an owner-signed upgrade or funded wallet.
A >5-second startup and service-retirement race were covered by host gate tests
rather than a timed physical reproduction. Radio handover, synced battery use,
funded-wallet migration, and live on-chain notice delivery remain unmeasured.

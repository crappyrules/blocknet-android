# Offline wallet and update-check acceptance matrix

Local source and debug APK checks on 2026-10-16: `testDebugUnitTest` (68 tests), pinned Go helper tests (2 tests), `lintDebug`, `assembleDebug`, and `scripts/verify-apk.py` passed. The debug APK was not installed. The rows marked **Device** still require an authorized ARM64 phone or emulator run.

| Scenario | Expected result | Evidence / remaining check |
| --- | --- | --- |
| Wi-Fi to disallowed cellular while wallet is open | Core sync pauses; authenticated view switches to dated saved values; no send or shortname lookup. | Route, cache crypto, and network policy unit tests; **Device** radio handover and UI. |
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
| Preview APK replacement | Published preview certificates differ. The app warns prominently; no in-app installer is offered. Keep the old app until a separate recovery is verified. | Certificate comparison and signing-gate negative test; **Device** migration only after an owner signer is configured. |

The existing published preview does not contain this change. The new helper is built from pinned Core source, while the supplied Core binary has unavailable dirty source changes. Real installed-wallet compatibility must be verified on an authorized ARM64 device before relying on offline first unlock. Do not uninstall a funded wallet to test an update without separately verifying recovery material and an encrypted backup.

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

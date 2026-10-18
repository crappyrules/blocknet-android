# On-chain update signal (channel configured, no announcement broadcast)

The reviewed mobile Core includes the activity API's authenticated, read-only `POST /api/updates/scan`
endpoint. It scans at most eight locally stored canonical blocks and 512 outputs
per request, with batches of 64 and at most 16 candidate memos. A partial cursor
includes block height, transaction index and output index, so a crowded block
or spam cannot make the scan skip outputs. Core returns block hashes and only
122-byte memos with the `BNAU` prefix that match the configured public-view
announcement channel. It does not load, unlock, or inspect the user's wallet.
The endpoint comes from the reviewed 0003 patch to pinned upstream Core.

## Signed record

All integers are big-endian. The exact 122-byte layout is:

| Offset | Bytes | Value |
| --- | ---: | --- |
| 0–3 | 4 | `BNAU` |
| 4 | 1 | Format `1` |
| 5 | 1 | Kind `1` (release only; all other kinds rejected) |
| 6–9 | 4 | Chain epoch (`0x20260215` for the reviewed mainnet) |
| 10 | 1 | App `1` (Blocknet Android) |
| 11 | 1 | APK variant `2` (activity API protocol; the release uses mobile Core) |
| 12 | 1 | Channel `1` (preview) |
| 13 | 1 | Signer `1` (pinned primary public key) |
| 14–21 | 8 | Monotonic sequence |
| 22–25 | 4 | Android `versionCode` |
| 26–57 | 32 | SHA-256 of raw HTTPS manifest bytes |
| 58–121 | 64 | Ed25519 signature |

The signature covers the ASCII bytes `Blocknet Android update/v1` followed by
one NUL byte and record bytes 0–57. The memo checksum and matching channel
address do **not** authorize a release. Scope, length, kind, sequence, manifest
digest and Ed25519 signature are validated before any HTTPS fetch. No recovery
signer rotation or revocation record is implemented; unknown kinds and signers
are rejected.

Each manifest has the exact URL
`https://raw.githubusercontent.com/crappyrules/blocknet-android/master/android/updates/<signed-sha256>.json`.
The digest-named path lets a new release coexist with older signed notices.
The app binds the request to Android's permitted network, rejects redirects,
and reads at most 32 KiB. The raw-byte SHA-256 must match the signed memo. Its
JSON must contain only the documented app, epoch, sequence, version, variant,
channel, official release-page URL, exact activity-release APK URL, APK SHA-256,
expected signing-certificate SHA-256, and notes fields. The `activity-release`
asset name remains the variant-2 URL contract even when the APK contains the
mobile Core. The app displays the
release-page link named in the authenticated manifest; it does not download,
authenticate, or install the APK at that page. A signed announcement is **not**
an authenticated installer. Phone-side APK byte, package and signing checks
would be required before any future installer feature.

## Cursor and reorganization handling

The Android worker runs outside UI and wallet-unlock paths. It scans only
blocks with at least ten confirmations, stores a bounded cursor after each
page, checks its canonical block hash on resume, and rewinds up to Core's
100-block reorganization window if the hash changes. Validly signed memos are
saved in a 64-entry private pending inbox in the same commit as the cursor.
An unavailable or malformed HTTPS manifest remains pending; up to four entries
are retried per pass, rotating failures so later notices continue. When the
inbox is full, the worker keeps the input page cursor and replays that page
after retries free space; it never evicts an unprocessed signed notice. If the
inbox is corrupt, cursor and inbox reset together to the channel birth height
while the finalized sequence floor is retained. Only a
valid manifest can produce an accepted notice or advance the sequence floor.
A signed notice is marked provisional until it is at least 100 blocks deep. The durable sequence floor
advances only then. An identical sequence and digest can be republished after
a reorganization; a conflicting digest at the same sequence is rejected.
The worker pauses when Core or an allowed Wi-Fi/mobile connection is absent.
Configured on-chain builds do not poll GitHub releases or display old unsigned
GitHub cache entries as update notices. Builds without this channel retain the
legacy GitHub listing, explicitly labeled unverified in the app.

## Activation boundary

The owner Ed25519 announcement signing key and a separate dedicated mainnet
stealth channel were created locally outside Git. The Android build pins the
signer's public key, the channel's spend public key, deliberately published view
private key, and birth height. The channel spend private key remains outside
Git and is never used by the app. Core's address parser and a synthetic
stealth-output match validated the public address and view-key relationship.
No channel output or signed update notice has been broadcast.

Channel address:
`KNNpZhPcJX92oAyvcVWJhQXJjsWRYsEiPHej6ZTM2LuSJBDS3VQebjUjUCzYqBnidt1JXYUiUgA69HeBoTuVuQYWwnhQN`.
Spend public key:
`8c66dd904be0ef4ecc9b48c3a4710af0b8a0b485387a9fbd7ffd64596eb6c34c`.
View public key:
`dc45c0cf621c674ea6ddc27fe1f01131327943500c6832304183b97b6cc5f836`.
Published view private key:
`a887e975d9374b75fe36e4cd828f6333599f195b9fe2544b2329ffeb51300204`.
The scanner starts at mainnet height **66200**, the highest height in the
bundled validated checkpoint file retrieved on 2026-10-15. This predates
channel creation and gives a small safety margin without replaying the entire
chain. It is not a claim about the current network tip.

The pinned raw Ed25519 public key is
`333c7c85db80716deceb4da838b5ae3145ee4b0ed0a64966f60aa56e12972b16`
(SHA-256 of those 32 public bytes:
`e8697123b28bdc3e24e328313cef084e37dbfe75cfb68b4cc30ed05eb136256e`).
The separate owner Android release certificate is pinned by DER SHA-256
`4a307e802e3504f7850c546ab8b38c292211ea5d5c5be3342107edaec1255fff`.
Default debug builds leave the scanner disabled. A configured mobile-Core
build scans this channel, but cannot display a signed update until one is
broadcast and its manifest is available. A signed release build fails unless
the owner provides the reviewed mobile Core, a pinned release certificate, and all five
channel inputs matching the pinned values: `BLOCKNET_ANNOUNCEMENT_PRIMARY_PUB`,
`BLOCKNET_ANNOUNCEMENT_VIEW_PRIV` (a deliberately published channel view key,
never a user's wallet key), `BLOCKNET_ANNOUNCEMENT_SPEND_PUB`,
`BLOCKNET_ANNOUNCEMENT_BIRTH_HEIGHT`, and
`BLOCKNET_ANNOUNCEMENT_APK_CERT_SHA256`. The local owner signing script reads
the public channel config from the private key directory and prompts for the
Android keystore password without echo. The old published previews cannot gain this code or a new signing
identity in place. This implementation accepts only the currently pinned
mainnet epoch and network ID. A future fork requires explicit review, a new
scope and migration plan; notices from this chain are not assumed portable.

Host tests cover a full independently signed 122-byte golden record and scope
mutations, manifest scope/hash rejection, durable pending retry/reorg boundaries,
corruption replay and overflow backpressure,
bounded Core cursor progress, spam limits, and synthetic channel matching before,
at and after mainnet height
6500. ARM64 phone tests must still cover real channel matching, catch-up and reorgs,
power/latency, radio handover, process death, and user-wallet isolation.

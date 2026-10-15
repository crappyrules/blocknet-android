# Experimental locked wallet activity API

This additive patch targets Core commit `5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e`.
It does not replace the original Android binaries or relax any existing endpoint guard.
The extension is registered only on the authenticated private API, never the public explorer.

## Contract

`GET /api/wallet/activity?kind=outputs&limit=100&offset=0&order=desc`

- `kind`: `outputs` (default) or `sends`.
- `limit`: default 100, minimum 1, values above 100 clamp to 100.
- `offset`: nonnegative integer; beyond the last record returns an empty array.
- `order`: `desc` (default) or `asc`, matching the existing corresponding history/send endpoint.
- Authentication is unchanged: missing/invalid bearer token returns 401. No loaded wallet returns 503.
- A loaded wallet returns 200 even while API-locked. No unlock call or password is needed.

Every successful response contains `version: 1`, `wallet_id`, `locked`, `synced_height`,
`chain_height`, `scanning`, `kind`, `count`, `total`, `limit`, `offset`, `order`, and the
selected `outputs` or `sends` array. `scanning` means the wallet is more than
`MaxReorgDepth` blocks behind the chain, matching the balance endpoint's threshold.
Clients should also wait for `synced_height >= chain_height` before establishing an
activity baseline. `wallet_id` is lowercase hexadecimal SHA-256 of the UTF-8 bytes of:

```
blocknet-wallet-activity-v1<NUL><authoritative wallet address>
```

The delimiter is one zero byte, not the five literal characters `<NUL>`. Bind this
hash to the address obtained while unlocked, plus the app's network/wallet scope.
Reject pages whose hash differs from that binding. Do not treat the hash as a secret
or authorization credential. A replacement wallet using the same keys has the same
identity; filenames are deliberately excluded.

Output rows contain only `txid`, `direction: "received"`, `output_index`, `amount`,
`block_height`. These are wallet outputs and can include own change; clients suppress
change using the corresponding outgoing txid. Send rows contain only `txid`,
`direction: "sent"`, `total_amount`, `recorded_height`, `timestamp`. Numeric field
semantics and sort order match existing history/send APIs. Amounts are atomic units.
No address, recipient, memo, fee, seed, private key, password, or wallet path is returned.

The handler holds the API wallet-state read lock through serialization, preventing
an unload/replacement while responding. Wallet storage methods take their existing
internal read locks. Multiple page requests remain separate observations: callers
must check wallet identity and scan metadata on every page, and reject a changed
scope/generation. This is a bounded polling feed, not a durable event journal or
atomic multi-page snapshot. Reorgs and a growing tip can change page membership.

## Lock and lifecycle limits

Baseline Core API lock sets an API permission flag; its loaded scanner continues
scanning and saving the encrypted wallet. This endpoint exposes a narrow read-only
projection of that existing state. Seed, send, sign, address, history, sends and all
other existing guards remain unchanged. The API bearer token therefore gains this
explicit read-only activity capability while locked. Keep the API bound to loopback.

After process restart the wallet is unloaded. This extension cannot detect wallet
payments until the user loads the wallet once. It never retains a password, exports
view keys, starts a second wallet/node, or automatically unlocks. It does not provide
unconfirmed incoming mempool detection.

## Verification and build provenance

The patch includes `TestWalletActivityLockedContract`: in-process authenticated
HTTP handlers, synthetic encrypted wallets and synthetic activity records. It opens
no network listeners, starts no daemon/network/miner and builds/broadcasts no payment.
It verifies empty locked snapshots, authentication, public-route exclusion, locked
seed/send/sign/history/address guards, exact existing API field/order parity, paging
bounds, scan lag, identity changes, field allowlists and unloaded behavior.

Host verification requires the Rust static library followed by CGO-enabled Go tests:

```
cd crypto-rs && cargo build --release && cd ..
CGO_ENABLED=1 go test -run '^(TestWalletActivityLockedContract|TestHandleWalletSendsListsSendHistory|TestHandleHistoryPagePaginationContract|TestHandleUnloadWallet_NoWallet503)$' -count=1 .
```

The original supplied ARM binary records Go 1.27.0, Android ARM64, CGO enabled,
`-ldflags="-s -w -checklinkname=0"`, baseline commit above and `vcs.modified=true`.
ELF notes identify Android API 24 and NDK r30 build 16248370. The archive provides
binaries and an installer, with no Android source changes or cross-build recipe.
The upstream Makefile builds a host Rust static library at
`crypto-rs/target/release/libblocknet_crypto.a`; `crypto.go` links that path explicitly.
A new Android build must provide an Android ARM64 Rust static library at that path,
NDK Bionic C toolchain and matching link settings. The producer's dirty diff and
Rust dependency lock/toolchain are needed to claim reproduction of the original;
a build from clean baseline plus this patch is an explicitly new experimental build.

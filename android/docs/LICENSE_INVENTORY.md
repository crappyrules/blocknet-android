# License inventory for the Android candidate

This records the license evidence in the local source and build inputs. The
versioned [component inventory](THIRD_PARTY_COMPONENTS.csv) lists every module
recorded by the two packaged Go executables, the active Android ARM64 Cargo
target graph, and the resolved Java runtime graph. The APK also includes
`third-party-source-notices.txt`, a collection of locally available source
license and NOTICE files. This is release-preparation evidence, not a declaration
that every transitive component or bundled asset has completed notice review.

## Blocknet components

| Component | Evidence | Distribution notice |
| --- | --- | --- |
| Android wallet source | The existing `blocknetprivacy/blocknet` and pinned `blocknetprivacy/core` source checkouts have byte-identical BSD 3-Clause `LICENSE` files, SHA-256 `47eb78a8a1d00990787c7f3918e8cf6b5d609ba6214815a2582eca83e2533688`. | The same text is now at the Android repository root as `LICENSE` and in APK assets as `blocknet-wallet-LICENSE.txt`. This follows the owner's instruction to carry the existing Blocknet/Core terms for the wallet. |
| Blocknet Core, including the offline wallet reader and the BIP39 English word list | Pinned Core commit `5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e` contains that BSD 3-Clause `LICENSE`. `offline-wallet-view/main.go` is built with the pinned Core wallet package. The word-list provenance records the same license digest. | `blocknet-core-LICENSE.txt` is packaged in APK assets. The current Core executable is built from a pinned source archive with ordered reviewed patches. |

The Blocknet copyright and BSD terms in these files do **not** replace the
independent terms of dependencies. No third-party component is relicensed here.

Earlier Android candidates bundled Mantle and a dirty-source Core binary.
Their executable inputs are removed from the current Git tree; ordinary Git
history retains those older commits. Neither executable is in this APK.


## Separately packaged and linked dependencies

| Component | Verified local license evidence | Status |
| --- | --- | --- |
| ZXing Core 3.5.3 (`com.google.zxing:core`) | Cached `zxing-parent-3.5.3.pom` names Apache License 2.0; the [exact upstream tag](https://api.github.com/repos/zxing/zxing/git/trees/zxing-3.5.3?recursive=1) includes a root [LICENSE](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.3/LICENSE) and [NOTICE](https://raw.githubusercontent.com/zxing/zxing/zxing-3.5.3/NOTICE). | The full Apache 2.0 text is in `android-library-LICENSES.txt`. The upstream NOTICE is packaged verbatim as `zxing-core-3.5.3-NOTICE.txt`, SHA-256 `4db4b60aace29ec6c5c4f13ca9cac1930d3c0c54bcc67dceead0c10771120e66`. |
| Gson 2.11.0 (`com.google.code.gson:gson`) | Cached `gson-2.11.0.pom` names Apache-2.0. The [exact upstream tag tree](https://api.github.com/repos/google/gson/git/trees/gson-parent-2.11.0?recursive=1) has root and module LICENSE files but no NOTICE; the [root LICENSE](https://raw.githubusercontent.com/google/gson/gson-parent-2.11.0/LICENSE) is Apache 2.0. The cached JAR also has no NOTICE entry. | The full Apache 2.0 text is in `android-library-LICENSES.txt`. No Gson NOTICE was available from that tagged source distribution. |
| Bouncy Castle provider 1.86 (`org.bouncycastle:bcprov-jdk15to18`) | Cached POM names the [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html); the JAR contains `org/bouncycastle/LICENSE.class` with copyright and permission text. | The exact text emitted by the packaged JAR is now in APK asset `android-library-LICENSES.txt`. |
| Go modules recorded in candidate binary build info | `go version -m` records 83 Core module versions and two in the offline reader (both already in Core). All 83 distinct packaged Go module versions have local root license/notice source files. The two Mantle-only module versions from earlier candidates are no longer in the current component inventory or APK notice asset. | Each module/version and source file hash is in `THIRD_PARTY_COMPONENTS.csv`; their root and nested source license/NOTICE texts are in APK asset `third-party-source-notices.txt`. This includes Pion `LICENSES/` alternatives, libp2p websocket notices, Prometheus's embedded gddo license, and QUIC assets' license. Go build info is dependency evidence, not proof that every byte of each module survives linking. |
| Core Rust crates | The reviewed Cargo lock has 59 registry packages; `cargo tree --target aarch64-linux-android --edges normal,build` selects 50 for this build. All 50 cached manifests have license expressions and root license/notice files. Four lock-only packages (`fiat-crypto@0.2.9`, `hex@0.4.3`, `wasi@0.11.1+wasi-snapshot-preview1`, `zerocopy-derive@0.8.63`) are absent from the selected target graph. | The APK source-notice collection includes every selected crate/version, manifest expression/authors, and its root license/notice files, including `unicode-ident@1.0.27`'s Unicode-3.0 text. The complete file names and hashes are in `THIRD_PARTY_COMPONENTS.csv`; review any attribution text embedded below crate roots before release. |
| `go-yamux/v5@v5.0.1` | The [exact upstream tag](https://github.com/libp2p/go-yamux/tree/v5.0.1) supplies both [MPL-2.0](https://raw.githubusercontent.com/libp2p/go-yamux/v5.0.1/LICENSE) and [BSD](https://raw.githubusercontent.com/libp2p/go-yamux/v5.0.1/LICENSE-BSD) texts; both match cached source hashes and are packaged. | The APK notice asset also points recipients to that tagged source. [MPL 2.0 sections 3.1–3.4](https://www.mozilla.org/en-US/MPL/2.0/) address source availability and notices for executable distribution. The owner must verify the chosen distribution method and any covered modifications before signing; this inventory does not assert legal clearance. |
| `go-base36@v0.2.0` | The [exact upstream LICENSE.md](https://raw.githubusercontent.com/multiformats/go-base36/v0.2.0/LICENSE.md) matches the cached source and describes the Permissive License Stack as Apache-2.0 **or** MIT. | Its text is packaged verbatim. Full Apache and MIT texts are also present elsewhere in the same source-notice asset. The tagged tree has no separate NOTICE file. |
| `pion/rtp@v1.8.19` | Both the [tagged LICENSE](https://raw.githubusercontent.com/pion/rtp/v1.8.19/LICENSE) and [tagged `LICENSES/MIT.txt`](https://raw.githubusercontent.com/pion/rtp/v1.8.19/LICENSES/MIT.txt) contain the literal `<year> <copyright holders>` placeholder; both match the cached module SHA-256 `b85dcd3e453d05982552c52b5fc9e0bdd6d23c6f8e844b984a88af32570b0cc0`. | Both texts are packaged verbatim. No holder was invented; an upstream or owner-supplied attribution correction is needed if a named holder is required for release. |
| Gradle wrapper / Android build tools | `android/gradle/wrapper/gradle-wrapper.jar` is a repository build input; the Android Gradle plugin and SDK are build tools. | These are not the three application libraries above. Check any redistribution of build tooling separately. |

The Android app also contains icons, generated translations and checkpoint
data. This inventory does not establish their independent provenance or any
third-party artwork rights.

## Release work still required

The selected debug APK packages the local root and nested source notices, the
exact ZXing NOTICE, and wallet, Core, and Java library license assets;
`verify-apk.py` compares each asset byte for byte with source. Remaining narrow
attribution questions are owner validation of MPL source availability for
`go-yamux/v5`, the upstream Pion RTP copyright placeholder if a concrete
holder is needed, and independent
artwork/checkpoint provenance. Repeat byte verification against the
**owner-signed** artifact. This inventory records these limits; it does not
declare legal clearance.

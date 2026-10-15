# Offline recovery-word suggestions

The app bundles the exact 2,048 English BIP39 words from Blocknet Core's `wallet/wordlist.go` at commit `5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e`. The source's string entries are copied without changing their order or spelling into `app/src/main/assets/mnemonic-english.txt`, one lowercase word per line with a final newline.

`mnemonic-english.provenance.json` records the source repository, exact commit/path, source and asset SHA-256 digests, and the license filename/digest. The full upstream BSD 3-Clause license is included beside it as `blocknet-core-LICENSE.txt`.

`MnemonicWords` loads this immutable vocabulary once. Prefix matching starts with the first ASCII letter, uses dictionary order, and supports showing every matching word. An empty fragment returns no suggestions. It never sends a recovery word to a server, records typed fragments, or learns a typing history. The recovery-phrase form remains responsible for replacing only the word at the cursor and submitting the complete phrase to Core for validation; suggestions do not prove that a phrase or its checksum is valid.

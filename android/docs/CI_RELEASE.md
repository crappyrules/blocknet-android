# Owner-signed Android CI handoff

The regular `android.yml` job builds the pinned mobile Core debug APK on
pushes and pull requests. It runs the launcher verifier regression
tests, Android unit tests, lint, and APK payload checks, then uploads test
artifacts. A tag push **does not** sign or publish an APK. Do not push a new
`v*` tag until this replacement workflow is on GitHub's default branch: the
previous published workflow automatically published debug APKs on tags.

An owner-signed CI build requires a separate manual `workflow_dispatch` against
an existing, reviewed `vX.Y.Z-preview.N` tag with `sign_release=true`. That job
runs only after the reviewed mobile debug build succeeds and the
`android-release` environment allows it. It rebuilds
the pinned mobile Core and offline reader, requires the pinned owner
certificate and public announcement channel, checks package/version against
the selected tag, verifies that the remote tag still points to the checked-out
dispatch commit and tracked source is unchanged, verifies the APK and uploads
a signed artifact for review.
The Android keystore is decoded only into the signing runner's temporary
directory and removed when the signing step ends. No announcement Ed25519
private key or channel spend private key is used by Actions.

## Owner-controlled GitHub setup

1. Review and merge this workflow and verifier update first. Confirm the
   default branch no longer has the old tag-triggered debug publication job.
   Set a repository ruleset that prevents moving or deleting release tags.
2. In repository Settings → Environments, create `android-release`. Restrict
   deployment to reviewed `v*` tags and require an appropriate reviewer;
   disable bypass where available. Verify those protection rules in Settings.
   Add these **environment secrets** there, not repository-wide secrets:
   `BLOCKNET_RELEASE_KEYSTORE_B64` (base64 of the existing owner `.p12`),
   `BLOCKNET_RELEASE_STORE_PASSWORD`, and
   `BLOCKNET_RELEASE_KEY_PASSWORD`. Keep the originals outside Git and never
   paste their values into a workflow, issue, chat, or command argument.
3. For publication, create a separate `android-publish` environment with tag
   restrictions and required review; disable bypass where available. Verify
   those protection rules in Settings too. The publish job receives no owner
   signing secret. The workflow name `android-publish` alone does not create
   protection: GitHub can create an unprotected environment automatically.
4. After phone acceptance and review of the release notes' remaining limits, create and
   review the release tag. Run the workflow manually at that tag with
   `sign_release=true` and `publish_release=false` first. Verify the downloaded
   signed artifact, its signer certificate and checksum. This is a rehearsal.
   For actual publication, start a **new** run at the reviewed tag with
   `sign_release=true`, `publish_release=true`, and `confirm_publish_tag`
   exactly equal to the selected tag. Approve `android-release`, then inspect
   the signed APK and `SHA256SUMS` from **that same run** before approving
   `android-publish`. The later run rebuilds and signs a new APK; approval of
   the rehearsal's bytes does not authorize the later artifact.

The environments and tag rules must be configured and inspected before
dispatch. YAML cannot enforce required reviewers or prove that environment
secrets came from environment scope rather than repository or organization
scope. Do not define the signing secret names at those broader scopes.
Reviewers must inspect the tagged workflow code and the exact publication-run
   artifact before allowing signing and publication. The workflow rechecks the
remote tag commit before and after signing, and again immediately before
publication; tag rules provide the needed protection against a move between
the final check and GitHub release creation. The publish job also rechecks the
downloaded artifact's SHA-256, source commit, certificate, package and version.

## Local signing alternative

The current local owner script builds and signs the release APK from reviewed
source and native build inputs. The existing CI produces **debug-signed** APKs;
it does not produce an unsigned release APK that can simply be signed locally.
An unsigned-CI/local-sign flow would need a separate reproducible unsigned
release target and byte/provenance checks before adding that option. Do not
re-sign a CI debug APK and present it as the release variant.

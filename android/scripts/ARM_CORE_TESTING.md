# Test the pinned mobile Core on a Linux x86 host

The test uses a separately built mobile Core executable, QEMU user emulation,
and Android 30 Bionic libraries. It verifies the real core API and
cryptography; Android application lifecycle, camera, and UI need separate tests.

Install `qemu-user` and `unzip`; mount commands below require root. The tested SDK
image is `system-images/android-30/google_apis/arm64-v8a/system.img` (Google APIs
revision 16, July 2024). This image is a GPT disk containing Android dynamic
partitions. Its system extent starts at **3145728**, length **800317440** bytes.
These offsets are specific to this image; inspect LP metadata for another image.

Prepare a disposable root, substituting the actual SDK and repository paths:

```sh
mkdir -p /tmp/blocknet-arm-system /tmp/blocknet-arm-root/system
sudo mount -o ro,loop,offset=3145728,sizelimit=800317440 \
  /path/android-sdk/system-images/android-30/google_apis/arm64-v8a/system.img \
  /tmp/blocknet-arm-system
unzip -p /tmp/blocknet-arm-system/system/apex/com.android.runtime.apex \
  apex_payload.img > /tmp/blocknet-runtime-apex.img
mkdir -p /tmp/blocknet-arm-root/apex/com.android.runtime
sudo mount -o ro,loop /tmp/blocknet-runtime-apex.img \
  /tmp/blocknet-arm-root/apex/com.android.runtime
sudo cp -a /tmp/blocknet-arm-system/system/bin /tmp/blocknet-arm-root/system/
sudo cp -a /tmp/blocknet-arm-system/system/lib64 /tmp/blocknet-arm-root/system/
sudo cp -a /tmp/blocknet-arm-system/system/etc /tmp/blocknet-arm-root/system/
cp /usr/bin/qemu-aarch64 /tmp/blocknet-arm-root/qemu-aarch64
sudo mkdir -p /tmp/blocknet-arm-root/dev /tmp/blocknet-arm-root/proc \
  /tmp/blocknet-arm-root/sys /tmp/blocknet-arm-root/work/testdata \
  /tmp/blocknet-arm-root/work/wallets
sudo mount --bind /dev /tmp/blocknet-arm-root/dev
sudo mount -t proc proc /tmp/blocknet-arm-root/proc
sudo mount --bind /sys /tmp/blocknet-arm-root/sys
sudo cp /absolute/reviewed/mobile-build/blocknet-core-arm64-android-experimental \
  /tmp/blocknet-arm-root/work/blocknet-core-arm64-android
```

Create `/tmp/blocknet-arm-root/work/testdata/settings.json`:

```json
{"wallet_file":"/work/wallets/main.wallet.dat","listen":"/ip4/127.0.0.1/tcp/0","p2p_max_inbound":0,"p2p_max_outbound":0,"chain_cache_cap":256,"seed":false,"identity_key_path":"/work/testdata/test.identity"}
```

The mobile profile uses `BLOCKNET_MOBILE_CLIENT=1` to permit an offline start
without enabling seed serving. It does not listen for P2P peers.
No mining or funded transactions are performed by this test suite.

Run these in a terminal:

```sh
sudo chroot /tmp/blocknet-arm-root /qemu-aarch64 /work/blocknet-core-arm64-android --version
sudo env BLOCKNET_MOBILE_CLIENT=1 chroot /tmp/blocknet-arm-root /qemu-aarch64 /work/blocknet-core-arm64-android \
  --data /work/testdata --testnet --api 127.0.0.1:18392
```

Then run the API integration suite in another terminal:

```sh
sudo env BLOCKNET_MOBILE_CLIENT=1 python3 android/scripts/test-core-api.py \
  --cookie /tmp/blocknet-arm-root/work/testdata/api.cookie \
  --url http://127.0.0.1:18392 --disposable-testnet \
  --restart-arm-chroot /tmp/blocknet-arm-root
```

Only use a disposable testnet node. The suite unloads any current wallet, creates
new empty wallets, tests signing, mnemonic/private/view key imports, lock/unlock,
recovery, history, failed-send idempotency, encrypted backup copying, and restart.
Credentials and recovery words are kept out of its output. Restart mode leaves
the core running, with its output in `work/restart.log`.

After testing, stop the QEMU core gracefully (SIGTERM), then unmount in reverse:

```sh
sudo umount /tmp/blocknet-arm-root/sys /tmp/blocknet-arm-root/proc /tmp/blocknet-arm-root/dev
sudo umount /tmp/blocknet-arm-root/apex/com.android.runtime
sudo umount /tmp/blocknet-arm-system
```

Remove the disposable root only after all mounts are gone. Never recursively
remove a directory while host `/dev`, `/proc`, or `/sys` is mounted inside it.

Blocknet on Android: test build
mantle v1.2.0 + core v0.22.0, for 64-bit ARM phones

1. Install Termux from F-Droid (f-droid.org) or from
   github.com/termux/termux-app/releases.
   Do not use the Play Store version. It cannot run this.

2. Save blocknet-android-test.tar.gz to the phone's Download folder.

3. Open Termux and type these lines, one at a time:

     termux-setup-storage
     cd ~ && tar xzf ~/storage/downloads/blocknet-android-test.tar.gz
     sh ~/android-test/install.sh
     blocknet start
     blocknet status

   termux-setup-storage asks for file access. Tap Allow.

4. Use Wi-Fi. The node downloads the blockchain.

5. Do not run "blocknet upgrade". This test build is newer than
   the public release.

What to send back:
  - A screenshot of everything after "sh ~/android-test/install.sh".
  - The output of:  tail -n 50 ~/.config/bnt/mainnet.log

To stop the node:  blocknet stop
To remove it all:  blocknet stop; rm -rf ~/.config/bnt ~/android-test $PREFIX/bin/blocknet

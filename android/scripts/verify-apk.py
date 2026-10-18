#!/usr/bin/env python3
"""Verify the APK's Core programs, checkpoint provenance and adaptive launcher resources."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile
from apk_launcher import verify_launcher

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', nargs='?', default='app/build/outputs/apk/debug/app-debug.apk')
parser.add_argument('--core-mobile-dir', required=True, help='Absolute reviewed mobile Core build directory')
parser.add_argument('--offline-wallet-view-dir', required=True, help='Absolute pinned offline wallet reader build directory')
parser.add_argument('--aapt2', help='Path to Android SDK aapt2 for compiled manifest/resource verification')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
apk = Path(args.apk)
if not apk.is_absolute():
    apk = root / apk

def require(ok, message):
    if not ok:
        raise SystemExit('FAIL: ' + message)

def recorded_go_modules(binary):
    result = subprocess.check_output(['go', 'version', '-m', str(binary)], text=True)
    return {(fields[1], fields[2]) for line in result.splitlines()
            if len(fields := line.split()) >= 3 and fields[0] == 'dep'}

def find_aapt2():
    if args.aapt2:
        return args.aapt2
    if shutil.which('aapt2'):
        return shutil.which('aapt2')
    sdks = [Path(value) for key in ['ANDROID_HOME', 'ANDROID_SDK_ROOT'] if (value := os.environ.get(key))]
    properties = root / 'local.properties'
    if properties.is_file():
        for line in properties.read_text().splitlines():
            if line.startswith('sdk.dir='):
                sdks.append(Path(line.split('=', 1)[1]))
    for sdk in sdks:
        for candidate in sorted((sdk / 'build-tools').glob('*/aapt2'), reverse=True):
            if candidate.is_file():
                return str(candidate)
    raise SystemExit('FAIL: aapt2 is required; set ANDROID_HOME or pass --aapt2 /path/to/aapt2')

def dump(*arguments):
    return subprocess.check_output([aapt2, 'dump', *arguments], text=True, stderr=subprocess.STDOUT)

def verified_mobile_inputs(directory):
    require(directory.is_absolute() and directory.is_dir(), '--core-mobile-dir must be an existing absolute build directory')
    provenance_file = directory / 'build-provenance.json'
    binary_file = directory / 'blocknet-core-arm64-android-experimental'
    patches = root / 'core-patches'
    required = [provenance_file, binary_file, directory / 'Cargo.lock', directory / 'applied.patch',
                directory / 'android-cgo-link-flags.patch', patches / '0001-locked-wallet-activity.patch',
                patches / '0002-android-cgo-link-flags.patch', patches / '0003-onchain-announcement-scan.patch',
                directory / 'onchain-announcement-scan.patch']
    required += [patches / '0004-mobile-client-policy.patch', directory / 'mobile-client-policy.patch']
    for name in ('0005-core-wallet-persistence.patch', '0006-core-wallet-scan-serialization.patch',
                 '0007-core-wallet-send-readiness.patch', '0008-core-wallet-send-reorg-activity.patch',
                 '0009-core-wallet-notification-overflow.patch', '0010-bootstrap-progress.patch'):
        required += [patches / name, directory / name]
    require(all(path.is_file() for path in required), 'Experimental Core build is missing its binary, provenance, lockfile or reviewed patch copies')
    provenance_bytes = provenance_file.read_bytes()
    try:
        provenance = json.loads(provenance_bytes)
    except (ValueError, UnicodeDecodeError):
        raise SystemExit('FAIL: Invalid experimental Core build provenance')
    require(isinstance(provenance, dict) and provenance.get('experimental') is True,
            'Experimental Core provenance must explicitly mark the build experimental')
    require(provenance.get('baseline_repository') == 'https://github.com/blocknetprivacy/core' and
            provenance.get('baseline_commit') == '5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e',
            'Experimental Core provenance identifies an unreviewed baseline')
    expected = {'binary_sha256': binary_file,
                'patch_sha256': patches / '0001-locked-wallet-activity.patch',
                'android_link_shim_sha256': patches / '0002-android-cgo-link-flags.patch',
                'announcement_scan_patch_sha256': patches / '0003-onchain-announcement-scan.patch',
                'cargo_lock_sha256': root / 'core-patches/core-activity.Cargo.lock'}
    expected.update({'mobile_client_patch_sha256': patches / '0004-mobile-client-policy.patch',
                     'wallet_persistence_patch_sha256': patches / '0005-core-wallet-persistence.patch',
                     'wallet_scan_patch_sha256': patches / '0006-core-wallet-scan-serialization.patch',
                     'wallet_send_readiness_patch_sha256': patches / '0007-core-wallet-send-readiness.patch',
                     'wallet_followup_patch_sha256': patches / '0008-core-wallet-send-reorg-activity.patch',
                     'wallet_notification_overflow_patch_sha256': patches / '0009-core-wallet-notification-overflow.patch',
                     'bootstrap_progress_patch_sha256': patches / '0010-bootstrap-progress.patch'})
    for key, path in expected.items():
        recorded = provenance.get(key)
        require(isinstance(recorded, str) and re.fullmatch('[0-9a-f]{64}', recorded) is not None and
                recorded == hashlib.sha256(path.read_bytes()).hexdigest(),
                f'Experimental Core {key} does not match {path}')
    require(hashlib.sha256((directory / 'applied.patch').read_bytes()).hexdigest() == provenance['patch_sha256'] and
            hashlib.sha256((directory / 'android-cgo-link-flags.patch').read_bytes()).hexdigest() == provenance['android_link_shim_sha256'] and
            hashlib.sha256((directory / 'onchain-announcement-scan.patch').read_bytes()).hexdigest() == provenance['announcement_scan_patch_sha256'],
            'Experimental build patch copies differ from the reviewed source patches')
    require(provenance.get('mobile_client_capability') == 1 and
                provenance.get('patch_order') == ['0001-locked-wallet-activity.patch', '0002-android-cgo-link-flags.patch', '0003-onchain-announcement-scan.patch', '0004-mobile-client-policy.patch', '0005-core-wallet-persistence.patch', '0006-core-wallet-scan-serialization.patch', '0007-core-wallet-send-readiness.patch', '0008-core-wallet-send-reorg-activity.patch', '0009-core-wallet-notification-overflow.patch', '0010-bootstrap-progress.patch'] and
                hashlib.sha256((directory / 'mobile-client-policy.patch').read_bytes()).hexdigest() == provenance['mobile_client_patch_sha256'] and
                all(hashlib.sha256((directory / name).read_bytes()).hexdigest() == provenance[field]
                    for name, field in [('0005-core-wallet-persistence.patch', 'wallet_persistence_patch_sha256'),
                                        ('0006-core-wallet-scan-serialization.patch', 'wallet_scan_patch_sha256'),
                                        ('0007-core-wallet-send-readiness.patch', 'wallet_send_readiness_patch_sha256'),
                                        ('0008-core-wallet-send-reorg-activity.patch', 'wallet_followup_patch_sha256'),
                                        ('0009-core-wallet-notification-overflow.patch', 'wallet_notification_overflow_patch_sha256'),
                                        ('0010-bootstrap-progress.patch', 'bootstrap_progress_patch_sha256')]),
            'Mobile Core capability or ordered patch provenance is invalid')
    return binary_file.read_bytes(), provenance_bytes

selected_dir = Path(args.core_mobile_dir)
mobile_inputs = verified_mobile_inputs(selected_dir)
view_dir = Path(args.offline_wallet_view_dir)
require(view_dir.is_absolute() and view_dir.is_dir(), '--offline-wallet-view-dir must be an existing absolute build directory')
view_binary = view_dir / 'blocknet-wallet-view-arm64-android'
view_provenance = view_dir / 'build-provenance.json'
require(view_binary.is_file() and view_provenance.is_file(), 'Offline wallet reader build is missing')
view_record = json.loads(view_provenance.read_bytes())
require(view_record.get('baseline_commit') == '5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e' and
        view_record.get('binary_sha256') == hashlib.sha256(view_binary.read_bytes()).hexdigest() and
        view_record.get('helper_source_sha256') == hashlib.sha256((root / 'offline-wallet-view/main.go').read_bytes()).hexdigest() and
        view_record.get('helper_test_sha256') == hashlib.sha256((root / 'offline-wallet-view/main_test.go').read_bytes()).hexdigest(),
        'Offline wallet reader provenance does not match the pinned build')
activity_asset = 'assets/core-mobile-build-provenance.json'
with zipfile.ZipFile(apk) as archive:
    archive_names = set(archive.namelist())
    require(not any('mantle' in name.lower() for name in archive_names),
            'APK unexpectedly contains a Mantle executable or asset')
    actual_core = archive.read('lib/arm64-v8a/libblocknet_core.so')
    require(actual_core == mobile_inputs[0], 'Core differs from the explicitly selected mobile build')
    require(activity_asset in archive.namelist(), 'Mobile Core APK is missing build provenance')
    require(archive.read(activity_asset) == mobile_inputs[1],
            'Packaged mobile Core provenance differs from the validated build directory')
    require('assets/core-activity-build-provenance.json' not in archive.namelist(),
            'APK contains provenance for a retired Core variant')
    print('PASS mobile Core: reviewed baseline, binary/patch/shim/lockfile hashes and exact packaged build provenance')
    print(f'PASS libblocknet_core.so: {len(actual_core):,} bytes SHA-256 {hashlib.sha256(actual_core).hexdigest()}')
    actual_view = archive.read('lib/arm64-v8a/libblocknet_walletview.so')
    require(actual_view == view_binary.read_bytes(), 'Offline wallet reader differs from the pinned build')
    require(archive.read('assets/offline-wallet-view-build-provenance.json') == view_provenance.read_bytes(),
            'Packaged offline wallet reader provenance differs from the pinned build')
    print(f'PASS libblocknet_walletview.so: {len(actual_view):,} bytes SHA-256 {hashlib.sha256(actual_view).hexdigest()}')
    abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/')}
    require(abis == {'arm64-v8a'}, f'Unexpected APK native ABIs: {abis}')
    require('classes.dex' in archive.namelist(), 'APK contains no application code')

    assets = root / 'app/src/main/assets'
    for asset_name, source in {
        'blocknet-wallet-LICENSE.txt': root.parent / 'LICENSE',
        'blocknet-core-LICENSE.txt': assets / 'blocknet-core-LICENSE.txt',
        'android-library-LICENSES.txt': assets / 'android-library-LICENSES.txt',
        'zxing-core-3.5.3-NOTICE.txt': assets / 'zxing-core-3.5.3-NOTICE.txt',
        'third-party-source-notices.txt': assets / 'third-party-source-notices.txt',
    }.items():
        require(archive.read('assets/' + asset_name) == source.read_bytes(),
                f'Packaged license {asset_name} differs from reviewed source')
    print('PASS packaged Blocknet, Android library, and pinned source dependency notices')
    notice = archive.read('assets/third-party-source-notices.txt')
    rows = list(csv.DictReader((root / 'docs/THIRD_PARTY_COMPONENTS.csv').open(newline='', encoding='utf-8')))
    recorded = {(row['component'], row['version']) for row in rows if row['ecosystem'] == 'Go'}
    actual = (recorded_go_modules(selected_dir / 'blocknet-core-arm64-android-experimental') |
              recorded_go_modules(view_binary))
    require(recorded == actual, 'Go license inventory differs from selected binary build info')
    require(all((row['component'] + '@' + row['version']).encode() in notice
                for row in rows if row['ecosystem'] in ('Go', 'Rust')),
            'A selected source component is missing from the packaged notice collection')
    require(hashlib.sha256(archive.read('assets/zxing-core-3.5.3-NOTICE.txt')).hexdigest() ==
            '4db4b60aace29ec6c5c4f13ca9cac1930d3c0c54bcc67dceead0c10771120e66',
            'ZXing NOTICE differs from the exact upstream 3.5.3 tag')
    print(f'PASS notice inventory: {len(recorded)} exact Go module versions recorded by selected binaries')
    checkpoint_bytes = archive.read('assets/checkpoints-mainnet.dat')
    provenance_bytes = archive.read('assets/checkpoints-mainnet.provenance.json')
    require(checkpoint_bytes == (assets / 'checkpoints-mainnet.dat').read_bytes(), 'Packaged checkpoints differ from source asset')
    require(provenance_bytes == (assets / 'checkpoints-mainnet.provenance.json').read_bytes(), 'Packaged checkpoint provenance differs from source')
    provenance = json.loads(provenance_bytes)
    require(provenance.get('filename') == 'checkpoints-mainnet.dat' and provenance.get('network') == 'mainnet', 'Checkpoint provenance names wrong file/network')
    require(provenance.get('source') == 'https://bntpool.com/checkpoints.dat', 'Checkpoint provenance does not identify the official pool endpoint')
    digest = hashlib.sha256(checkpoint_bytes).hexdigest()
    require(digest == provenance.get('sha256'), 'Checkpoint bytes do not match provenance SHA-256')
    records = {}
    for line in checkpoint_bytes.decode('utf-8').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        match = re.fullmatch(r'([1-9][0-9]*):([a-fA-F0-9]{64})', line)
        require(match is not None, 'Bundled checkpoint file contains an invalid record')
        height = int(match[1])
        require(height not in records, 'Bundled checkpoint file contains duplicate heights')
        records[height] = match[2]
    require(records and len(records) == provenance.get('entries'), 'Bundled checkpoint count differs from provenance')
    require(max(records) == provenance.get('max_height'), 'Bundled checkpoint maximum height differs from provenance')
    print(f'PASS mainnet checkpoints: {len(records)} entries through block {max(records)}, SHA-256 {digest}')

    word_bytes = archive.read('assets/mnemonic-english.txt')
    word_provenance = json.loads(archive.read('assets/mnemonic-english.provenance.json'))
    words = word_bytes.decode('ascii').splitlines()
    require(word_bytes == (assets / 'mnemonic-english.txt').read_bytes(), 'Packaged mnemonic words differ from source asset')
    require(hashlib.sha256(word_bytes).hexdigest() == word_provenance.get('sha256'), 'Mnemonic words do not match provenance')
    require(len(words) == 2048 == word_provenance.get('entries') and words == sorted(set(words)), 'Mnemonic words must be 2048 unique sorted entries')
    require(all(re.fullmatch('[a-z]+', word) for word in words) and 'pond' in words, 'Invalid mnemonic word list')
    require(word_provenance.get('source_commit') == '5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e' and word_provenance.get('source_path') == 'wallet/wordlist.go', 'Wrong mnemonic source revision')
    license_bytes = archive.read('assets/blocknet-core-LICENSE.txt')
    require(hashlib.sha256(license_bytes).hexdigest() == word_provenance.get('license_sha256'), 'Core license differs from provenance')
    print('PASS mnemonic completions: exact 2048-word Core BIP39 English asset, provenance and license')

aapt2 = find_aapt2()
resources = dump('resources', str(apk))
manifest = dump('xmltree', str(apk), '--file', 'AndroidManifest.xml')
try:
    verify_launcher(resources, manifest, archive_names,
                    lambda path: dump('xmltree', str(apk), '--file', path))
except ValueError as error:
    require(False, str(error))
print('PASS adaptive launcher: packaged foreground/monochrome artwork and manifest icon/roundIcon mappings')
print(f'PASS {apk}: reviewed mobile Core and offline reader, mainnet checkpoint seed/provenance and launcher resources verified')

#!/usr/bin/env python3
"""Verify the APK's supplied programs, checkpoint provenance and adaptive launcher resources."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk', nargs='?', default='app/build/outputs/apk/debug/app-debug.apk')
parser.add_argument('--core-activity-dir', help='Explicit absolute experimental Core build directory; never auto-detected')
parser.add_argument('--aapt2', help='Path to Android SDK aapt2 for compiled manifest/resource verification')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
apk = Path(args.apk)
if not apk.is_absolute():
    apk = root / apk
inputs = root.parent / 'android-test' / 'android-test'

def require(ok, message):
    if not ok:
        raise SystemExit('FAIL: ' + message)

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

def verified_activity_inputs(directory):
    require(directory.is_absolute() and directory.is_dir(), '--core-activity-dir must be an existing absolute build directory')
    provenance_file = directory / 'build-provenance.json'
    binary_file = directory / 'blocknet-core-arm64-android-experimental'
    patches = root / 'core-patches'
    required = [provenance_file, binary_file, directory / 'Cargo.lock', directory / 'applied.patch',
                directory / 'android-cgo-link-flags.patch', patches / '0001-locked-wallet-activity.patch',
                patches / '0002-android-cgo-link-flags.patch']
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
                'original_binary_sha256': inputs / 'blocknet-core-arm64-android',
                'patch_sha256': patches / '0001-locked-wallet-activity.patch',
                'android_link_shim_sha256': patches / '0002-android-cgo-link-flags.patch',
                'cargo_lock_sha256': root / 'core-patches/core-activity.Cargo.lock'}
    for key, path in expected.items():
        recorded = provenance.get(key)
        require(isinstance(recorded, str) and re.fullmatch('[0-9a-f]{64}', recorded) is not None and
                recorded == hashlib.sha256(path.read_bytes()).hexdigest(),
                f'Experimental Core {key} does not match {path}')
    require(hashlib.sha256((directory / 'applied.patch').read_bytes()).hexdigest() == provenance['patch_sha256'] and
            hashlib.sha256((directory / 'android-cgo-link-flags.patch').read_bytes()).hexdigest() == provenance['android_link_shim_sha256'],
            'Experimental build patch copies differ from the reviewed source patches')
    return binary_file.read_bytes(), provenance_bytes

activity_inputs = verified_activity_inputs(Path(args.core_activity_dir)) if args.core_activity_dir is not None else None
activity_asset = 'assets/core-activity-build-provenance.json'
with zipfile.ZipFile(apk) as archive:
    mantle = archive.read('lib/arm64-v8a/libmantle.so')
    require(mantle == (inputs / 'blocknet').read_bytes(), 'Mantle differs from the unchanged supplied executable')
    print(f'PASS libmantle.so: {len(mantle):,} bytes SHA-256 {hashlib.sha256(mantle).hexdigest()}')
    actual_core = archive.read('lib/arm64-v8a/libblocknet_core.so')
    expected_core = activity_inputs[0] if activity_inputs else (inputs / 'blocknet-core-arm64-android').read_bytes()
    require(actual_core == expected_core, 'Core differs from the explicitly selected experimental build' if activity_inputs else 'Core differs from the supplied executable; experimental builds require --core-activity-dir')
    if activity_inputs:
        require(activity_asset in archive.namelist(), 'Experimental Core APK is missing build provenance')
        require(archive.read(activity_asset) == activity_inputs[1], 'Packaged experimental Core provenance differs from the validated build directory')
        print('PASS experimental activity API Core: reviewed baseline, binary/patch/shim/lockfile hashes and exact packaged build provenance')
    else:
        require(activity_asset not in archive.namelist(), 'Default APK unexpectedly contains experimental Core provenance')
    print(f'PASS libblocknet_core.so: {len(actual_core):,} bytes SHA-256 {hashlib.sha256(actual_core).hexdigest()}')
    abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/')}
    require(abis == {'arm64-v8a'}, f'Unexpected APK native ABIs: {abis}')
    require('classes.dex' in archive.namelist(), 'APK contains no application code')

    assets = root / 'app/src/main/assets'
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

    for resource in ['res/mipmap-anydpi-v26/ic_launcher.xml', 'res/mipmap-anydpi-v33/ic_launcher.xml',
                     'res/drawable/ic_launcher_foreground.xml', 'res/drawable/ic_launcher_monochrome.xml']:
        require(resource in archive.namelist(), f'Missing packaged launcher resource: {resource}')

aapt2 = find_aapt2()
resources = dump('resources', str(apk))
launcher = re.search(r'resource (0x[0-9a-f]+) mipmap/ic_launcher\b', resources)
require(launcher is not None, 'Compiled mipmap/ic_launcher resource is missing')
manifest = dump('xmltree', str(apk), '--file', 'AndroidManifest.xml')
for attribute in ['icon', 'roundIcon']:
    require(re.search(rf':{attribute}\(0x[0-9a-f]+\)=@{launcher[1]}\b', manifest) is not None,
            f'Packaged application {attribute} does not reference mipmap/ic_launcher')
require('android.intent.action.MAIN' in manifest and 'android.intent.category.LAUNCHER' in manifest,
        'Packaged manifest has no launcher activity')
for version in [26, 33]:
    icon = dump('xmltree', str(apk), '--file', f'res/mipmap-anydpi-v{version}/ic_launcher.xml')
    require(all(f'E: {element}' in icon for element in ['adaptive-icon', 'background', 'foreground']),
            f'Packaged API {version} launcher is not a complete adaptive icon')
    if version == 33:
        require('E: monochrome' in icon, 'Packaged Android 13 launcher lacks themed monochrome artwork')
print('PASS adaptive launcher: packaged foreground/monochrome artwork and manifest icon/roundIcon mappings')
print(f'PASS {apk}: {"explicit experimental activity API" if activity_inputs else "supplied original"} native programs, mainnet checkpoint seed/provenance and launcher resources verified')

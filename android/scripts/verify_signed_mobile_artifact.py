#!/usr/bin/env python3
"""Recheck the downloaded signed artifact's mobile payload and provenance."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PATCHES = (
    ('0001-locked-wallet-activity.patch', 'patch_sha256'),
    ('0002-android-cgo-link-flags.patch', 'android_link_shim_sha256'),
    ('0003-onchain-announcement-scan.patch', 'announcement_scan_patch_sha256'),
    ('0004-mobile-client-policy.patch', 'mobile_client_patch_sha256'),
    ('0005-core-wallet-persistence.patch', 'wallet_persistence_patch_sha256'),
    ('0006-core-wallet-scan-serialization.patch', 'wallet_scan_patch_sha256'),
    ('0007-core-wallet-send-readiness.patch', 'wallet_send_readiness_patch_sha256'),
    ('0008-core-wallet-send-reorg-activity.patch', 'wallet_followup_patch_sha256'),
    ('0009-core-wallet-notification-overflow.patch', 'wallet_notification_overflow_patch_sha256'),
    ('0010-bootstrap-progress.patch', 'bootstrap_progress_patch_sha256'),
    ('0011-fetched-block-wallet-scan.patch', 'fetched_block_wallet_scan_patch_sha256'),
)
BASELINE = '5a37ed52f0426951eaac2f0ae86bf0bb6d70a83e'


def require(value, reason):
    if not value:
        raise ValueError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify(directory):
    apk = directory / 'blocknet-wallet-arm64-activity-release.apk'
    core_bytes = (directory / 'core-mobile-build-provenance.json').read_bytes()
    view_bytes = (directory / 'offline-wallet-view-build-provenance.json').read_bytes()
    core, view = json.loads(core_bytes), json.loads(view_bytes)
    require(core.get('baseline_commit') == BASELINE and core.get('mobile_client_capability') == 1,
            'Release Core is not the pinned mobile build')
    require(core.get('patch_order') == [name for name, _ in PATCHES],
            'Release Core patch order differs from reviewed source')
    require('original_binary_sha256' not in core,
            'Release provenance unexpectedly depends on a retired bundled Core')
    for name, field in PATCHES:
        require(core.get(field) == digest((ROOT / 'core-patches' / name).read_bytes()),
                f'Release Core patch differs: {name}')
    require(core.get('cargo_lock_sha256') == digest((ROOT / 'core-patches/core-activity.Cargo.lock').read_bytes()),
            'Release Cargo lock differs from reviewed source')
    require(view.get('baseline_commit') == BASELINE and
            view.get('helper_source_sha256') == digest((ROOT / 'offline-wallet-view/main.go').read_bytes()) and
            view.get('helper_test_sha256') == digest((ROOT / 'offline-wallet-view/main_test.go').read_bytes()),
            'Release offline reader differs from reviewed source')
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        require('assets/core-activity-build-provenance.json' not in names and
                not any('mantle' in name.lower() for name in names),
                'Release APK contains a retired Core or Mantle asset')
        require(archive.read('assets/core-mobile-build-provenance.json') == core_bytes and
                archive.read('assets/offline-wallet-view-build-provenance.json') == view_bytes,
                'Packaged native provenance differs from release artifact')
        require(digest(archive.read('lib/arm64-v8a/libblocknet_core.so')) == core.get('binary_sha256') and
                digest(archive.read('lib/arm64-v8a/libblocknet_walletview.so')) == view.get('binary_sha256'),
                'Packaged native executable differs from release provenance')
        require({name.split('/')[1] for name in names if name.startswith('lib/')} == {'arm64-v8a'},
                'Release APK contains an unexpected native ABI')
    print('PASS signed artifact contains only the reviewed pinned mobile Core and offline reader')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: verify_signed_mobile_artifact.py RELEASE_DIRECTORY')
    try:
        verify(Path(sys.argv[1]))
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f'FAIL: {error}')

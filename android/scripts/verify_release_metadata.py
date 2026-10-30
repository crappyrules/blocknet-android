#!/usr/bin/env python3
"""Verify a signed release APK's owner certificate, package and selected tag."""

import os
from pathlib import Path
import re
import subprocess
import sys


OWNER_CERT_SHA256 = '4a307e802e3504f7850c546ab8b38c292211ea5d5c5be3342107edaec1255fff'
RELEASE_TAG = 'v1.0.0-preview.9'
RELEASE_VERSION_CODE = 9


def verify_metadata(cert_output, badging, tag):
    if tag != RELEASE_TAG:
        raise ValueError('Release tag differs from the reviewed version')
    if re.search(r'^Number of signers: 1$', cert_output, re.M) is None or re.search(
            rf'^Signer #1 certificate SHA-256 digest: {OWNER_CERT_SHA256}$', cert_output, re.M) is None:
        raise ValueError('APK does not have exactly the pinned owner signing certificate')
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", badging, re.M)
    if not package or package[1] != 'com.blocknet.wallet' or int(package[2]) != RELEASE_VERSION_CODE or package[3] != tag[1:]:
        raise ValueError('APK package/version does not match the selected release tag')


def verify_apk(apk, tag):
    sdk = Path(os.environ.get('ANDROID_HOME', ''))
    tools = sdk / 'build-tools/36.0.0'
    if not apk.is_file() or not (tools / 'apksigner').is_file() or not (tools / 'aapt').is_file():
        raise ValueError('APK or Android SDK build tools are missing')
    cert_output = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    verify_metadata(cert_output, badging, tag)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: verify_release_metadata.py APK vX.Y.Z-preview.N')
    try:
        verify_apk(Path(sys.argv[1]), sys.argv[2])
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f'FAIL: {error}')
    print('PASS owner certificate, APK signature, package, version code and selected tag')

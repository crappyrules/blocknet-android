import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from verify_release_metadata import OWNER_CERT_SHA256, verify_metadata
from verify_signed_mobile_artifact import verify_core_provenance


TAG = 'v1.0.0-preview.9'
CERT = f'Number of signers: 1\nSigner #1 certificate SHA-256 digest: {OWNER_CERT_SHA256}\n'
BADGING = "package: name='com.blocknet.wallet' versionCode='9' versionName='1.0.0-preview.9'\n"
TAG_CHECK = Path(__file__).with_name('verify_release_tag.py')
PREVIEW8_PROVENANCE = Path(__file__).with_name('fixtures') / 'preview8-core-mobile-build-provenance.json'


class ReleaseMetadataTest(unittest.TestCase):
    def test_owner_signed_package_matches_tag(self):
        verify_metadata(CERT, BADGING, TAG)

    def test_wrong_signer_or_package_fails(self):
        with self.assertRaisesRegex(ValueError, 'owner signing certificate'):
            verify_metadata(CERT.replace(OWNER_CERT_SHA256, '0' * 64), BADGING, TAG)
        with self.assertRaisesRegex(ValueError, 'package/version'):
            verify_metadata(CERT, BADGING.replace('versionCode=\'9\'', 'versionCode=\'1\''), TAG)
        with self.assertRaisesRegex(ValueError, 'package/version'):
            verify_metadata(CERT, BADGING.replace('versionCode=\'9\'', 'versionCode=\'8\''), TAG)
        with self.assertRaisesRegex(ValueError, 'package/version'):
            verify_metadata(CERT, BADGING.replace('versionName=\'1.0.0-preview.9\'', 'versionName=\'1.0.0-preview.8\''), TAG)
        with self.assertRaisesRegex(ValueError, 'reviewed version'):
            verify_metadata(CERT, BADGING, 'v1.0.0-preview.8')


class SignedMobileProvenanceTest(unittest.TestCase):
    def record(self):
        # Captured directly from the packaged preview.8 review APK's provenance asset.
        return json.loads(PREVIEW8_PROVENANCE.read_text())

    def test_actual_packaged_preview8_patch_manifest_is_accepted(self):
        verify_core_provenance(self.record())

    def test_missing_or_reordered_birth_rescan_patch_is_rejected(self):
        record = self.record()
        record['patch_order'] = record['patch_order'][:-1]
        with self.assertRaisesRegex(ValueError, 'patch order'):
            verify_core_provenance(record)
        record = self.record()
        record['patch_order'][-1], record['patch_order'][-2] = record['patch_order'][-2:]
        with self.assertRaisesRegex(ValueError, 'patch order'):
            verify_core_provenance(record)

    def test_missing_or_changed_birth_rescan_hash_is_rejected(self):
        record = self.record()
        record.pop('wallet_birth_rescan_patch_sha256')
        with self.assertRaisesRegex(ValueError, '0012-wallet-birth-rescan.patch'):
            verify_core_provenance(record)
        record = self.record()
        record['wallet_birth_rescan_patch_sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, '0012-wallet-birth-rescan.patch'):
            verify_core_provenance(record)


class ReleaseTagTest(unittest.TestCase):
    def git(self, *args, cwd):
        return subprocess.check_output(['git', *args], cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()

    def check(self, tag, commit, cwd):
        env = dict(os.environ, GITHUB_REF='refs/tags/' + tag)
        return subprocess.run([sys.executable, str(TAG_CHECK), tag, commit], cwd=cwd,
                              env=env, text=True, capture_output=True)

    def test_lightweight_and_annotated_tags_and_tag_move(self):
        for annotated in (False, True):
            with self.subTest(annotated=annotated), tempfile.TemporaryDirectory() as temporary:
                base = Path(temporary)
                remote, work = base / 'remote.git', base / 'work'
                self.git('init', '-q', '--bare', str(remote), cwd=base)
                self.git('init', '-q', str(work), cwd=base)
                self.git('config', 'user.name', 'Release Test', cwd=work)
                self.git('config', 'user.email', 'test@example.invalid', cwd=work)
                (work / 'fixture.txt').write_text('first\n')
                self.git('add', 'fixture.txt', cwd=work)
                self.git('commit', '-qm', 'fixture', cwd=work)
                first = self.git('rev-parse', 'HEAD', cwd=work)
                self.git('remote', 'add', 'origin', str(remote), cwd=work)
                args = ('tag', '-am', 'release', TAG) if annotated else ('tag', TAG)
                self.git(*args, cwd=work)
                self.git('push', '-q', 'origin', 'refs/tags/' + TAG, cwd=work)
                self.assertEqual(self.check(TAG, first, work).returncode, 0)
                (work / 'fixture.txt').write_text('uncommitted change\n')
                dirty = self.check(TAG, first, work)
                self.assertNotEqual(dirty.returncode, 0)
                self.assertIn('Tracked release source differs', dirty.stderr)

                (work / 'fixture.txt').write_text('second\n')
                self.git('commit', '-qam', 'moved', cwd=work)
                self.git('tag', '-f', TAG, cwd=work)
                self.git('push', '-q', '--force', 'origin', 'refs/tags/' + TAG, cwd=work)
                self.git('checkout', '-q', '--detach', first, cwd=work)
                moved = self.check(TAG, first, work)
                self.assertNotEqual(moved.returncode, 0)
                self.assertIn('no longer resolves', moved.stderr)


if __name__ == '__main__':
    unittest.main()

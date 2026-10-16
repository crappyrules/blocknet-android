#!/usr/bin/env python3
"""Reject a moved release tag or a checkout that differs from the dispatch SHA."""

import os
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(['git', *args], text=True, stderr=subprocess.STDOUT).strip()


def verify_tag(tag, expected_sha):
    if re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+-preview\.[0-9]+', tag) is None:
        raise ValueError('Release tag has the wrong format')
    if re.fullmatch(r'[0-9a-f]{40}', expected_sha) is None:
        raise ValueError('Expected release commit must be a full SHA-1')
    if os.environ.get('GITHUB_REF') and os.environ['GITHUB_REF'] != 'refs/tags/' + tag:
        raise ValueError('Dispatch ref is not the selected release tag')
    if git('rev-parse', 'HEAD') != expected_sha:
        raise ValueError('Checked-out source differs from the dispatch commit')
    if subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--'], check=False).returncode != 0:
        raise ValueError('Tracked release source differs from the checked-out commit')

    name = 'refs/tags/' + tag
    output = git('ls-remote', '--tags', 'origin', name, name + '^{}')
    refs = dict(line.split('\t', 1)[::-1] for line in output.splitlines() if '\t' in line)
    remote_sha = refs.get(name + '^{}', refs.get(name))
    if remote_sha != expected_sha:
        raise ValueError('Release tag no longer resolves to the checked-out commit')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: verify_release_tag.py vX.Y.Z-preview.N EXPECTED_COMMIT_SHA')
    try:
        verify_tag(sys.argv[1], sys.argv[2])
    except (ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f'FAIL: {error}')
    print('PASS release tag, remote commit, dispatch SHA and checkout agree')

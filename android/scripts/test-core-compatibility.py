#!/usr/bin/env python3
"""Root-only offline ORIGINAL/CANDIDATE ARM wallet compatibility acceptance.

Run only inside a fresh network namespace, for example:
  sudo unshare --net python3 test-core-compatibility.py --arm-root ROOT \
    --original ORIGINAL --candidate CANDIDATE
No mining, network route, wallet funding or transaction broadcast is used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--arm-root', required=True)
parser.add_argument('--original', required=True)
parser.add_argument('--candidate', required=True)
args = parser.parse_args()
checks = 0
stage = 'network isolation'
owned = None
marker = None
process = None
log_handle = None
token = ''
# Public BIP39 test vector, never a user recovery phrase. Never printed.
public_mnemonic = ' '.join(['abandon'] * 11 + ['about'])
password = 'compatibility-' + secrets.token_hex(24)
message = 'Offline original and experimental core interoperability check'
url = 'http://127.0.0.1:39192'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def check(ok, label):
    global checks
    if not ok:
        raise AssertionError(label)
    checks += 1
    print('PASS ' + label, flush=True)


def request(method, path, body=None, expected=200, authenticated=True):
    data = None if body is None else json.dumps(body, separators=(',', ':')).encode()
    headers = {'Content-Type': 'application/json'}
    if authenticated:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(url + path, data=data, headers=headers, method=method)
    try:
        with opener.open(request, timeout=120) as response:
            status, raw = response.status, response.read(2 * 1024 * 1024 + 1)
    except urllib.error.HTTPError as response:
        status, raw = response.code, response.read(2 * 1024 * 1024 + 1)
    if status != expected or len(raw) > 2 * 1024 * 1024:
        raise AssertionError('Unexpected bounded API response status: ' + str(status))
    if expected != 200:
        try:
            return json.loads(raw)
        except ValueError:
            return {}  # Authentication middleware deliberately returns a plain-text401.
    return json.loads(raw)


def stop():
    global process, log_handle
    if process is None:
        return
    child = process
    if child.poll() is not None:
        process = None
        if log_handle:
            log_handle.close()
            log_handle = None
        raise AssertionError('Owned core exited before graceful termination')
    child.send_signal(signal.SIGTERM)
    try:
        child.wait(timeout=35)
    except subprocess.TimeoutExpired:
        child.kill()
        child.wait(timeout=5)
        process = None
        raise AssertionError('Owned core required forced termination')
    finally:
        if log_handle:
            log_handle.close()
            log_handle = None
    process = None
    if child.returncode not in (0, -signal.SIGTERM):
        raise AssertionError('Owned core returned an unexpected termination code')


def start(binary, label):
    global process, token, log_handle
    if process is not None:
        raise AssertionError('Previous owned process has not stopped')
    cookie = owned / 'data' / 'api.cookie'
    cookie.unlink(missing_ok=True)
    token = ''
    log_handle = (owned / (label + '.log')).open('wb')
    environment = os.environ.copy()
    environment.pop('LD_PRELOAD', None)
    environment.pop('LD_LIBRARY_PATH', None)
    environment.update(XDG_CONFIG_HOME=inside + '/config', TMPDIR=inside + '/tmp', GOMEMLIMIT='384MiB', NO_COLOR='1')
    process = subprocess.Popen(['chroot', str(arm_root), '/qemu-aarch64', inside + '/' + binary,
                                '--data', inside + '/data', '--api', '127.0.0.1:39192'],
                               stdin=subprocess.DEVNULL, stdout=log_handle, stderr=subprocess.STDOUT,
                               env=environment, start_new_session=True)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise AssertionError('Owned core exited during startup')
        try:
            token = cookie.read_text().strip()
            if token and request('GET', '/api/health').get('status') in ('ok', 'healthy'):
                return
            # Health shape varies between compatible baselines; HTTP 200 itself proves readiness.
            if token:
                request('GET', '/api/status')
                return
        except (OSError, ValueError, AssertionError, urllib.error.URLError):
            pass
        time.sleep(0.2)
    raise AssertionError('Owned core API readiness timeout')


def load_wallet():
    request('POST', '/api/wallet/load', {'filepath': 'public-vector.wallet.dat', 'password': password})


def seed_and_address(expected_address=None):
    address = request('GET', '/api/wallet/address')['address']
    recovered = request('POST', '/api/wallet/seed', {'password': password})['mnemonic']
    check(recovered == public_mnemonic, 'Recovery phrase survives encrypted wallet interchange')
    if expected_address is not None:
        check(address == expected_address, 'Recovered stealth address matches the original core')
    return address


def verify(signature):
    return request('POST', '/api/verify', {name: signature[name] for name in ('address', 'message', 'signature')})['valid']


success = False
try:
    check(os.geteuid() == 0, 'Compatibility harness is root-only')
    check(os.readlink('/proc/self/ns/net') != os.readlink('/proc/1/ns/net'), 'Harness runs in a separate network namespace')
    links = json.loads(subprocess.check_output(['ip', '-json', 'link', 'show'], text=True))
    check({link['ifname'] for link in links} == {'lo'}, 'Isolated namespace has only loopback')
    subprocess.run(['ip', 'link', 'set', 'lo', 'up'], check=True, stdout=subprocess.DEVNULL)
    routes = json.loads(subprocess.check_output(['ip', '-json', 'route', 'show', 'table', 'main'], text=True))
    check(not routes, 'Isolated namespace has no external IPv4 route')
    routes6 = json.loads(subprocess.check_output(['ip', '-json', '-6', 'route', 'show', 'table', 'main'], text=True))
    check(all(route.get('dev') == 'lo' for route in routes6), 'Isolated namespace has no external IPv6 route')
    arm_root = Path(args.arm_root).resolve(strict=True)
    original = Path(args.original).resolve(strict=True)
    candidate = Path(args.candidate).resolve(strict=True)
    check((arm_root / 'qemu-aarch64').is_file() and os.path.lexists(arm_root / 'system/bin/linker64'), 'Prepared ARM chroot and Bionic linker exist')
    check(hashlib.sha256(original.read_bytes()).hexdigest() == '0056d4d86446071525203f738dfdbaaa6e71be48e04f6f222e1067d3c9861143', 'Original input matches the supplied unchanged ARM core')
    fixture_id = uuid.uuid4().hex
    owned = arm_root / 'work' / ('core-compat-' + fixture_id)
    owned.mkdir(mode=0o700)
    marker = fixture_id
    (owned / '.owned-fixture').write_text(marker)
    inside = '/work/' + owned.name
    for name in ('data', 'wallets', 'tmp'):
        (owned / name).mkdir(mode=0o700)
    for source, name in ((original, 'original'), (candidate, 'candidate')):
        shutil.copyfile(source, owned / name)
        (owned / name).chmod(0o700)
    settings = {'seed': True, 'listen': '/ip4/127.0.0.1/tcp/39180', 'chain_cache_cap': 64,
                'p2p_max_inbound': 0, 'p2p_max_outbound': 0, 'full_sync': False,
                'wallet_file': inside + '/wallets/public-vector.wallet.dat'}
    (owned / 'data/settings.json').write_text(json.dumps(settings))
    (owned / 'data/settings.json').chmod(0o600)
    wallet = owned / 'wallets/public-vector.wallet.dat'

    stage = 'original public-vector restore'
    start('original', 'original-create')
    request('POST', '/api/wallet/import', {'filename': wallet.name, 'password': password, 'mnemonic': public_mnemonic})
    address = seed_and_address()
    check(bool(address), 'Original core restores the public deterministic test vector')
    original_signature = request('POST', '/api/wallet/sign', {'message': message})
    check(verify(original_signature), 'Original core produces a valid cryptographic message signature')
    stop()
    check(wallet.is_file() and wallet.stat().st_size > 44, 'Original core gracefully saves a nonempty encrypted wallet')
    original_wallet_hash = hashlib.sha256(wallet.read_bytes()).digest()

    stage = 'candidate loads original encrypted wallet'
    start('candidate', 'candidate-crossload')
    load_wallet()
    seed_and_address(address)
    check(verify(original_signature), 'Candidate verifies a signature from the original core')
    candidate_signature = request('POST', '/api/wallet/sign', {'message': message})
    check(verify(candidate_signature), 'Candidate signs and verifies with the original encrypted keys')
    request('POST', '/api/wallet/lock', {})
    activity = request('GET', '/api/wallet/activity?kind=outputs&limit=100&offset=0&order=desc')
    expected_identity = hashlib.sha256(('blocknet-wallet-activity-v1\0' + address).encode()).hexdigest()
    check(activity['locked'] and activity['wallet_id'] == expected_identity, 'Candidate exposes authenticated locked read-only activity for the correct wallet')
    request('GET', '/api/wallet/activity', expected=401, authenticated=False)
    check(True, 'Locked activity still requires API authentication')
    request('POST', '/api/wallet/seed', {'password': password}, expected=403)
    request('POST', '/api/wallet/sign', {'message': message}, expected=403)
    request('POST', '/api/wallet/send', {'recipients': [{'address': address, 'amount': 1}], 'dry_run': True}, expected=403)
    check(True, 'Locked candidate keeps recovery, signing and spending inaccessible')
    request('POST', '/api/wallet/unlock', {'password': password})
    stop()
    check(hashlib.sha256(wallet.read_bytes()).digest() != original_wallet_hash, 'Candidate actually writes a fresh encrypted wallet envelope')

    stage = 'original reloads candidate-saved encrypted wallet'
    start('original', 'original-roundtrip')
    load_wallet()
    seed_and_address(address)
    check(verify(candidate_signature), 'Original core verifies the candidate signature after backward-compatible wallet reload')
    stop()

    stage = 'candidate independently derives the public vector'
    start('candidate', 'candidate-derive')
    request('POST', '/api/wallet/import', {'filename': 'independent-derivation.wallet.dat', 'password': password, 'mnemonic': public_mnemonic})
    seed_and_address(address)
    check(verify(original_signature), 'Independent candidate recovery derives cryptographically compatible signing keys')
    stop()
    success = True
except BaseException as failure:
    print('FAIL at ' + stage + ' after ' + str(checks) + ' checks (' + type(failure).__name__ + (': '+str(failure) if isinstance(failure, AssertionError) else '') + '). No credentials or recovery text logged.', flush=True)
finally:
    if process is not None:
        try:
            stop()
        except BaseException as cleanup:
            success = False
            print('FAIL owned process cleanup (' + type(cleanup).__name__ + ').', flush=True)
    if owned is not None:
        try:
            expected_parent = (arm_root / 'work').resolve()
            if owned.parent.resolve() != expected_parent or owned.is_symlink() or not owned.name.startswith('core-compat-') or (owned / '.owned-fixture').read_text() != marker:
                raise AssertionError('Fixture ownership mismatch')
            shutil.rmtree(owned)
        except BaseException as cleanup:
            success = False
            print('FAIL owned fixture cleanup (' + type(cleanup).__name__ + ').', flush=True)
if success:
    print('OK (' + str(checks) + ' offline original/candidate compatibility checks). No external network, miner, funds or broadcast used.', flush=True)
sys.exit(0 if success else 1)

#!/usr/bin/env python3
"""Exercise the wallet API on a dedicated disposable testnet core.

Uses real cryptography and encrypted wallets. Creates uniquely named test wallets,
unloads the current wallet, and never mines or submits a funded transaction.
Example: sudo python3 android/scripts/test-core-api.py --cookie /path/api.cookie \
  --url http://127.0.0.1:18392 --disposable-testnet
"""
import argparse
import json
import pathlib
import urllib.error
import urllib.request
import uuid
import os
import signal
import time
import subprocess
import shutil
import posixpath

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--cookie', required=True)
p.add_argument('--url', required=True)
p.add_argument('--disposable-testnet', action='store_true', required=True)
p.add_argument('--restart-arm-chroot', help='Optional prepared Android ARM chroot; restart the exact core and verify backup recovery')
args = p.parse_args()
if not args.url.startswith('http://127.0.0.1:'):
    p.error('Only a loopback test node is supported')
token = pathlib.Path(args.cookie).read_text().strip()
password = 'integration-' + uuid.uuid4().hex
prefix = 'integration-' + uuid.uuid4().hex[:10]
checks = []

def request(method, path, body=None, expected=200, key=None, auth=True):
    data = None if body is None else json.dumps(body, separators=(',', ':')).encode()
    headers = {'Content-Type': 'application/json'}
    if auth: headers['Authorization'] = 'Bearer ' + token
    if key: headers['Idempotency-Key'] = key
    req = urllib.request.Request(args.url + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    assert status in (expected if isinstance(expected, tuple) else (expected,)), f'{method} {path}: expected {expected}, got {status}'
    return json.loads(raw) if raw.lstrip().startswith(b'{') else {'error':raw.decode().strip()}

def check(label):
    checks.append(label)
    print('PASS ' + label, flush=True)

request('GET', '/api/wallet/balance', expected=401, auth=False)
check('cookie authentication enforced')
wallet_dir=posixpath.dirname(request('GET','/api/settings')['settings']['wallet_file'])
request('POST', '/api/wallet/unload', {}, expected=(200,503))
created = request('POST', '/api/wallet/create', {'filename': prefix+'.wallet.dat', 'password': password})
address = created['address']
assert created['created'] and created['filename'] == prefix+'.wallet.dat'
check('create encrypted wallet')
seed = request('POST', '/api/wallet/seed', {'password': password})['mnemonic']
assert len(seed.split()) == 12
check('password-confirmed 12-word backup')
balance = request('GET', '/api/wallet/balance')
assert balance['spendable'] == 0
assert request('GET', '/api/wallet/address')['address'] == address
assert 'outputs' in request('GET', '/api/wallet/history/page?limit=20&offset=0&order=desc')
assert 'sends' in request('GET', '/api/wallet/sends?limit=20&offset=0&order=desc')
check('balance address and history schema')
signed = request('POST', '/api/wallet/sign', {'message':'Native Android wallet integration test'})
verified = request('POST', '/api/verify', {k:signed[k] for k in ('address','message','signature')})
assert verified['valid']
check('real cryptographic message sign and verify')
request('POST', '/api/wallet/lock', {})
request('GET', '/api/wallet/balance', expected=403)
request('POST', '/api/wallet/unlock', {'password': password})
check('lock access restriction and unlock')
body={'recipients':[{'address':address,'amount':1,'memo_text':'test'}], 'dry_run':True}
request('POST','/api/wallet/send',body,expected=400)
check('dry run rejects unfunded payment')
body['dry_run']=False
key=uuid.uuid4().hex
request('POST','/api/wallet/send',body,expected=400,key=key)
request('POST','/api/wallet/send',body,expected=400,key=key)
state=request('GET','/api/wallet/send/status?idempotency_key='+key)
assert state['state']=='failed' and state['original_status']==400
body['recipients'][0]['amount']=2
request('POST','/api/wallet/send',body,expected=409,key=key)
assert request('GET','/api/wallet/send/status?idempotency_key='+uuid.uuid4().hex)['state']=='not_found'
check('send idempotency replay mismatch and status persistence')
request('POST','/api/wallet/unload',{})
request('POST','/api/wallet/load',{'filepath':prefix+'.wallet.dat','password':password})
assert request('GET','/api/wallet/address')['address']==address
check('encrypted wallet reload preserves address')
if args.restart_arm_chroot:
    root = pathlib.Path(args.restart_arm_chroot)
    source = root/'work/wallets'/(prefix+'.wallet.dat')
    backup = root/'work/wallets'/(prefix+'-backup.wallet.dat')
    old_pid = request('GET','/api/status')['pid']
    os.kill(old_pid, signal.SIGTERM)
    for _ in range(200):
        if not pathlib.Path('/proc/'+str(old_pid)).exists(): break
        time.sleep(.1)
    else: raise AssertionError('Core failed to stop gracefully')
    shutil.copyfile(source,backup)
    os.chmod(backup,0o600)
    with open(root/'work/restart.log','ab') as log:
        subprocess.Popen(['chroot',str(root),'/qemu-aarch64','/work/blocknet-core-arm64-android','--data','/work/testdata','--testnet','--api','127.0.0.1:'+args.url.rsplit(':',1)[1]],stdout=log,stderr=log,start_new_session=True)
    for _ in range(200):
        try:
            token=pathlib.Path(args.cookie).read_text().strip()
            request('GET','/api/health'); break
        except (OSError,urllib.error.URLError): time.sleep(.1)
    else: raise AssertionError('Restarted core never became ready')
    request('GET','/api/wallet/balance',expected=503)
    request('POST','/api/wallet/load',{'filepath':backup.name,'password':password})
    assert request('GET','/api/wallet/address')['address']==address
    assert request('GET','/api/wallet/send/status?idempotency_key='+key)['state']=='failed'
    check('graceful restart encrypted backup reload and durable send status')
request('POST','/api/wallet/unload',{})
restored=request('POST','/api/wallet/import',{'filename':prefix+'-restored.wallet.dat','password':password,'mnemonic':seed})
assert restored['address']==address
check('mnemonic restore preserves address')
request('PATCH','/api/settings',{'wallet_file':posixpath.join(wallet_dir,prefix+'-restored.wallet.dat')})
assert request('GET','/api/settings')['settings']['wallet_file'].endswith(prefix+'-restored.wallet.dat')
check('active wallet settings contract')
viewkeys=request('POST','/api/wallet/viewkeys',{'password':password})
request('POST','/api/wallet/unload',{})
view=request('POST','/api/wallet/import',{'filename':prefix+'-view.wallet.dat','password':password,'spend_pub':viewkeys['spend_pub'],'view_priv':viewkeys['view_priv']})
assert view['address']==address
assert request('GET','/api/wallet/address')['view_only']
request('POST','/api/wallet/sign',{'message':'must fail'},expected=403)
request('POST','/api/wallet/send',body,expected=403)
check('view-only key import address and spending restrictions')
request('POST','/api/wallet/unload',{})
keywallet=request('POST','/api/wallet/import',{'filename':prefix+'-keys.wallet.dat','password':password,'spend_priv':'01'+'00'*31,'view_priv':'02'+'00'*31})
assert keywallet['address']
assert not request('GET','/api/wallet/address')['view_only']
keysign=request('POST','/api/wallet/sign',{'message':'Imported private keys'})
assert request('POST','/api/verify',{k:keysign[k] for k in ('address','message','signature')})['valid']
check('private key import and real signature verification')
request('POST','/api/wallet/unload',{})
print(json.dumps({'result':'passed','checks':len(checks),'transactions_broadcast':0,'wallet_prefix':prefix}))

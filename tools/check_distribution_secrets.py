#!/usr/bin/env python3
"""Check tracked Git history for private-key files and PEM private-key blocks; no contents printed."""
from pathlib import PurePosixPath
import re
import subprocess


def git(*args):
    return subprocess.check_output(['git', *args])


suffixes = {'.jks', '.keystore', '.p12', '.pfx', '.pkcs12', '.jceks', '.key', '.pem'}
objects = git('rev-list', '--objects', '--all').decode().splitlines()
blobs = []
for item in objects:
    oid, _, name = item.partition(' ')
    if not name:
        continue
    path = PurePosixPath(name)
    if path.suffix.lower() in suffixes or path.name in {'signing.properties', 'keystore.properties', '.env'}:
        raise SystemExit('Private material filename found in Git history (content suppressed)')
    if git('cat-file', '-t', oid).strip() == b'blob':
        blobs.append(oid)
for oid in set(blobs):
    body = git('cat-file', 'blob', oid)
    if re.search(rb'-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----', body):
        raise SystemExit('Private key block found in Git history (content suppressed)')
print(f'Git history: {len(set(blobs))} blobs checked; no private key files or PEM blocks')

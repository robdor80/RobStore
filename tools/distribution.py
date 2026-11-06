#!/usr/bin/env python3
"""Shared APK validation primitives for automatic Android distribution."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

PACKAGES = {'release': 'es.robertodorado.robstore', 'dev': 'es.robertodorado.robstore.debug'}
CONTRACT = 'robstore-update.json'
RECEIPT = 'robstore-distribution.json'
REPO = 'robdor80/RobStore'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
    if result.returncode:
        if Path(str(args[0])).name != 'apksigner':
            raise ValueError('Tool failed: ' + Path(str(args[0])).name)
        # Classify known errors locally; never print argv, paths, tool output or secret values.
        error = (result.stdout + result.stderr).lower()
        if 'unsupportedclassversionerror' in error or 'java_home' in error:
            reason = 'El Java del runner no puede ejecutar apksigner.'
        elif 'does not contain a key' in error or 'no key found' in error or 'not a key entry' in error:
            reason = 'SIGNING_ALIAS no identifica una clave privada del keystore existente.'
        elif 'password was incorrect' in error or 'password verification failed' in error or 'tampered with' in error:
            reason = 'SIGNING_STORE_PASSWORD no abre el keystore existente.'
        elif 'cannot recover key' in error or 'unrecoverablekeyexception' in error or 'failed to decrypt' in error:
            reason = 'SIGNING_KEY_PASSWORD no desbloquea la clave privada existente.'
        elif 'invalid keystore format' in error or 'keystore was not recognized' in error or 'derinputstream' in error:
            reason = 'SIGNING_KEYSTORE_B64 no contiene un keystore compatible o está dañado.'
        elif 'min-sdk-version' in error or 'minsdkversion' in error:
            reason = 'apksigner no pudo leer el SDK mínimo del APK compilado.'
        else:
            reason = 'apksigner rechazó la firma; el log antiguo no permite identificar el motivo sin ejecutar el diagnóstico con las claves existentes.'
        raise ValueError('apksigner ' + str(args[1]) + ': ' + reason + ' No regenerar ni sustituir la identidad Release.')
    return result.stdout.strip()


def git(*args):
    return run('git', *args)


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write(path, data):
    Path(path).write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def tools():
    sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    require(sdk, 'ANDROID_HOME is required')
    return Path(sdk) / 'build-tools' / '35.0.0'


def fingerprint(value):
    require(re.fullmatch('[a-fA-F0-9]{64}', value) is not None, 'Expected a public SHA-256 certificate fingerprint without colons')
    return value.lower()


def inspect_apk(apk, channel, certificate):
    apk = Path(apk)
    require(apk.is_file() and 5 <= len(apk.name) <= 160 and
            re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*\.apk', apk.name), 'Unsafe APK asset name')
    require(0 < apk.stat().st_size <= 512 * 1024 * 1024, 'APK exceeds P03 size bounds')
    tool = tools()
    signature = run(tool / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', signature, re.M)
    require(len(certs) == 1 and certs[0].lower() == fingerprint(certificate), 'Unexpected APK signer')
    require('Verified using v2 scheme (APK Signature Scheme v2): true' in signature, 'APK Signature Scheme v2 required')
    run(tool / 'zipalign', '-c', '-p', '4', apk)
    badging = run(tool / 'aapt', 'dump', 'badging', apk)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    require(match is not None, 'Cannot read APK metadata')
    package, code, name = match.groups()
    require(package == PACKAGES[channel], 'Wrong package for distribution channel')
    require(0 < int(code) <= 2100000000, 'Invalid Android versionCode')
    require(name.strip() and len(name) <= 100 and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name), 'Invalid versionName')
    require((channel != 'dev' or name.endswith('-DEV')), 'DEV versionName suffix required')
    require("sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging, 'SDK changed')
    require('application-debuggable' not in badging, 'Distribution APK must not be debuggable')
    require('android.permission.REQUEST_INSTALL_PACKAGES' in badging, 'Installer permission missing')
    for permission in ('WRITE_EXTERNAL_STORAGE', 'MANAGE_EXTERNAL_STORAGE', 'QUERY_ALL_PACKAGES'):
        require('android.permission.' + permission not in badging, 'Forbidden broad permission')
    manifest = run(tool / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml')
    providers = [block for block in manifest.split('E: provider') if 'androidx.core.content.FileProvider' in block]
    require(len(providers) == 1, 'Expected one FileProvider')
    provider = providers[0].split('E: activity', 1)[0]
    require(f'="{package}.apkfiles"' in provider, 'Unexpected FileProvider identity')
    require(re.search(r'android:exported[^\n]*0x0\b', provider) is not None, 'FileProvider must not be exported')
    resources = run(tool / 'aapt', 'dump', '--values', 'resources', apk)
    entries = re.findall(r'resource 0x[0-9a-f]+ [^\s]+:xml/apk_paths:[^\n]*\n\s*\(string8\) "([^"]+)"', resources)
    require(len(entries) == 1, 'Expected a single APK paths resource')
    paths = run(tool / 'aapt', 'dump', 'xmltree', apk, entries[0])
    require(re.findall(r'E: ([\w-]+)', paths) == ['paths', 'files-path'] and
            'path="apk-downloads/verified/"' in paths, 'FileProvider path must remain restricted')
    return dict(schemaVersion=1, channel=channel, packageName=package, versionCode=int(code),
                versionName=name, apkAssetName=apk.name, sha256=digest(apk))


def validate_contract(actual, expected):
    require(actual == expected, 'Contract does not match the final signed APK')


def increasing(contract, previous):
    require(previous['channel'] == contract['channel'] and previous['packageName'] == contract['packageName'], 'Previous publication belongs to another identity')
    require(type(previous['versionCode']) is int and previous['versionCode'] > 0, 'Invalid previous versionCode')
    require(contract['versionCode'] > previous['versionCode'], 'versionCode must strictly increase')


def clean():
    require(not git('status', '--porcelain', '--untracked-files=all'), 'A clean tracked and untracked worktree is required')

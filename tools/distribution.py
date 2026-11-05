#!/usr/bin/env python3
"""Local, fail-closed APK/contract preparation. Never uploads, tags or publishes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

PACKAGES = {'release': 'es.robertodorado.robstore', 'dev': 'es.robertodorado.robstore.debug'}
CONTRACT = 'robstore-update.json'
RECEIPT = 'robstore-distribution.json'
REPO = 'robdor80/RobStore'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def run(*args):
    result = subprocess.run(list(map(str, args)), capture_output=True, text=True)
    # Do not echo subprocess output: a signing tool might include sensitive details.
    require(result.returncode == 0, f'Tool failed: {Path(str(args[0])).name}; inspect locally without sharing secrets')
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


def signer_certificates(signature):
    """Accept Android SDK and Termux signer labels without weakening certificate checks."""
    pattern = r'^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: ([a-fA-F0-9]{64})
    apk = Path(apk)
    require(apk.is_file() and 5 <= len(apk.name) <= 160 and
            re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*\.apk', apk.name), 'Unsafe APK asset name')
    require(0 < apk.stat().st_size <= 512 * 1024 * 1024, 'APK exceeds P03 size bounds')
    tool = tools()
    signature = run(tool / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
    certs = signer_certificates(signature)
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


def source_tree(ref):
    # Contract/receipt are not packaged by Android. Everything else must be identical.
    return '\n'.join(line for line in git('ls-tree', '-r', ref).splitlines()
                     if line.split('\t', 1)[1] not in (CONTRACT, RECEIPT))


def provenance(receipt, ref):
    source = receipt['sourceCommit']
    require(re.fullmatch('[0-9a-f]{40}', source) is not None, 'Invalid source commit')
    git('merge-base', '--is-ancestor', source, ref)
    require(source_tree(source) == source_tree(ref), 'Code/resources changed after the signed build')


def build(args):
    clean()
    require(os.environ.get('CI') != 'true' and os.environ.get('GITHUB_ACTIONS') != 'true', 'Local distribution only')
    source = git('rev-parse', 'HEAD')
    certificate = fingerprint(args.certificate)
    out = Path(args.output).resolve()
    root = Path(git('rev-parse', '--show-toplevel')).resolve()
    require(not out.is_relative_to(root), 'Output must be outside the repository')
    require(not out.exists(), 'Output directory already exists; never overwrite a signed candidate')
    task = ':app:assembleRelease' if args.channel == 'release' else ':app:assembleDebug'
    wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
    run(wrapper, task, f'-ProbstoreDistribution={args.channel}', '--no-daemon', '--no-configuration-cache', '--no-build-cache', '--rerun-tasks')
    clean()
    require(git('rev-parse', 'HEAD') == source, 'HEAD changed during build')
    variant = 'release' if args.channel == 'release' else 'debug'
    apk = Path(f'app/build/outputs/apk/{variant}/app-{variant}.apk')
    contract = inspect_apk(apk, args.channel, certificate)
    out.mkdir(parents=True)
    import shutil
    final = out / f"RobStore-{args.channel}-{contract['versionCode']}.apk"
    shutil.copyfile(apk, final)
    contract = inspect_apk(final, args.channel, certificate)
    write(out / CONTRACT, contract)
    write(out / RECEIPT, dict(schemaVersion=1, sourceCommit=source, channel=args.channel,
                              certificateSha256=certificate, apkSha256=contract['sha256']))
    (out / 'SHA256SUMS').write_text(f"{contract['sha256']}  {final.name}\n", encoding='utf-8')
    print('Signed candidate verified outside Git. No publication performed.')


def prepare(args):
    clean()
    directory = Path(args.candidate)
    contract = load(directory / CONTRACT)
    receipt = load(directory / RECEIPT)
    actual = inspect_apk(directory / contract['apkAssetName'], args.channel, args.certificate)
    validate_contract(contract, actual)
    require(receipt['apkSha256'] == actual['sha256'] and receipt['certificateSha256'] == fingerprint(args.certificate)
            and receipt['channel'] == args.channel, 'Candidate receipt mismatch')
    require(git('rev-parse', 'HEAD') == receipt['sourceCommit'], 'Prepare must run at the exact build commit')
    if args.previous:
        increasing(actual, load(args.previous))
    else:
        require(args.first_publication, 'Provide previous official contract or explicitly declare first publication')
    write(CONTRACT, actual)
    write(RECEIPT, receipt)
    print('Contract and provenance prepared. Review and commit ONLY these two files; do not rebuild.')


def published_contracts(channel):
    # gh --paginate emits one JSON array per page; support older MSI CLIs without --slurp.
    remaining = run('gh', 'api', '--paginate', f'repos/{REPO}/releases?per_page=100')
    pages = []
    decoder = json.JSONDecoder()
    while remaining.strip():
        page, end = decoder.raw_decode(remaining.lstrip())
        require(isinstance(page, list), 'Invalid Releases page')
        pages.append(page)
        remaining = remaining.lstrip()[end:]
    require(pages, 'No Releases response')
    found = []
    for release in (item for page in pages for item in page):
        if release['draft'] or release['prerelease'] != (channel == 'dev'):
            continue
        from urllib.parse import quote
        tag = release['tag_name']
        raw = run('gh', 'api', '-H', 'Accept: application/vnd.github.raw+json',
                  f'repos/{REPO}/contents/{CONTRACT}?ref={quote("refs/tags/" + tag, safe="")}')
        contract = json.loads(raw)
        require(contract['channel'] == channel and contract['packageName'] == PACKAGES[channel], 'Published channel has an incoherent contract; resolve manually')
        require(type(contract['versionCode']) is int and contract['versionCode'] > 0, 'Invalid published versionCode')
        assets = [a for a in release['assets'] if a['name'] == contract['apkAssetName']]
        require(len(assets) == 1 and assets[0]['state'] == 'uploaded', 'Previous publication has no exact APK asset')
        require(assets[0].get('digest') in (None, 'sha256:' + contract['sha256']), 'Previous asset digest mismatch')
        found.append(contract)
    return found


def preflight(args):
    clean()
    require(git('remote', 'get-url', 'origin') in (
        'https://github.com/robdor80/RobStore.git', 'git@github.com:robdor80/RobStore.git'), 'Unexpected origin repository')
    contract = load(CONTRACT)
    receipt = load(RECEIPT)
    actual = inspect_apk(args.apk, args.channel, args.certificate)
    validate_contract(contract, actual)
    require(receipt['schemaVersion'] == 1 and receipt['channel'] == args.channel
            and receipt['certificateSha256'] == fingerprint(args.certificate)
            and receipt['apkSha256'] == actual['sha256'], 'Committed provenance mismatch')
    provenance(receipt, 'HEAD')
    # Must be a distinct final commit whose sole differences are the contract/receipt.
    require(git('rev-parse', 'HEAD') != receipt['sourceCommit'], 'Commit the contract before preflight')
    remote_commit = json.loads(run('gh', 'api', f'repos/{REPO}/commits/' + git('rev-parse', 'HEAD')))
    require(remote_commit['sha'] == git('rev-parse', 'HEAD'), 'Final commit must be reviewable in the official repository')
    require(re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', args.tag) is not None if args.channel == 'release'
            else re.fullmatch(r'dev-[A-Za-z0-9][A-Za-z0-9._-]*', args.tag) is not None, 'Invalid stable/DEV tag')
    require(args.tag == 'v' + actual['versionName'] if args.channel == 'release'
            else args.tag == 'dev-' + actual['versionName'] + '-' + str(actual['versionCode']), 'Tag/version mismatch')
    require(not git('ls-remote', '--tags', 'origin', 'refs/tags/' + args.tag, 'refs/tags/' + args.tag + '^{}'), 'Public tag already exists; never move it')
    require(not git('tag', '--list', args.tag), 'Local tag already exists; inspect manually')
    previous = published_contracts(args.channel)
    if not previous:
        require(args.first_publication, 'No prior publication: explicit --first-publication required')
    for old in previous:
        increasing(actual, old)
    print(json.dumps(dict(result='READY FOR OWNER REVIEW; NO PUBLICATION PERFORMED',
                          repository=REPO, commit=git('rev-parse', 'HEAD'), tag=args.tag,
                          prerelease=args.channel == 'dev', contract=actual), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('build', 'prepare', 'preflight', 'verify'):
        child = sub.add_parser(command)
        child.add_argument('--channel', required=True, choices=PACKAGES)
        child.add_argument('--certificate', required=True, help='Public expected certificate SHA-256, no colons')
        if command == 'build':
            child.add_argument('--output', required=True)
        elif command == 'prepare':
            child.add_argument('--candidate', required=True)
            previous = child.add_mutually_exclusive_group(required=True)
            previous.add_argument('--previous', help='Previous official contract for this package')
            previous.add_argument('--first-publication', action='store_true')
        else:
            child.add_argument('--apk', required=True)
            if command == 'preflight':
                child.add_argument('--tag', required=True)
                child.add_argument('--first-publication', action='store_true')
            else:
                child.add_argument('--contract', required=True)
    args = parser.parse_args()
    if args.command == 'verify':
        validate_contract(load(args.contract), inspect_apk(args.apk, args.channel, args.certificate))
        print('Signature, identity, SDK, alignment and contract: PASS')
    else:
        globals()[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, json.JSONDecodeError) as error:
        # Do not print JSON/file/tool content: it can be private configuration.
        print('Distribution blocked: ' + (str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)

    return re.findall(pattern, signature, re.M)


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


def source_tree(ref):
    # Contract/receipt are not packaged by Android. Everything else must be identical.
    return '\n'.join(line for line in git('ls-tree', '-r', ref).splitlines()
                     if line.split('\t', 1)[1] not in (CONTRACT, RECEIPT))


def provenance(receipt, ref):
    source = receipt['sourceCommit']
    require(re.fullmatch('[0-9a-f]{40}', source) is not None, 'Invalid source commit')
    git('merge-base', '--is-ancestor', source, ref)
    require(source_tree(source) == source_tree(ref), 'Code/resources changed after the signed build')


def build(args):
    clean()
    require(os.environ.get('CI') != 'true' and os.environ.get('GITHUB_ACTIONS') != 'true', 'Local distribution only')
    source = git('rev-parse', 'HEAD')
    certificate = fingerprint(args.certificate)
    out = Path(args.output).resolve()
    root = Path(git('rev-parse', '--show-toplevel')).resolve()
    require(not out.is_relative_to(root), 'Output must be outside the repository')
    require(not out.exists(), 'Output directory already exists; never overwrite a signed candidate')
    task = ':app:assembleRelease' if args.channel == 'release' else ':app:assembleDebug'
    wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
    run(wrapper, task, f'-ProbstoreDistribution={args.channel}', '--no-daemon', '--no-configuration-cache', '--no-build-cache', '--rerun-tasks')
    clean()
    require(git('rev-parse', 'HEAD') == source, 'HEAD changed during build')
    variant = 'release' if args.channel == 'release' else 'debug'
    apk = Path(f'app/build/outputs/apk/{variant}/app-{variant}.apk')
    contract = inspect_apk(apk, args.channel, certificate)
    out.mkdir(parents=True)
    import shutil
    final = out / f"RobStore-{args.channel}-{contract['versionCode']}.apk"
    shutil.copyfile(apk, final)
    contract = inspect_apk(final, args.channel, certificate)
    write(out / CONTRACT, contract)
    write(out / RECEIPT, dict(schemaVersion=1, sourceCommit=source, channel=args.channel,
                              certificateSha256=certificate, apkSha256=contract['sha256']))
    (out / 'SHA256SUMS').write_text(f"{contract['sha256']}  {final.name}\n", encoding='utf-8')
    print('Signed candidate verified outside Git. No publication performed.')


def prepare(args):
    clean()
    directory = Path(args.candidate)
    contract = load(directory / CONTRACT)
    receipt = load(directory / RECEIPT)
    actual = inspect_apk(directory / contract['apkAssetName'], args.channel, args.certificate)
    validate_contract(contract, actual)
    require(receipt['apkSha256'] == actual['sha256'] and receipt['certificateSha256'] == fingerprint(args.certificate)
            and receipt['channel'] == args.channel, 'Candidate receipt mismatch')
    require(git('rev-parse', 'HEAD') == receipt['sourceCommit'], 'Prepare must run at the exact build commit')
    if args.previous:
        increasing(actual, load(args.previous))
    else:
        require(args.first_publication, 'Provide previous official contract or explicitly declare first publication')
    write(CONTRACT, actual)
    write(RECEIPT, receipt)
    print('Contract and provenance prepared. Review and commit ONLY these two files; do not rebuild.')


def published_contracts(channel):
    # gh --paginate emits one JSON array per page; support older MSI CLIs without --slurp.
    remaining = run('gh', 'api', '--paginate', f'repos/{REPO}/releases?per_page=100')
    pages = []
    decoder = json.JSONDecoder()
    while remaining.strip():
        page, end = decoder.raw_decode(remaining.lstrip())
        require(isinstance(page, list), 'Invalid Releases page')
        pages.append(page)
        remaining = remaining.lstrip()[end:]
    require(pages, 'No Releases response')
    found = []
    for release in (item for page in pages for item in page):
        if release['draft'] or release['prerelease'] != (channel == 'dev'):
            continue
        from urllib.parse import quote
        tag = release['tag_name']
        raw = run('gh', 'api', '-H', 'Accept: application/vnd.github.raw+json',
                  f'repos/{REPO}/contents/{CONTRACT}?ref={quote("refs/tags/" + tag, safe="")}')
        contract = json.loads(raw)
        require(contract['channel'] == channel and contract['packageName'] == PACKAGES[channel], 'Published channel has an incoherent contract; resolve manually')
        require(type(contract['versionCode']) is int and contract['versionCode'] > 0, 'Invalid published versionCode')
        assets = [a for a in release['assets'] if a['name'] == contract['apkAssetName']]
        require(len(assets) == 1 and assets[0]['state'] == 'uploaded', 'Previous publication has no exact APK asset')
        require(assets[0].get('digest') in (None, 'sha256:' + contract['sha256']), 'Previous asset digest mismatch')
        found.append(contract)
    return found


def preflight(args):
    clean()
    require(git('remote', 'get-url', 'origin') in (
        'https://github.com/robdor80/RobStore.git', 'git@github.com:robdor80/RobStore.git'), 'Unexpected origin repository')
    contract = load(CONTRACT)
    receipt = load(RECEIPT)
    actual = inspect_apk(args.apk, args.channel, args.certificate)
    validate_contract(contract, actual)
    require(receipt['schemaVersion'] == 1 and receipt['channel'] == args.channel
            and receipt['certificateSha256'] == fingerprint(args.certificate)
            and receipt['apkSha256'] == actual['sha256'], 'Committed provenance mismatch')
    provenance(receipt, 'HEAD')
    # Must be a distinct final commit whose sole differences are the contract/receipt.
    require(git('rev-parse', 'HEAD') != receipt['sourceCommit'], 'Commit the contract before preflight')
    remote_commit = json.loads(run('gh', 'api', f'repos/{REPO}/commits/' + git('rev-parse', 'HEAD')))
    require(remote_commit['sha'] == git('rev-parse', 'HEAD'), 'Final commit must be reviewable in the official repository')
    require(re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', args.tag) is not None if args.channel == 'release'
            else re.fullmatch(r'dev-[A-Za-z0-9][A-Za-z0-9._-]*', args.tag) is not None, 'Invalid stable/DEV tag')
    require(args.tag == 'v' + actual['versionName'] if args.channel == 'release'
            else args.tag == 'dev-' + actual['versionName'] + '-' + str(actual['versionCode']), 'Tag/version mismatch')
    require(not git('ls-remote', '--tags', 'origin', 'refs/tags/' + args.tag, 'refs/tags/' + args.tag + '^{}'), 'Public tag already exists; never move it')
    require(not git('tag', '--list', args.tag), 'Local tag already exists; inspect manually')
    previous = published_contracts(args.channel)
    if not previous:
        require(args.first_publication, 'No prior publication: explicit --first-publication required')
    for old in previous:
        increasing(actual, old)
    print(json.dumps(dict(result='READY FOR OWNER REVIEW; NO PUBLICATION PERFORMED',
                          repository=REPO, commit=git('rev-parse', 'HEAD'), tag=args.tag,
                          prerelease=args.channel == 'dev', contract=actual), indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    for command in ('build', 'prepare', 'preflight', 'verify'):
        child = sub.add_parser(command)
        child.add_argument('--channel', required=True, choices=PACKAGES)
        child.add_argument('--certificate', required=True, help='Public expected certificate SHA-256, no colons')
        if command == 'build':
            child.add_argument('--output', required=True)
        elif command == 'prepare':
            child.add_argument('--candidate', required=True)
            previous = child.add_mutually_exclusive_group(required=True)
            previous.add_argument('--previous', help='Previous official contract for this package')
            previous.add_argument('--first-publication', action='store_true')
        else:
            child.add_argument('--apk', required=True)
            if command == 'preflight':
                child.add_argument('--tag', required=True)
                child.add_argument('--first-publication', action='store_true')
            else:
                child.add_argument('--contract', required=True)
    args = parser.parse_args()
    if args.command == 'verify':
        validate_contract(load(args.contract), inspect_apk(args.apk, args.channel, args.certificate))
        print('Signature, identity, SDK, alignment and contract: PASS')
    else:
        globals()[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, KeyError, OSError, json.JSONDecodeError) as error:
        # Do not print JSON/file/tool content: it can be private configuration.
        print('Distribution blocked: ' + (str(error) if isinstance(error, ValueError) and not isinstance(error, json.JSONDecodeError) else type(error).__name__), file=sys.stderr)
        sys.exit(1)

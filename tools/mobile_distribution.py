#!/usr/bin/env python3
"""Reusable Android Actions pipeline. Signing never executes app/Gradle code.
One explicit RobPublisher dispatch can build, sign and publish in the same run.
"""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.parse import quote
import distribution as d

OWNER = 'robdor80'
CONFIG = '.github/android-distribution.json'
CLOSURE_FILES = ('robstore-update.json', 'robstore-distribution.json', 'robstore.json', 'robstore/icon.png')


def api(endpoint, method='GET', data=None, raw=False, missing_ok=False):
    command = ['gh', 'api', '--method', method]
    if raw:
        command += ['-H', 'Accept: application/vnd.github.raw+json']
    if data is not None:
        command += ['--input', '-']
    result = subprocess.run(command + [endpoint], input=None if data is None else json.dumps(data),
                            text=True, capture_output=True)
    if result.returncode and missing_ok and '(HTTP 404)' in result.stderr:
        return None
    d.require(result.returncode == 0, 'GitHub API refused the operation; private details suppressed')
    return result.stdout if raw else json.loads(result.stdout or 'null')


def pages(endpoint):
    result = []
    for page in range(1, 1001):
        part = api(f'{endpoint}{"&" if "?" in endpoint else "?"}per_page=100&page={page}')
        d.require(isinstance(part, list), 'Invalid GitHub listing')
        result += part
        if len(part) < 100:
            return result
    raise ValueError('GitHub pagination limit exceeded')


def raw_file(repo, ref, path, missing_ok=False):
    return api(f'repos/{repo}/contents/{path}?ref={quote(ref, safe="")}', raw=True, missing_ok=missing_ok)


def config(data, repo):
    d.require(type(data['schemaVersion']) is int and data['schemaVersion'] == 1 and data['repository'] == repo, 'Configuration repository mismatch')
    d.require(re.fullmatch(r'robdor80/[A-Za-z0-9_.-]+', repo), 'Only owner personal repositories are allowed')
    for channel in ('release', 'dev'):
        d.require(re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', data['packages'][channel]), 'Invalid package')
    d.require(data['packages']['dev'] == data['packages']['release'] + '.debug', 'DEV identity must remain separate')
    d.require(re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]*', data['module']), 'Invalid Android module')
    d.require(type(data['minSdk']) is int and type(data['targetSdk']) is int and
              26 <= data['minSdk'] <= data['targetSdk'] <= 36, 'Unsupported SDK policy')
    d.require(isinstance(data['robstorePolicy'], bool), 'Explicit verification policy required')
    for key in ('iconSource', 'notesPath'):
        d.require(re.fullmatch(r'[A-Za-z0-9_.\-/]+', data[key]) and not data[key].startswith('/') and
                  all(p not in ('', '.', '..') for p in data[key].split('/')), 'Unsafe source path')
    d.require(isinstance(data['name'], str) and data['name'].strip() and len(data['name']) <= 120 and
              isinstance(data['description'], str) and len(data['description']) <= 4000 and
              all(not (ord(c) < 32 or 127 <= ord(c) <= 159) or c in '\n\r\t' for c in data['name'] + data['description']), 'Invalid catalog text')
    return data


def identity(event, repo, source, channel, tag=''):
    d.require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Only explicit workflow_dispatch is allowed')
    d.require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Only main can distribute')
    d.require(os.environ.get('GITHUB_ACTOR') == OWNER and os.environ.get('GITHUB_TRIGGERING_ACTOR', OWNER) == OWNER,
              'Only the owner may dispatch or rerun distribution')
    d.require(repo.startswith(OWNER + '/') and os.environ.get('GITHUB_REPOSITORY') == repo, 'Wrong repository')
    d.require(re.fullmatch('[0-9a-f]{40}', source), 'Full source commit SHA required')
    d.require(event['repository']['full_name'] == repo and not event['repository']['fork'], 'Fork distribution forbidden')
    d.require(event['repository']['default_branch'] == 'main', 'Main branch required')
    d.require(channel in ('release', 'dev'), 'Invalid channel')
    if tag:
        validate_tag(channel, tag)


def validate_tag(channel, tag):
    pattern = r'v[A-Za-z0-9][A-Za-z0-9._-]*' if channel == 'release' else r'dev-[A-Za-z0-9][A-Za-z0-9._-]*'
    d.require(re.fullmatch(pattern, tag), 'Unsafe channel tag')


def protected_environment(repo, name):
    environment = api(f'repos/{repo}/environments/{name}')
    d.require(all(rule['type'] == 'branch_policy' for rule in environment.get('protection_rules', [])),
              'Remove environment approvals, wait timers and deployment protection rules; see docs/RS-05-AUTOMATICO.md')
    policy = environment.get('deployment_branch_policy') or {}
    d.require(policy.get('custom_branch_policies') is True and policy.get('protected_branches') is False,
              'Explicit environment main-only policy required')
    policies = api(f'repos/{repo}/environments/{name}/deployment-branch-policies')['branch_policies']
    d.require(len(policies) == 1 and policies[0]['name'] == 'main' and policies[0]['type'] == 'branch',
              'Environment must allow only branch main, never tags or PR branches')


def guard(args):
    event = d.load(os.environ['GITHUB_EVENT_PATH'])
    repo = os.environ['GITHUB_REPOSITORY']
    identity(event, repo, args.source, args.channel, args.tag)
    main = api(f'repos/{repo}/branches/main')
    d.require(main['commit']['sha'] == args.source, 'Source must equal current reviewed main HEAD')
    # Tests and lint run in this operation's unsigned build, before accessing keys.
    for name in ('android-' + args.channel, 'android-publish'):
        protected_environment(repo, name)
    settings = config(json.loads(raw_file(repo, args.source, CONFIG)), repo)
    d.write(args.config_output, settings)
    print('Owner, source and automatic environment policy: PASS; no keys accessed')


def unsigned(args):
    settings = config(d.load(CONFIG), os.environ['GITHUB_REPOSITORY'])
    d.require(d.git('rev-parse', 'HEAD') == args.source, 'Wrong build checkout')
    d.clean()
    variant = 'release' if args.channel == 'release' else 'debug'
    engine = Path(__file__).resolve().parent
    d.run('./gradlew', f":{settings['module']}:test{variant.title()}UnitTest",
          f":{settings['module']}:lint{variant.title()}", f":{settings['module']}:assemble{variant.title()}", '-I', engine / 'unsigned-distribution.gradle',
          '--no-daemon', '--no-configuration-cache', '--no-build-cache')
    d.clean()
    output = Path(args.directory)
    output.mkdir(parents=True, exist_ok=False)
    source = Path(f"{settings['module']}/build/outputs/apk/{variant}/{settings['module']}-{variant}-unsigned.apk")
    d.require(source.is_file(), 'Expected one standard unsigned Android APK')
    shutil.copyfile(source, output / 'unsigned.apk')
    print('Unsigned build prepared without signing secrets')



def verify_signer_output(signature, certificate):
    # Android SDK apksigner prints "Signer #1 ..."; Termux's native build prints
    # "V3.0 Signer: ...". Accept only these explicit formats while preserving
    # the original single-signer and exact SHA-256 pin requirements.
    certs = re.findall(
        r'^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: ([a-fA-F0-9]{64})$',
        signature,
        re.M,
    )
    d.require(len(certs) == 1 and certs[0].lower() == d.fingerprint(certificate), 'Unexpected APK signer')


def inspect(apk, channel, certificate, settings):
    if settings['robstorePolicy']:
        d.require(settings['repository'] == d.REPO and settings['packages'] == d.PACKAGES,
                  'RobStore verification policy cannot be repurposed')
        return d.inspect_apk(apk, channel, certificate)
    tool = d.tools()
    signature = d.run(tool / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
    verify_signer_output(signature, certificate)
    d.require('Verified using v2 scheme (APK Signature Scheme v2): true' in signature, 'APK v2 signature required')
    d.run(tool / 'zipalign', '-c', '-p', '4', apk)
    badging = d.run(tool / 'aapt', 'dump', 'badging', apk)
    match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    d.require(match is not None, 'APK metadata unavailable')
    package, code, name = match.groups()
    d.require(package == settings['packages'][channel] and 0 < int(code) <= 2100000000, 'Wrong package/versionCode')
    d.require(name.strip() and len(name) <= 100 and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in name), 'Invalid versionName')
    d.require(channel != 'dev' or name.endswith('-DEV'), 'DEV versionName suffix required')
    d.require('application-debuggable' not in badging and
              f"sdkVersion:'{settings['minSdk']}'" in badging and f"targetSdkVersion:'{settings['targetSdk']}'" in badging,
              'Distribution must not be debuggable and must match SDK policy')
    d.require(0 < Path(apk).stat().st_size <= 512 * 1024 * 1024, 'APK size exceeds catalog bounds')
    return dict(schemaVersion=1, channel=channel, packageName=package, versionCode=int(code),
                versionName=name, apkAssetName=Path(apk).name, sha256=d.digest(apk))


def legacy_baselines(repo):
    # Original Android identities of applications distributed before RS-05.
    registry = d.load(Path(__file__).with_name('android-original-identities.json'))
    d.require(registry['schemaVersion'] == 1, 'Unsupported legacy registry')
    entries = registry['applications']
    d.require(len({(b['repository'], b['tag']) for b in entries}) == len(entries), 'Duplicate historical identities')
    return [b for b in entries if b['repository'] == repo]


def historical_certificate(repo, channel, certificate):
    if channel == 'release':
        for baseline in legacy_baselines(repo):
            d.require(d.fingerprint(certificate) == baseline['certificateSha256'],
                      'Release identity must match the approved historical APK; never generate a replacement')


def previous_apk(repo, release, contract):
    """First RS-05 update of an existing app: derive compatibility from its APK, no manual registry."""
    codes = []
    for asset in release['assets']:
        if not asset['name'].lower().endswith('.apk'):
            continue
        d.require(0 < asset['size'] <= 512 * 1024 * 1024, 'Previous APK size invalid')
        with tempfile.TemporaryDirectory(prefix='previous-apk-') as folder:
            apk = Path(folder) / 'previous.apk'
            with apk.open('wb') as stream:
                result = subprocess.run(['gh', 'api', '-H', 'Accept: application/octet-stream',
                    f"repos/{repo}/releases/assets/{asset['id']}"], stdout=stream, stderr=subprocess.PIPE)
            d.require(result.returncode == 0 and apk.stat().st_size == asset['size'], 'Previous APK download failed')
            metadata = d.run(d.tools() / 'aapt', 'dump', 'badging', apk)
            match = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", metadata, re.M)
            d.require(match is not None, 'Previous APK version unavailable')
            if match[1] != contract['packageName']:
                continue
            signature = d.run(d.tools() / 'apksigner', 'verify', '--print-certs', apk)
            verify_signer_output(signature, os.environ['CERTIFICATE_SHA256'])
            codes.append(int(match[2]))
    d.require(codes, 'Previous Release has no compatible APK; keep its original signing key')
    return dict(channel=contract['channel'], packageName=contract['packageName'], versionCode=max(codes))


def increasing(repo, contract):
    baselines = legacy_baselines(repo) if contract['channel'] == 'release' else []
    for baseline in baselines:
        d.increasing(contract, dict(channel='release', packageName=baseline['packageName'], versionCode=baseline['versionCode']))
    releases = [release for release in pages(f'repos/{repo}/releases')
                if not release['draft'] and release['prerelease'] == (contract['channel'] == 'dev')]
    # Once adopted, the latest RS-05 contract carries the growing Android versionCode.
    releases.sort(key=lambda release: release.get('published_at') or '', reverse=True)
    for release in releases:
        body = raw_file(repo, 'refs/tags/' + release['tag_name'], d.CONTRACT, missing_ok=True)
        if body is not None:
            d.increasing(contract, json.loads(body))
            break
        original = next((baseline for baseline in baselines if baseline['tag'] == release['tag_name']), None)
        previous = (dict(channel='release', packageName=original['packageName'], versionCode=original['versionCode'])
                    if original else previous_apk(repo, release, contract))
        d.increasing(contract, previous)
        # Before the first RS-05 contract, check all old APK versions automatically.


def expected_tag(contract):
    # Android only requires a growing versionCode; versionName need not be unique or semantic.
    version = re.sub(r'[^A-Za-z0-9._-]+', '-', contract['versionName']).replace('..', '-').strip('.-_') or 'build'
    prefix = 'v' if contract['channel'] == 'release' else 'dev-'
    return f"{prefix}{version}-{contract['versionCode']}"


def sign(args):
    settings = config(d.load(args.config_output), os.environ['GITHUB_REPOSITORY'])
    certificate = d.fingerprint(os.environ['CERTIFICATE_SHA256'])
    historical_certificate(settings['repository'], args.channel, certificate)
    # Older setups may have a distinct key password; new PKCS12 keys need only one.
    if not os.environ.get('SIGNING_ALIAS'):
        os.environ['SIGNING_ALIAS'] = 'android-' + args.channel
    if not os.environ.get('SIGNING_KEY_PASSWORD'):
        os.environ['SIGNING_KEY_PASSWORD'] = os.environ.get('SIGNING_STORE_PASSWORD', '')
    encoded = os.environ['SIGNING_KEYSTORE_B64']
    d.require(0 < len(encoded) < 1400000 and all(os.environ.get(k) for k in
              ('SIGNING_STORE_PASSWORD', 'SIGNING_KEY_PASSWORD', 'SIGNING_ALIAS')), 'Private signing configuration missing')
    key = base64.b64decode(encoded, validate=True)
    output = Path(args.directory)
    output.mkdir(parents=True, exist_ok=False)
    apk = output / f"Android-{args.channel}.apk"
    with tempfile.TemporaryDirectory(prefix='android-private-', dir=os.environ.get('RUNNER_TEMP')) as private:
        store = Path(private) / 'signing.p12'
        store.write_bytes(key)
        store.chmod(0o600)
        d.run(d.tools() / 'apksigner', 'sign', '--ks', store, '--ks-key-alias', os.environ['SIGNING_ALIAS'],
              '--ks-pass', 'env:SIGNING_STORE_PASSWORD', '--key-pass', 'env:SIGNING_KEY_PASSWORD',
              '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--out', apk, args.apk)
        contract = inspect(apk, args.channel, certificate, settings)
    # The directory above is gone before any artifact upload. No Gradle in this job.
    args.tag = expected_tag(contract)
    validate_tag(args.channel, args.tag)
    d.write(output / d.CONTRACT, contract)
    d.write(output / d.RECEIPT, dict(schemaVersion=1, sourceCommit=args.source, channel=args.channel,
                                    certificateSha256=certificate, apkSha256=contract['sha256'],
                                    repository=settings['repository'], preparationRun=os.environ['GITHUB_RUN_ID'], tag=args.tag))
    (output / 'SHA256SUMS').write_text(f"{contract['sha256']}  {apk.name}\n")
    if os.environ.get('GITHUB_OUTPUT'):
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            stream.write(f'tag={args.tag}\n')
    print(json.dumps(dict(result='SIGNED', source=args.source, tag=args.tag, contract=contract), indent=2))
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with Path(summary).open('a') as stream:
            stream.write(f"## APK firmado y verificado\n\nCommit: `{args.source}`\n\nTag: `{args.tag}`\n\nSHA-256 del APK: `{contract['sha256']}`\n\nRun de preparación: `{os.environ['GITHUB_RUN_ID']}`\n")


def candidate(args, settings):
    directory = Path(args.directory)
    contract = d.load(directory / d.CONTRACT)
    receipt = d.load(directory / d.RECEIPT)
    d.require(set(p.name for p in directory.iterdir()) <= {d.CONTRACT, d.RECEIPT, 'SHA256SUMS', f'Android-{args.channel}.apk', f'Android-{args.channel}.apk.idsig'}, 'Unexpected candidate files')
    d.require(contract['apkAssetName'] == f'Android-{args.channel}.apk', 'Unexpected asset name')
    apk = directory / contract['apkAssetName']
    historical_certificate(settings['repository'], args.channel, os.environ['CERTIFICATE_SHA256'])
    actual = inspect(apk, args.channel, os.environ['CERTIFICATE_SHA256'], settings)
    d.validate_contract(contract, actual)
    run = os.environ.get('GITHUB_RUN_ID', '')
    d.require(run.isdecimal() and int(run) > 0, 'Current Actions run required')
    d.require(receipt == dict(schemaVersion=1, sourceCommit=args.source, channel=args.channel,
                             certificateSha256=d.fingerprint(os.environ['CERTIFICATE_SHA256']), apkSha256=contract['sha256'],
                             repository=settings['repository'], preparationRun=run, tag=args.tag), 'Candidate provenance mismatch')
    d.require(args.tag == expected_tag(contract), 'Wrong publication tag')
    d.require((directory / 'SHA256SUMS').read_text() == f"{contract['sha256']}  {apk.name}\n", 'Checksum manifest mismatch')
    return contract, receipt, apk


def publication_files(settings, source, contract, receipt):
    repo = settings['repository']
    files = {d.CONTRACT: json.dumps(contract, indent=2) + '\n', d.RECEIPT: json.dumps(receipt, indent=2) + '\n'}
    if contract['channel'] == 'release':
        icon = api(f'repos/{repo}/contents/{settings["iconSource"]}?ref={source}')
        d.require(icon['encoding'] == 'base64' and icon['size'] <= 512 * 1024, 'Invalid catalog icon')
        body = base64.b64decode(icon['content'])
        d.require(body.startswith(b'\x89PNG\r\n\x1a\n'), 'Catalog icon must be PNG')
        files['robstore/icon.png'] = body
        files['robstore.json'] = json.dumps(dict(schemaVersion=1, name=settings['name'], description=settings['description'],
            packageName=contract['packageName'], icon='robstore/icon.png', apkAssetName=contract['apkAssetName'],
            versionCode=contract['versionCode'], versionName=contract['versionName']), indent=2, ensure_ascii=False) + '\n'
    return files


def publish(args):
    # Recheck the explicit order and source before writing; no rebuild or signing here.
    guard(args)
    settings = config(d.load(args.config_output), os.environ['GITHUB_REPOSITORY'])
    contract, receipt, apk = candidate(args, settings)
    repo = settings['repository']
    increasing(repo, contract)
    existing_tags = {tag['name'] for tag in pages(f'repos/{repo}/tags')}
    d.require(args.tag not in [b['name'] for b in pages(f'repos/{repo}/branches')], 'Tag name collides with a branch')
    matches = [release for release in pages(f'repos/{repo}/releases') if release['tag_name'] == args.tag]
    d.require(not any(not release['draft'] for release in matches), 'This version is already published')
    draft = matches[0] if matches else None
    try:
        notes = raw_file(repo, args.source, settings['notesPath'])
    except ValueError:
        notes = ''
    if not notes.strip() or '<!-- NOT_FOR_PUBLICATION -->' in notes:
        notes = settings['name'] + ' ' + args.tag
    files = publication_files(settings, args.source, contract, receipt)
    d.require(set(files).issubset(CLOSURE_FILES), 'Only publication closure files may change')
    if not args.execute:
        print('DRY PLAN: validated candidate; no commit, tag, Release or upload created')
        return
    source = api(f'repos/{repo}/git/commits/{args.source}')
    entries = []
    for name, body in files.items():
        blob = api(f'repos/{repo}/git/blobs', 'POST', dict(content=base64.b64encode(body if isinstance(body, bytes) else body.encode()).decode(), encoding='base64'))
        entries.append(dict(path=name, mode='100644', type='blob', sha=blob['sha']))
    tree = api(f'repos/{repo}/git/trees', 'POST', dict(base_tree=source['tree']['sha'], tree=entries))
    commit = api(f'repos/{repo}/git/commits', 'POST', dict(message=f"Prepare verified Android distribution {args.tag}; built from {args.source}", tree=tree['sha'], parents=[args.source]))
    changed = api(f'repos/{repo}/compare/{args.source}...{commit["sha"]}')
    d.require(changed['commits'] and all(f['filename'] in files and f['status'] in ('added', 'modified') for f in changed['files']), 'Closure commit changed code/resources')
    d.require(api(f'repos/{repo}/branches/main')['commit']['sha'] == args.source, 'Main changed; no tag or Release created')
    # No branch is moved. The tag makes this closure commit reachable.
    tag = api(f'repos/{repo}/git/tags', 'POST', dict(tag=args.tag, message=f"Signed APK SHA-256 {contract['sha256']}; source {args.source}", object=commit['sha'], type='commit'))
    if args.tag in existing_tags:
        # Only unpublished tags reach this point; retry without asking for manual cleanup.
        api(f'repos/{repo}/git/refs/tags/{args.tag}', 'PATCH', dict(sha=tag['sha'], force=True))
    else:
        api(f'repos/{repo}/git/refs', 'POST', dict(ref='refs/tags/' + args.tag, sha=tag['sha']))
    metadata = dict(tag_name=args.tag, target_commitish=commit['sha'], name=args.tag,
        body=notes + f"\n\nSource: `{args.source}`\nPublication commit: `{commit['sha']}`\nAPK SHA-256: `{contract['sha256']}`\n",
        draft=True, prerelease=args.channel == 'dev')
    if draft:
        api(f'repos/{repo}/releases/{draft["id"]}', 'PATCH', metadata)
        release = draft
    else:
        release = api(f'repos/{repo}/releases', 'POST', metadata)
    for file in (apk, Path(args.directory) / 'SHA256SUMS'):
        for attempt in range(3):
            result = subprocess.run(['gh', 'release', 'upload', args.tag, str(file), '--repo', repo, '--clobber'], capture_output=True)
            if result.returncode == 0:
                break
            if attempt < 2:
                time.sleep(2)
        d.require(result.returncode == 0, 'Upload failed after automatic retries; the draft can resume from RobPublisher')
    uploaded = api(f'repos/{repo}/releases/{release["id"]}')
    assets = [a for a in uploaded['assets'] if a['name'] == apk.name]
    d.require(len(assets) == 1 and assets[0]['state'] == 'uploaded' and assets[0]['size'] == apk.stat().st_size and
              assets[0].get('digest') == 'sha256:' + contract['sha256'], 'Uploaded APK digest mismatch; draft retained')
    tagged = json.loads(raw_file(repo, 'refs/tags/' + args.tag, d.CONTRACT))
    d.validate_contract(tagged, contract)
    d.require(api(f'repos/{repo}/branches/main')['commit']['sha'] == args.source, 'Main changed; the next order can resume the unpublished draft')
    api(f'repos/{repo}/releases/{release["id"]}', 'PATCH', dict(draft=False))
    print('Authorized publication completed: ' + release['html_url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('guard', 'unsigned', 'sign', 'publish'))
    for name, default in [('source', ''), ('channel', 'release'), ('tag', ''),
                          ('config-output', ''), ('directory', ''), ('apk', '')]:
        parser.add_argument('--' + name, default=default)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    globals()[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never echo API bodies, subprocess output, secret values or malformed keystore bytes.
        print('Android distribution blocked: ' + (str(error) if type(error) is ValueError else type(error).__name__), file=sys.stderr)
        sys.exit(1)

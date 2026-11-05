#!/usr/bin/env python3
"""Reusable Android Actions pipeline. Signing never executes app/Gradle code.
Publication is a separate, explicitly authorized operation; default is preparation.
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
from urllib.parse import quote
import distribution as d

OWNER = 'robdor80'
CONFIG = '.github/android-distribution.json'
CLOSURE_FILES = ('robstore-update.json', 'robstore-distribution.json', 'robstore.json', 'robstore/icon.png')


def api(endpoint, method='GET', data=None, raw=False):
    command = ['gh', 'api', '--method', method]
    if raw:
        command += ['-H', 'Accept: application/vnd.github.raw+json']
    if data is not None:
        command += ['--input', '-']
    result = subprocess.run(command + [endpoint], input=None if data is None else json.dumps(data),
                            text=True, capture_output=True)
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


def raw_file(repo, ref, path):
    return api(f'repos/{repo}/contents/{path}?ref={quote(ref, safe="")}', raw=True)


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


def identity(event, repo, source, operation, channel, tag, confirmation):
    d.require(os.environ.get('GITHUB_EVENT_NAME') == 'workflow_dispatch', 'Only manual workflow_dispatch is allowed')
    d.require(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'Only main can distribute')
    d.require(os.environ.get('GITHUB_ACTOR') == OWNER and os.environ.get('GITHUB_TRIGGERING_ACTOR', OWNER) == OWNER,
              'Only the owner may dispatch or rerun distribution')
    d.require(repo.startswith(OWNER + '/') and os.environ.get('GITHUB_REPOSITORY') == repo, 'Wrong repository')
    d.require(re.fullmatch('[0-9a-f]{40}', source), 'Full source commit SHA required')
    d.require(event['repository']['full_name'] == repo and not event['repository']['fork'], 'Fork distribution forbidden')
    d.require(event['repository']['default_branch'] == 'main', 'Reviewed main branch required')
    d.require(operation in ('prepare', 'publish') and channel in ('release', 'dev'), 'Invalid operation/channel')
    pattern = r'v[0-9]+\.[0-9]+\.[0-9]+' if channel == 'release' else r'dev-[A-Za-z0-9][A-Za-z0-9._-]*'
    d.require(re.fullmatch(pattern, tag), 'Unsafe channel tag')
    if operation == 'publish':
        d.require(confirmation == 'PUBLICAR ' + tag, 'Explicit publication confirmation required')


def protected_environment(repo, name):
    environment = api(f'repos/{repo}/environments/{name}')
    reviews = [r for r in environment.get('protection_rules', []) if r['type'] == 'required_reviewers']
    d.require(any(any(x['reviewer'].get('login') == OWNER and x['type'] == 'User'
                      for x in r.get('reviewers', [])) for r in reviews), 'Owner environment review is mandatory')
    policy = environment.get('deployment_branch_policy') or {}
    d.require(policy.get('custom_branch_policies') is True and policy.get('protected_branches') is False,
              'Explicit environment main-only policy required')
    policies = api(f'repos/{repo}/environments/{name}/deployment-branch-policies')['branch_policies']
    d.require(len(policies) == 1 and policies[0]['name'] == 'main' and policies[0]['type'] == 'branch',
              'Environment must allow only branch main, never tags or PR branches')


def guard(args):
    d.require(os.environ.get('DISTRIBUTION_ENABLED') == 'true', 'Owner setup and backup verification required')
    tooling = os.environ.get('TOOLING_SHA', '')
    d.require(re.fullmatch('[0-9a-f]{40}', tooling), 'Pinned tooling SHA required')
    reviewed = api(f'repos/{d.REPO}/compare/{tooling}...main')
    d.require(reviewed['status'] in ('ahead', 'identical'), 'Tooling must belong to reviewed central main history')
    event = d.load(os.environ['GITHUB_EVENT_PATH'])
    repo = os.environ['GITHUB_REPOSITORY']
    identity(event, repo, args.source, args.operation, args.channel, args.tag, args.confirmation)
    main = api(f'repos/{repo}/branches/main')
    d.require(main['commit']['sha'] == args.source, 'Source must equal current reviewed main HEAD')
    # Workflow approval is separate from the CI; both are required.
    runs = api(f'repos/{repo}/actions/workflows/android.yml/runs?head_sha={args.source}&event=push&per_page=100')['workflow_runs']
    matching = [r for r in runs if r['head_sha'] == args.source and r['head_branch'] == 'main' and r['head_repository']['full_name'] == repo]
    d.require(matching and matching[0]['conclusion'] == 'success', 'Latest main Android CI must have succeeded')
    protected_environment(repo, 'android-' + args.channel if args.operation == 'prepare' else 'android-publish')
    settings = config(json.loads(raw_file(repo, args.source, CONFIG)), repo)
    notes = raw_file(repo, args.source, settings['notesPath'])
    d.require(0 < len(notes.strip()) <= 32000 and '<!-- NOT_FOR_PUBLICATION -->' not in notes,
              'Approve real versioned release notes before preparing a candidate')
    if args.operation == 'publish':
        d.fingerprint(args.expected_sha256)
        d.require(args.candidate_run.isdecimal() and int(args.candidate_run) > 0, 'Prepared run ID required')
        run = api(f'repos/{repo}/actions/runs/{args.candidate_run}')
        d.require(run['conclusion'] == 'success' and run['event'] == 'workflow_dispatch' and
                  run['head_sha'] == args.source and run['head_branch'] == 'main' and
                  run['actor']['login'] == OWNER and run.get('triggering_actor', run['actor'])['login'] == OWNER and
                  run['path'] == '.github/workflows/android-distribute.yml', 'Untrusted prepared run')
    d.write(args.config_output, settings)
    print('Owner, source, CI and protected environment: PASS; no keys accessed')


def unsigned(args):
    settings = config(d.load(CONFIG), os.environ['GITHUB_REPOSITORY'])
    d.require(d.git('rev-parse', 'HEAD') == args.source, 'Wrong build checkout')
    d.clean()
    variant = 'release' if args.channel == 'release' else 'debug'
    engine = Path(__file__).resolve().parent
    d.run('./gradlew', f":{settings['module']}:assemble{variant.title()}", '-I', engine / 'unsigned-distribution.gradle',
          '--no-daemon', '--no-configuration-cache', '--no-build-cache')
    d.clean()
    output = Path(args.directory)
    output.mkdir(parents=True, exist_ok=False)
    source = Path(f"{settings['module']}/build/outputs/apk/{variant}/{settings['module']}-{variant}-unsigned.apk")
    d.require(source.is_file(), 'Expected one standard unsigned Android APK')
    shutil.copyfile(source, output / 'unsigned.apk')
    print('Unsigned build prepared without signing secrets')


def inspect(apk, channel, certificate, settings):
    if settings['robstorePolicy']:
        d.require(settings['repository'] == d.REPO and settings['packages'] == d.PACKAGES,
                  'RobStore verification policy cannot be repurposed')
        return d.inspect_apk(apk, channel, certificate)
    tool = d.tools()
    signature = d.run(tool / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
    certs = d.signer_certificates(signature)
    d.require(len(certs) == 1 and certs[0].lower() == d.fingerprint(certificate), 'Unexpected APK signer')
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
    # Trust only the registry shipped in the reviewed, SHA-pinned CENTRAL tooling.
    registry = d.load(Path(__file__).with_name('approved-legacy-publications.json'))
    d.require(registry['schemaVersion'] == 1, 'Unsupported legacy registry')
    entries = registry['publications']
    d.require(len({(b['repository'], b['tag']) for b in entries}) == len(entries), 'Duplicate legacy approvals')
    return [b for b in entries if b['repository'] == repo]


def historical_certificate(repo, channel, certificate):
    if channel == 'release':
        for baseline in legacy_baselines(repo):
            d.require(d.fingerprint(certificate) == baseline['certificateSha256'],
                      'Release identity must match the approved historical APK; never generate a replacement')


def verified_legacy(repo, release, baseline):
    """Read-only verification. No fallback on API errors, drafts or altered assets."""
    b = baseline
    d.require(repo == b['repository'] and release['tag_name'] == b['tag'] and
              release['id'] == b['releaseId'] and release['draft'] is False and
              release['prerelease'] is False and bool(release['published_at']) and
              release['url'] == f"https://api.github.com/repos/{repo}/releases/{b['releaseId']}",
              'Legacy approval does not match a published stable Release')
    reference = api(f"repos/{repo}/git/ref/tags/{quote(b['tag'], safe='')}")
    d.require(reference['object'] == dict(type='commit', sha=b['tagCommit'],
              url=f"https://api.github.com/repos/{repo}/git/commits/{b['tagCommit']}"),
              'Approved historical tag moved or changed type')
    # Inspect the immutable tree; absence must be established by a successful API response.
    listing = api(f"repos/{repo}/contents?ref={b['tagCommit']}")
    d.require(isinstance(listing, list) and len(listing) < 1000, 'Invalid historical root listing')
    if any(item['name'] == d.CONTRACT for item in listing):
        return json.loads(raw_file(repo, b['tagCommit'], d.CONTRACT))
    assets = [a for a in release['assets'] if a['name'] == b['apkAssetName']]
    d.require(len(assets) == 1 and assets[0]['id'] == b['assetId'] and assets[0]['state'] == 'uploaded' and
              assets[0].get('digest') == 'sha256:' + b['sha256'] and 0 < assets[0]['size'] <= 512 * 1024 * 1024,
              'Approved legacy asset/digest mismatch (GitHub SHA-256 is mandatory)')
    catalog = json.loads(raw_file(repo, b['tagCommit'], 'robstore.json'))
    d.require(catalog['packageName'] == b['packageName'] and catalog['apkAssetName'] == b['apkAssetName'] and
              all(catalog.get(k, b[k]) == b[k] for k in ('versionCode', 'versionName')),
              'Legacy catalog identity/version mismatch')
    with tempfile.TemporaryDirectory(prefix='verified-legacy-') as folder:
        apk = Path(folder) / b['apkAssetName']
        with apk.open('wb') as stream:
            result = subprocess.run(['gh', 'api', '-H', 'Accept: application/octet-stream',
                f"repos/{repo}/releases/assets/{b['assetId']}"], stdout=stream, stderr=subprocess.PIPE)
        d.require(result.returncode == 0 and apk.stat().st_size == assets[0]['size'] and
                  d.digest(apk) == b['sha256'], 'Downloaded historical APK differs from approved SHA-256')
        settings = dict(repository=repo, robstorePolicy=False, packages=dict(release=b['packageName']),
                        minSdk=b['minSdk'], targetSdk=b['targetSdk'])
        actual = inspect(apk, 'release', b['certificateSha256'], settings)
    expected = dict(schemaVersion=1, channel='release', **{k: b[k] for k in
                    ('packageName', 'versionCode', 'versionName', 'apkAssetName', 'sha256')})
    d.validate_contract(actual, expected)
    print(f"Explicit legacy approval verified: {repo} {b['tag']} SHA-256 {b['sha256']}")
    return actual


def increasing(repo, contract):
    releases = pages(f'repos/{repo}/releases')
    baselines = legacy_baselines(repo) if contract['channel'] == 'release' else []
    for baseline in baselines:
        matches = [r for r in releases if r['tag_name'] == baseline['tag']]
        d.require(len(matches) == 1 and matches[0]['draft'] is False and matches[0]['prerelease'] is False,
                  'Approved legacy Release must remain present, published and stable')
        d.increasing(contract, dict(channel='release', packageName=baseline['packageName'], versionCode=baseline['versionCode']))
    for release in releases:
        if release['draft'] or release['prerelease'] != (contract['channel'] == 'dev'):
            continue
        approved = [b for b in baselines if b['tag'] == release['tag_name']]
        previous = (verified_legacy(repo, release, approved[0]) if approved else
                    json.loads(raw_file(repo, 'refs/tags/' + release['tag_name'], d.CONTRACT)))
        d.increasing(contract, previous)
        assets = [a for a in release['assets'] if a['name'] == previous['apkAssetName']]
        d.require(len(assets) == 1 and assets[0]['state'] == 'uploaded' and
                  assets[0].get('digest') in (None, 'sha256:' + previous['sha256']), 'Previous official asset inconsistent')


def expected_tag(contract):
    return 'v' + contract['versionName'] if contract['channel'] == 'release' else f"dev-{contract['versionName']}-{contract['versionCode']}"


def sign(args):
    settings = config(d.load(args.config_output), os.environ['GITHUB_REPOSITORY'])
    certificate = d.fingerprint(os.environ['CERTIFICATE_SHA256'])
    historical_certificate(settings['repository'], args.channel, certificate)
    other = d.fingerprint(os.environ['OTHER_CERTIFICATE_SHA256'])
    d.require(certificate != other, 'Release and DEV certificates must differ')
    d.require(os.environ.get('ACTIONS_STEP_DEBUG') != 'true' and os.environ.get('ACTIONS_RUNNER_DEBUG') != 'true', 'Debug runner forbidden for signing')
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
    d.require(args.tag == expected_tag(contract), 'Tag must match real APK version')
    increasing(settings['repository'], contract)
    d.write(output / d.CONTRACT, contract)
    d.write(output / d.RECEIPT, dict(schemaVersion=1, sourceCommit=args.source, channel=args.channel,
                                    certificateSha256=certificate, apkSha256=contract['sha256'],
                                    repository=settings['repository'], preparationRun=os.environ['GITHUB_RUN_ID'], tag=args.tag))
    (output / 'SHA256SUMS').write_text(f"{contract['sha256']}  {apk.name}\n")
    print(json.dumps(dict(result='CANDIDATE ONLY', source=args.source, tag=args.tag, contract=contract), indent=2))
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with Path(summary).open('a') as stream:
            stream.write(f"## Candidato firmado; publicación pendiente\n\nCommit: `{args.source}`\n\nTag: `{args.tag}`\n\nSHA-256 del APK: `{contract['sha256']}`\n\nRun de preparación: `{os.environ['GITHUB_RUN_ID']}`\n")


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
    d.require(contract['sha256'] == d.fingerprint(args.expected_sha256), 'Owner approved another APK hash')
    d.require(receipt == dict(schemaVersion=1, sourceCommit=args.source, channel=args.channel,
                             certificateSha256=d.fingerprint(os.environ['CERTIFICATE_SHA256']), apkSha256=contract['sha256'],
                             repository=settings['repository'], preparationRun=args.candidate_run, tag=args.tag), 'Candidate provenance mismatch')
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
    # Recheck authorization and main/CI immediately before writing; no build or signing here.
    guard(args)
    settings = config(d.load(args.config_output), os.environ['GITHUB_REPOSITORY'])
    contract, receipt, apk = candidate(args, settings)
    repo = settings['repository']
    increasing(repo, contract)
    d.require(args.tag not in [t['name'] for t in pages(f'repos/{repo}/tags')], 'Never move or reuse a public tag')
    d.require(args.tag not in [b['name'] for b in pages(f'repos/{repo}/branches')], 'Tag name collides with a branch')
    d.require(not any(r['tag_name'] == args.tag for r in pages(f'repos/{repo}/releases')), 'Release/tag already used, including draft')
    notes = raw_file(repo, args.source, settings['notesPath'])
    d.require(0 < len(notes.strip()) <= 32000 and '<!-- NOT_FOR_PUBLICATION -->' not in notes, 'Approved versioned release notes required')
    files = publication_files(settings, args.source, contract, receipt)
    d.require(set(files).issubset(CLOSURE_FILES), 'Only publication closure files may change')
    if not args.execute:
        print('DRY PLAN: validated candidate; no commit, tag, Release or upload created')
        return
    d.require(os.environ.get('ANDROID_PUBLICATION_EXECUTE') == 'owner-approved', 'Publication execution not enabled')
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
    api(f'repos/{repo}/git/refs', 'POST', dict(ref='refs/tags/' + args.tag, sha=tag['sha']))
    release = api(f'repos/{repo}/releases', 'POST', dict(tag_name=args.tag, target_commitish=commit['sha'], name=args.tag,
        body=notes + f"\n\nSource: `{args.source}`\nPublication commit: `{commit['sha']}`\nAPK SHA-256: `{contract['sha256']}`\n",
        draft=True, prerelease=args.channel == 'dev'))
    for file in (apk, Path(args.directory) / 'SHA256SUMS'):
        result = subprocess.run(['gh', 'release', 'upload', args.tag, str(file), '--repo', repo], capture_output=True)
        d.require(result.returncode == 0, 'Upload failed; draft/tag retained for owner inspection; no automatic retry')
    uploaded = api(f'repos/{repo}/releases/{release["id"]}')
    assets = [a for a in uploaded['assets'] if a['name'] == apk.name]
    d.require(len(assets) == 1 and assets[0]['state'] == 'uploaded' and assets[0]['size'] == apk.stat().st_size and
              assets[0].get('digest') == 'sha256:' + contract['sha256'], 'Uploaded APK digest mismatch; draft retained')
    tagged = json.loads(raw_file(repo, 'refs/tags/' + args.tag, d.CONTRACT))
    d.validate_contract(tagged, contract)
    d.require(api(f'repos/{repo}/branches/main')['commit']['sha'] == args.source, 'Main changed; leave draft for owner inspection')
    api(f'repos/{repo}/releases/{release["id"]}', 'PATCH', dict(draft=False))
    print('Authorized publication completed: ' + release['html_url'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('guard', 'unsigned', 'sign', 'publish'))
    for name, default in [('source', ''), ('operation', 'prepare'), ('channel', 'release'), ('tag', ''),
                          ('confirmation', ''), ('candidate-run', ''), ('expected-sha256', ''),
                          ('config-output', ''), ('directory', ''), ('apk', '')]:
        parser.add_argument('--' + name, default=default)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    if args.command == 'publish':
        args.operation = 'publish'
    globals()[args.command](args)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never echo API bodies, subprocess output, secret values or malformed keystore bytes.
        print('Android distribution blocked: ' + (str(error) if type(error) is ValueError else type(error).__name__), file=sys.stderr)
        sys.exit(1)

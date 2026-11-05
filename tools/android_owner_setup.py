#!/usr/bin/env python3
"""OWNER ONLY on Android/Termux: one-time keys, encrypted Drive backup and GitHub environments.
Never run in Codex Cloud, CI or an AI-controlled terminal. This file contains no owner secrets.
"""
import argparse
import base64
import getpass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import uuid


def require(ok, message):
    if not ok:
        raise ValueError(message)


def command(args, data=None, environment=None):
    result = subprocess.run(list(map(str, args)), input=data, env=environment, capture_output=True)
    require(result.returncode == 0, 'Owner operation failed; private output suppressed')
    return result.stdout


def gh_api(path, method='GET', value=None):
    args = ['gh', 'api', '--method', method, path]
    if value is not None:
        args += ['--input', '-']
    return json.loads(command(args, None if value is None else json.dumps(value).encode()) or b'null')


def encrypted_backup(directory, repo):
    backup = directory / (repo.split('/')[1] + '-signing-backup-' + uuid.uuid4().hex[:8] + '.tar.gpg')
    # GPG asks personally via /dev/tty; the passphrase never enters Python, argv or GitHub.
    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode='w') as archive:
        for name in ('release.p12', 'dev.p12', 'public-identities.json'):
            archive.add(directory / name, arcname=name)
    result = subprocess.run(['gpg', '--symmetric', '--cipher-algo', 'AES256', '--s2k-mode', '3',
                             '--output', str(backup)], input=payload.getvalue())
    require(result.returncode == 0, 'Backup encryption failed; do not provision secrets')
    print('Sube personalmente este archivo cifrado a tu carpeta privada de Google Drive: ' + str(backup))
    print('Descárgalo de Drive con otro nombre. No compartas enlaces, claves ni contraseñas con Codex.')
    restored = Path(input('Ruta de la copia cifrada descargada desde Drive: ').strip()).expanduser().resolve()
    require(restored != backup.resolve() and restored.is_file(), 'A separately downloaded backup copy is required')
    require(hashlib.sha256(restored.read_bytes()).digest() == hashlib.sha256(backup.read_bytes()).digest(), 'Drive backup differs')
    # Recovery test uses the downloaded ciphertext, not the original archive.
    recovered = subprocess.run(['gpg', '--decrypt', str(restored)], capture_output=True)
    require(recovered.returncode == 0 and recovered.stdout == payload.getvalue(), 'Backup recovery failed')
    print('Copia de Drive y recuperación cifrada: verificadas localmente.')


def restore_backup(directory, ciphertext, repo):
    require(not directory.exists(), 'Restore only into a new private directory')
    require(ciphertext.is_file() and ciphertext.stat().st_size <= 2 * 1024 * 1024, 'Invalid encrypted backup size')
    decrypted = subprocess.run(['gpg', '--decrypt', str(ciphertext)], capture_output=True)
    require(decrypted.returncode == 0 and len(decrypted.stdout) <= 2 * 1024 * 1024, 'Could not decrypt backup')
    with tarfile.open(fileobj=io.BytesIO(decrypted.stdout), mode='r:') as archive:
        members = archive.getmembers()
        expected = {'release.p12', 'dev.p12', 'public-identities.json'}
        require(len(members) == 3 and {m.name for m in members} == expected and
                all(m.isfile() and m.size <= 1024 * 1024 for m in members), 'Unsafe backup archive')
        bodies = {m.name: archive.extractfile(m).read() for m in members}
    require(json.loads(bodies['public-identities.json'])['repository'] == repo, 'Backup belongs to another repository')
    directory.mkdir(mode=0o700, parents=True)
    for name, body in bodies.items():
        target = directory / name
        target.write_bytes(body)
        target.chmod(0o600)


def verify_historical_release(repo, tag):
    from mobile_distribution import legacy_baselines, verified_legacy, api
    approved = [b for b in legacy_baselines(repo) if b['tag'] == tag]
    require(len(approved) == 1, 'An explicit reviewed historical publication is required')
    b = approved[0]
    verified_legacy(repo, api(f'repos/{repo}/releases/tags/{tag}'), b)
    return b['certificateSha256']


def import_release(source, destination, password, expected):
    # Normalize an existing JKS/PKCS12 key into the RS-05 backup layout, retaining its identity.
    source = Path(source).expanduser().resolve()
    require(source.is_file() and 0 < source.stat().st_size <= 1024 * 1024 and source != destination and not destination.exists(),
            'Invalid historical keystore; original will never be overwritten')
    alias = input('Alias de la clave Release histórica: ').strip()
    require(alias and not alias.startswith('-'), 'Historical alias required')
    source_password = getpass.getpass('Contraseña del keystore histórico: ')
    key_password = getpass.getpass('Contraseña de la clave histórica (vacío = la del keystore): ') or source_password
    environment = dict(os.environ, ANDROID_OWNER_SOURCE_PASSWORD=source_password,
                       ANDROID_OWNER_SOURCE_KEY_PASSWORD=key_password, ANDROID_OWNER_KEY_PASSWORD=password)
    certificate = command(['keytool', '-exportcert', '-keystore', source, '-alias', alias,
                           '-storepass:env', 'ANDROID_OWNER_SOURCE_PASSWORD'], environment=environment)
    require(hashlib.sha256(certificate).hexdigest() == expected,
            'Historical keystore does not match official APK; STOP, do not generate another Release key')
    command(['keytool', '-importkeystore', '-noprompt', '-srckeystore', source, '-srcalias', alias,
             '-srcstorepass:env', 'ANDROID_OWNER_SOURCE_PASSWORD', '-srckeypass:env', 'ANDROID_OWNER_SOURCE_KEY_PASSWORD',
             '-destkeystore', destination, '-deststoretype', 'PKCS12', '-destalias', 'android-release',
             '-deststorepass:env', 'ANDROID_OWNER_KEY_PASSWORD', '-destkeypass:env', 'ANDROID_OWNER_KEY_PASSWORD'],
             environment=environment)


def check_existing_identities(repo, public):
    # Check every public pin, including the publish environment, before the first secret write.
    for channel in ('release', 'dev'):
        existing = gh_api(f'repos/{repo}/environments/android-{channel}/variables')['variables']
        pins = {v['name']: v['value'] for v in existing}
        for key, expected in dict(CERTIFICATE_SHA256=public['certificates'][channel],
            OTHER_CERTIFICATE_SHA256=public['certificates']['dev' if channel == 'release' else 'release']).items():
            require(pins.get(key, expected) == expected, 'Existing identity differs; do not replace secrets')
        secrets = gh_api(f'repos/{repo}/environments/android-{channel}/secrets')['secrets']
        require(not secrets or 'CERTIFICATE_SHA256' in pins, 'Existing secrets without a public identity pin; owner must investigate')
    pins = {v['name']: v['value'] for v in gh_api(f'repos/{repo}/environments/android-publish/variables')['variables']}
    for channel in ('release', 'dev'):
        require(pins.get(channel.upper() + '_CERTIFICATE_SHA256', public['certificates'][channel]) == public['certificates'][channel],
                'Existing publication identity differs; never replace it')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--private-directory', required=True)
    parser.add_argument('--tooling-sha', required=True)
    recovery = parser.add_mutually_exclusive_group()
    recovery.add_argument('--resume', action='store_true', help='Recover interrupted setup using the same identities; never regenerate keys')
    recovery.add_argument('--restore-backup', help='Restore encrypted Drive backup into a new private directory; never regenerate keys')
    recovery.add_argument('--import-release-keystore', help='OWNER ONLY: import an existing historical private key; never generate Release')
    parser.add_argument('--legacy-tag', help='Exact historical tag in the reviewed central approval registry')
    parser.add_argument('--catalog', action='store_true', help='Add robstore-app without removing existing topics; repository must already be public')
    args = parser.parse_args()
    require('com.termux' in os.environ.get('PREFIX', '') and not os.environ.get('CI') and not os.environ.get('CODEX_THREAD_ID'),
            'Run personally in Android Termux, never Cloud/CI/Codex')
    os.umask(0o077)
    require(re.fullmatch(r'robdor80/[A-Za-z0-9_.-]+', args.repository), 'Personal owner repository required')
    require(re.fullmatch('[0-9a-f]{40}', args.tooling_sha), 'Audited central tooling SHA required')
    require(gh_api('user')['login'] == 'robdor80', 'Authenticate personally as owner first')
    repository = gh_api(f'repos/{args.repository}')
    require(not repository['fork'] and repository['default_branch'] == 'main', 'Use an original repository with main')
    require(not args.catalog or not repository['private'], 'RobStore catalog requires an explicitly public repository')
    require(gh_api(f'repos/robdor80/RobStore/compare/{args.tooling_sha}...main')['status'] in ('ahead', 'identical'), 'Tooling not yet approved in main')
    from mobile_distribution import legacy_baselines
    historical = legacy_baselines(args.repository)
    require(not args.import_release_keystore or args.legacy_tag, 'Import requires an approved historical tag')
    require(not historical or args.resume or args.restore_backup or args.import_release_keystore,
            'Historical Release exists: import or recover its key; generation is forbidden')
    historical_pin = None
    if historical or args.import_release_keystore:
        tag = args.legacy_tag or historical[0]['tag']
        historical_pin = verify_historical_release(args.repository, tag)
    directory = Path(args.private_directory).expanduser().resolve()
    require(directory.is_dir() if args.resume else not directory.exists(), 'Use a new private directory, or --resume with the original identities')
    checkout = Path(command(['git', 'rev-parse', '--show-toplevel']).decode().strip()).resolve()
    require(command(['git', 'rev-parse', 'HEAD']).decode().strip() == args.tooling_sha and
            not command(['git', 'status', '--porcelain', '--untracked-files=all']).strip(),
            'Run the clean central checkout at exactly the approved tooling SHA')
    require(not directory.is_relative_to(checkout) and directory.is_relative_to(Path.home()), 'Use private Termux home outside Git/shared storage')
    if args.import_release_keystore:
        imported_source = Path(args.import_release_keystore).expanduser().resolve()
        require(imported_source.is_relative_to(Path.home()) and not imported_source.is_relative_to(checkout),
                'Recover historical keystore into private Termux home outside Git/shared storage')
    if args.restore_backup:
        restore_backup(directory, Path(args.restore_backup).expanduser().resolve(), args.repository)
        args.resume = True
    confirmation = ('RECUPERAR CONFIGURACION' if args.resume else
                    'IMPORTAR IDENTIDAD RELEASE' if args.import_release_keystore else 'CREAR IDENTIDADES')
    require(input('Escribe ' + confirmation + ' para esta aplicación: ') == confirmation, 'Owner cancelled')
    if not args.resume:
        existing = {x['name'] for x in gh_api(f'repos/{args.repository}/environments')['environments']}
        require(not existing.intersection({'android-release', 'android-dev', 'android-publish'}), 'Existing distribution environments: recover instead of replacing identities')
        directory.mkdir(mode=0o700, parents=True)
    passwords = {}
    public = json.loads((directory / 'public-identities.json').read_text()) if args.resume else {'repository': args.repository, 'certificates': {}}
    require(public['repository'] == args.repository, 'Backup belongs to another application')
    for channel in ('release', 'dev'):
        password = getpass.getpass(f'Contraseña única del gestor para {channel}: ')
        require(len(password) >= 20 and password == getpass.getpass('Repite personalmente la contraseña: '), 'Use a matching unique password of at least 20 characters')
        require(password not in passwords.values(), 'Choose a different DEV password')
        passwords[channel] = password
        environment = dict(os.environ, ANDROID_OWNER_KEY_PASSWORD=password)
        store = directory / (channel + '.p12')
        if not args.resume and channel == 'release' and args.import_release_keystore:
            import_release(args.import_release_keystore, store, password, historical_pin)
        elif not args.resume:
            command(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', store,
                     '-alias', 'android-' + channel, '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
                     '-validity', '10000', '-dname', 'CN=' + args.repository.replace('/', '-') + '-' + channel,
                     '-storepass:env', 'ANDROID_OWNER_KEY_PASSWORD'], environment=environment)
        store.chmod(0o600)
        cert = command(['keytool', '-exportcert', '-keystore', store, '-alias', 'android-' + channel,
                        '-storepass:env', 'ANDROID_OWNER_KEY_PASSWORD'], environment=environment)
        pin = hashlib.sha256(cert).hexdigest()
        require(not args.resume or public['certificates'][channel] == pin, 'Recovered certificate differs; never rotate silently')
        require(channel != 'release' or historical_pin is None or pin == historical_pin,
                'Recovered Release certificate differs from the official historical APK')
        public['certificates'][channel] = pin
    require(public['certificates']['release'] != public['certificates']['dev'], 'Release and DEV identities must differ')
    (directory / 'public-identities.json').write_text(json.dumps(public, indent=2) + '\n')
    encrypted_backup(directory, args.repository)
    require(input('Escribe CONFIGURAR GITHUB para guardar secretos en entornos protegidos: ') == 'CONFIGURAR GITHUB', 'Owner cancelled; keys remain only on Android')
    owner = gh_api('users/robdor80')['id']
    for name in ('android-release', 'android-dev', 'android-publish'):
        listing = gh_api(f'repos/{args.repository}/environments')['environments']
        if name not in [x['name'] for x in listing]:
            gh_api(f'repos/{args.repository}/environments/{name}', 'PUT', dict(prevent_self_review=False,
                   reviewers=[dict(type='User', id=owner)], deployment_branch_policy=dict(protected_branches=False, custom_branch_policies=True)))
            gh_api(f'repos/{args.repository}/environments/{name}/deployment-branch-policies', 'POST', dict(name='main', type='branch'))
        else:
            from mobile_distribution import protected_environment
            protected_environment(args.repository, name)
    check_existing_identities(args.repository, public)
    command(['gh', 'variable', 'set', 'ANDROID_DISTRIBUTION_ENABLED', '--repo', args.repository, '--body', 'false'])
    for channel in ('release', 'dev'):
        name = 'android-' + channel
        values = dict(SIGNING_KEYSTORE_B64=base64.b64encode((directory / (channel + '.p12')).read_bytes()).decode(),
                      SIGNING_STORE_PASSWORD=passwords[channel], SIGNING_KEY_PASSWORD=passwords[channel], SIGNING_ALIAS='android-' + channel)
        for key, value in values.items():
            command(['gh', 'secret', 'set', key, '--repo', args.repository, '--env', name], value.encode())
        for key, value in dict(CERTIFICATE_SHA256=public['certificates'][channel],
                               OTHER_CERTIFICATE_SHA256=public['certificates']['dev' if channel == 'release' else 'release']).items():
            command(['gh', 'variable', 'set', key, '--repo', args.repository, '--env', name, '--body', value])
        command(['gh', 'variable', 'set', channel.upper() + '_CERTIFICATE_SHA256', '--repo', args.repository,
                 '--env', 'android-publish', '--body', public['certificates'][channel]])
    for key, value in dict(ANDROID_DISTRIBUTION_TOOLING_SHA=args.tooling_sha, ANDROID_DISTRIBUTION_ENABLED='true').items():
        command(['gh', 'variable', 'set', key, '--repo', args.repository, '--body', value])
    if args.catalog:
        topics = gh_api(f'repos/{args.repository}/topics')['names']
        gh_api(f'repos/{args.repository}/topics', 'PUT', dict(names=sorted(set(topics + ['robstore-app']))))
    print('Configuración completada; no se ha ejecutado ningún workflow ni publicado nada.')
    print('Conserva claves y contraseñas; respalda también la recuperación del gestor fuera del móvil.')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Owner setup stopped: ' + (str(error) if type(error) is ValueError else type(error).__name__))
        raise SystemExit(1)

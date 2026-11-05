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
import tempfile
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


def historical_identity(repo):
    from mobile_distribution import legacy_baselines, download_historical_apk
    entries = legacy_baselines(repo)
    require(len(entries) <= 1, 'Multiple historical identities require owner review')
    if not entries:
        return None
    with tempfile.TemporaryDirectory(prefix='owner-public-apk-') as folder:
        apk = download_historical_apk(entries[0], folder)
        # Termux apksigner is Java based; do not require an x86 Linux Android SDK on Android.
        signature = command(['apksigner', 'verify', '--verbose', '--print-certs', apk]).decode()
        certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]{64})$', signature, re.M)
        require(len(certs) == 1 and certs[0].lower() == entries[0]['certificateSha256'] and
                'Verified using v2 scheme (APK Signature Scheme v2): true' in signature, 'Official historical APK certificate mismatch')
    return entries[0]['certificateSha256']


def import_release(store, password, checkout):
    """Owner prompts only; normalize an existing private entry without changing its key."""
    source = Path(input('Ruta privada del keystore Release histórico recuperado: ').strip()).expanduser().resolve()
    require(source.is_file() and source.is_relative_to(Path.home()) and not source.is_relative_to(checkout) and
            not any((parent / '.git').exists() for parent in source.parents) and
            source.stat().st_size <= 1024 * 1024, 'Historical keystore must be in private Termux home outside Git')
    alias = input('Alias histórico exacto: ').strip()
    require(alias and not any(ord(c) < 32 for c in alias), 'A historical alias is required')
    source_password = getpass.getpass('Contraseña histórica del almacén (sin eco): ')
    key_password = getpass.getpass('Contraseña histórica de la clave (sin eco; puede ser la misma): ')
    require(source_password and key_password, 'Historical passwords required; never generate a replacement')
    environment = dict(os.environ, OWNER_SOURCE_STORE_PASSWORD=source_password,
                       OWNER_SOURCE_KEY_PASSWORD=key_password, ANDROID_OWNER_KEY_PASSWORD=password)
    command(['keytool', '-importkeystore', '-noprompt', '-srckeystore', source, '-srcalias', alias,
             '-srcstorepass:env', 'OWNER_SOURCE_STORE_PASSWORD', '-srckeypass:env', 'OWNER_SOURCE_KEY_PASSWORD',
             '-destkeystore', store, '-deststoretype', 'PKCS12', '-destalias', 'android-release',
             '-deststorepass:env', 'ANDROID_OWNER_KEY_PASSWORD', '-destkeypass:env', 'ANDROID_OWNER_KEY_PASSWORD'], environment=environment)


def certificate_pin(store, channel, password):
    environment = dict(os.environ, ANDROID_OWNER_KEY_PASSWORD=password)
    entry = command(['keytool', '-J-Duser.language=en', '-list', '-v', '-keystore', store,
                     '-alias', 'android-' + channel, '-storepass:env', 'ANDROID_OWNER_KEY_PASSWORD'], environment=environment)
    require(b'Entry type: PrivateKeyEntry' in entry, 'A private signing entry is required')
    cert = command(['keytool', '-exportcert', '-keystore', store, '-alias', 'android-' + channel,
                    '-storepass:env', 'ANDROID_OWNER_KEY_PASSWORD'], environment=environment)
    return hashlib.sha256(cert).hexdigest()


def verify_existing_pins(repo, public):
    """Check all existing identities before any secret write; missing pins fail closed."""
    for channel in ('release', 'dev'):
        name = 'android-' + channel
        variables = gh_api(f'repos/{repo}/environments/{name}/variables')['variables']
        pins = {v['name']: v['value'] for v in variables}
        secrets = gh_api(f'repos/{repo}/environments/{name}/secrets')['secrets']
        expected = public['certificates'][channel]
        require((not secrets or 'CERTIFICATE_SHA256' in pins) and
                pins.get('CERTIFICATE_SHA256', expected) == expected, 'Existing identity differs or has no public pin; do not replace secrets')
        other = public['certificates']['dev' if channel == 'release' else 'release']
        require(pins.get('OTHER_CERTIFICATE_SHA256', other) == other, 'Existing other-channel identity differs')
    pins = {v['name']: v['value'] for v in gh_api(f'repos/{repo}/environments/android-publish/variables')['variables']}
    for channel in ('release', 'dev'):
        expected = public['certificates'][channel]
        require(pins.get(channel.upper() + '_CERTIFICATE_SHA256', expected) == expected, 'Existing publication identity differs')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--private-directory', required=True)
    parser.add_argument('--tooling-sha', required=True)
    recovery = parser.add_mutually_exclusive_group()
    recovery.add_argument('--import-release', action='store_true', help='Import an existing historical Release identity; never generate a Release key')
    recovery.add_argument('--resume', action='store_true', help='Recover interrupted setup using the same identities; never regenerate keys')
    recovery.add_argument('--restore-backup', help='Restore encrypted Drive backup into a new private directory; never regenerate keys')
    parser.add_argument('--catalog', action='store_true', help='Add robstore-app without removing existing topics; repository must already be public')
    args = parser.parse_args()
    require('com.termux' in os.environ.get('PREFIX', '') and not os.environ.get('CI') and not os.environ.get('CODEX_THREAD_ID'),
            'Run personally in Android Termux, never Cloud/CI/Codex')
    require(re.fullmatch(r'robdor80/[A-Za-z0-9_.-]+', args.repository), 'Personal owner repository required')
    require(re.fullmatch('[0-9a-f]{40}', args.tooling_sha), 'Audited central tooling SHA required')
    require(gh_api('user')['login'] == 'robdor80', 'Authenticate personally as owner first')
    repository = gh_api(f'repos/{args.repository}')
    require(not repository['fork'] and repository['default_branch'] == 'main', 'Use an original repository with main')
    require(not args.catalog or not repository['private'], 'RobStore catalog requires an explicitly public repository')
    require(gh_api(f'repos/robdor80/RobStore/compare/{args.tooling_sha}...main')['status'] in ('ahead', 'identical'), 'Tooling not yet approved in main')
    directory = Path(args.private_directory).expanduser().resolve()
    require(directory.is_dir() if args.resume else not directory.exists(), 'Use a new private directory, or --resume with the original identities')
    checkout = Path(command(['git', 'rev-parse', '--show-toplevel']).decode().strip()).resolve()
    require(not directory.is_relative_to(checkout) and directory.is_relative_to(Path.home()) and
            not any((parent / '.git').exists() for parent in directory.parents), 'Use private Termux home outside Git/shared storage')
    require(command(['git', 'rev-parse', 'HEAD']).decode().strip() == args.tooling_sha, 'Checkout must equal the audited tooling SHA')
    require(not command(['git', 'status', '--porcelain', '--untracked-files=all']).strip(), 'Use a clean audited tooling checkout')
    historical_pin = historical_identity(args.repository)
    require(not args.import_release or historical_pin is not None, 'Import requires a centrally approved historical APK baseline')
    require(not historical_pin or args.import_release or args.resume or args.restore_backup,
            'Historical Release exists: use --import-release or recover the same identity; generation forbidden')
    if args.restore_backup:
        restore_backup(directory, Path(args.restore_backup).expanduser().resolve(), args.repository)
        args.resume = True
    confirmation = 'RECUPERAR CONFIGURACION' if args.resume else ('IMPORTAR IDENTIDAD HISTORICA' if args.import_release else 'CREAR IDENTIDADES')
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
        if not args.resume and args.import_release and channel == 'release':
            import_release(store, password, checkout)
        elif not args.resume:
            command(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', store,
                     '-alias', 'android-' + channel, '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA',
                     '-validity', '10000', '-dname', 'CN=' + args.repository.replace('/', '-') + '-' + channel,
                     '-storepass:env', 'ANDROID_OWNER_KEY_PASSWORD'], environment=environment)
        store.chmod(0o600)
        pin = certificate_pin(store, channel, password)
        require(channel != 'release' or historical_pin is None or pin == historical_pin,
                'Imported/recovered certificate differs from official historical APK; ABORT, never replace the Release identity')
        require(not args.resume or public['certificates'][channel] == pin, 'Recovered certificate differs; never rotate silently')
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
        from mobile_distribution import protected_environment
        protected_environment(args.repository, name)
    verify_existing_pins(args.repository, public)
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

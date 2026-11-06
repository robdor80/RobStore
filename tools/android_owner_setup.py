#!/usr/bin/env python3
"""Alta única de claves Android y entornos. Las actualizaciones sólo usan RobPublisher.
Importar/crear claves: operación inicial del propietario; nunca CI ni un terminal de Codex.
El respaldo cifrado es opcional e independiente del alta.
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
import secrets


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
    """Optional independent local backup; upload it wherever the owner prefers."""
    backup = directory / (repo.split('/')[1] + '-signing-backup-' + uuid.uuid4().hex[:8] + '.tar.gpg')
    payload = io.BytesIO()
    with tarfile.open(fileobj=payload, mode='w') as archive:
        for name in ('release.p12', 'dev.p12', 'public-identities.json', 'signing.json'):
            if (directory / name).is_file():
                archive.add(directory / name, arcname=name)
    result = subprocess.run(['gpg', '--symmetric', '--cipher-algo', 'AES256', '--output', str(backup)], input=payload.getvalue())
    require(result.returncode == 0, 'No se pudo crear el respaldo cifrado; el alta y la distribución no cambian')
    print('Respaldo cifrado creado: ' + str(backup))
    return backup


def restore_backup(directory, ciphertext, repo):
    require(not directory.exists(), 'Restore only into a new private directory')
    require(ciphertext.is_file() and ciphertext.stat().st_size <= 2 * 1024 * 1024, 'Invalid encrypted backup size')
    decrypted = subprocess.run(['gpg', '--decrypt', str(ciphertext)], capture_output=True)
    require(decrypted.returncode == 0 and len(decrypted.stdout) <= 2 * 1024 * 1024, 'Could not decrypt backup')
    with tarfile.open(fileobj=io.BytesIO(decrypted.stdout), mode='r:') as archive:
        members = archive.getmembers()
        expected = {'release.p12', 'dev.p12', 'public-identities.json'}
        names = {m.name for m in members}
        require(expected <= names <= expected | {'signing.json'} and len(members) == len(names) and
                all(m.isfile() and m.size <= 1024 * 1024 for m in members), 'Unsafe backup archive')
        bodies = {m.name: archive.extractfile(m).read() for m in members}
    require(json.loads(bodies['public-identities.json'])['repository'] == repo, 'Backup belongs to another repository')
    directory.mkdir(mode=0o700, parents=True)
    for name, body in bodies.items():
        target = directory / name
        target.write_bytes(body)
        target.chmod(0o600)


def check_existing_identities(repo, public):
    # Check every public pin, including the publish environment, before the first secret write.
    for channel in ('release', 'dev'):
        existing = gh_api(f'repos/{repo}/environments/android-{channel}/variables')['variables']
        pins = {v['name']: v['value'] for v in existing}
        for key, expected in dict(CERTIFICATE_SHA256=public['certificates'][channel]).items():
            require(pins.get(key, expected) == expected, 'Existing identity differs; do not replace secrets')
        secrets = gh_api(f'repos/{repo}/environments/android-{channel}/secrets')['secrets']
        require(not secrets or 'CERTIFICATE_SHA256' in pins, 'Existing secrets without a public identity pin; owner must investigate')
    pins = {v['name']: v['value'] for v in gh_api(f'repos/{repo}/environments/android-publish/variables')['variables']}
    for channel in ('release', 'dev'):
        require(pins.get(channel.upper() + '_CERTIFICATE_SHA256', public['certificates'][channel]) == public['certificates'][channel],
                'Existing publication identity differs; never replace it')


def ensure_environments(repo):
    existing = {x['name'] for x in gh_api(f'repos/{repo}/environments')['environments']}
    for name in ('android-release', 'android-dev', 'android-publish'):
        # Updating environment protection preserves secrets, variables and branch rules.
        gh_api(f'repos/{repo}/environments/{name}', 'PUT', dict(prevent_self_review=False, wait_timer=0,
               reviewers=[], deployment_branch_policy=dict(protected_branches=False, custom_branch_policies=True)))
        if name not in existing:
            gh_api(f'repos/{repo}/environments/{name}/deployment-branch-policies', 'POST', dict(name='main', type='branch'))
        from mobile_distribution import protected_environment
        protected_environment(repo, name)


def provision_signing_secrets(repo, name, values):
    existing = {entry['name'] for entry in gh_api(f'repos/{repo}/environments/{name}/secrets')['secrets']}
    # Initial setup/recovery only: never overwrite an already configured signing secret.
    for key, value in values.items():
        if key not in existing:
            command(['gh', 'secret', 'set', key, '--repo', repo, '--env', name], value.encode())


def configured(repo):
    """Only secret names and public pins are read. Private values never leave GitHub."""
    names = {item['name'] for item in gh_api(f'repos/{repo}/environments')['environments']}
    required = {'SIGNING_KEYSTORE_B64', 'SIGNING_STORE_PASSWORD', 'SIGNING_ALIAS'}
    if not {'android-release', 'android-dev', 'android-publish'} <= names:
        return False
    for channel in ('release', 'dev'):
        root = f'repos/{repo}/environments/android-{channel}'
        stored = {item['name'] for item in gh_api(root + '/secrets')['secrets']}
        variables = {item['name']: item['value'] for item in gh_api(root + '/variables')['variables']}
        if not required <= stored or 'CERTIFICATE_SHA256' not in variables:
            return False
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--private-directory')
    parser.add_argument('--environments-only', action='store_true')
    parser.add_argument('--import-release-keystore')
    parser.add_argument('--import-dev-keystore')
    parser.add_argument('--backup', action='store_true', help='Optional encrypted local backup, independent of setup')
    parser.add_argument('--restore-backup')
    parser.add_argument('--catalog', action='store_true')
    args = parser.parse_args()
    require(re.fullmatch(r'robdor80/[A-Za-z0-9_.-]+', args.repository), 'Personal owner repository required')
    require(gh_api('user')['login'] == 'robdor80', 'Configura una vez el acceso de GitHub del propietario')
    if args.environments_only:
        ensure_environments(args.repository)
        print('Entornos sin aprobaciones; claves conservadas.')
        return
    directory = Path(args.private_directory or Path.home() / '.local/share/rs05' / args.repository.replace('/', '-')).expanduser().resolve()
    if args.backup or args.restore_backup:
        require(not os.environ.get('CI') and not os.environ.get('CODEX_THREAD_ID'), 'El respaldo y la recuperación de claves se hacen en el dispositivo del propietario')
        if args.backup:
            encrypted_backup(directory, args.repository)
        else:
            restore_backup(directory, Path(args.restore_backup).expanduser().resolve(), args.repository)
            print('Copia local recuperada sin modificar GitHub.')
        return
    # Existing complete setup is idempotent: no local keys, password prompts or secret writes.
    if configured(args.repository):
        ensure_environments(args.repository)
        print('La aplicación ya está configurada. Publica desde RobPublisher.')
        return
    # Private-key operations are initial owner setup only, never CI or an AI terminal.
    require(not os.environ.get('CI') and not os.environ.get('CODEX_THREAD_ID'),
            'El propietario debe importar o crear sus claves una sola vez en su dispositivo; no compartir secretos con Codex')
    os.umask(0o077)
    from mobile_distribution import legacy_baselines
    historical = legacy_baselines(args.repository)
    existing = {item['name'] for item in gh_api(f'repos/{args.repository}/environments')['environments']}
    metadata = directory / 'signing.json'
    public_file = directory / 'public-identities.json'
    public = json.loads(public_file.read_text()) if public_file.is_file() else dict(repository=args.repository, certificates={})
    require(public['repository'] == args.repository, 'Las claves pertenecen a otra aplicación')
    settings = json.loads(metadata.read_text()) if metadata.is_file() else {}
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    # A partial existing setup may be recovered only from the same local identities/imports.
    for channel in ('release', 'dev'):
        store = directory / (channel + '.p12')
        imported = getattr(args, 'import_' + channel + '_keystore')
        require(not ('android-' + channel in existing and not store.exists() and not imported),
                'Falta recuperar la clave existente de ' + channel + '; no se generará otra identidad')
        if channel == 'release' and historical:
            require(store.exists() or imported, 'Recupera o importa la Release original; generation is forbidden')
        if channel not in settings:
            settings[channel] = dict(password=(getpass.getpass('Contraseña de la clave local recuperada de ' + channel + ': ')
                                                     if store.exists() else secrets.token_urlsafe(32)), alias='android-' + channel)
        metadata.write_text(json.dumps(settings)); metadata.chmod(0o600)
        password = settings[channel]['password']
        environment = dict(os.environ, ANDROID_OWNER_KEY_PASSWORD=password)
        if not store.exists():
            if imported:
                # Import either channel without replacing the original file or its certificate.
                expected = historical[0]['certificateSha256'] if channel == 'release' and historical else None
                import_key(imported, store, password, settings[channel]['alias'], expected)
            else:
                command(['keytool', '-genkeypair', '-storetype', 'PKCS12', '-keystore', store,
                         '-alias', settings[channel]['alias'], '-keyalg', 'RSA', '-keysize', '3072',
                         '-validity', '10000', '-dname', 'CN=' + args.repository.replace('/', '-') + '-' + channel,
                         '-storepass:env', 'ANDROID_OWNER_KEY_PASSWORD'], environment=environment)
        store.chmod(0o600)
        cert = command(['keytool', '-exportcert', '-keystore', store, '-alias', settings[channel]['alias'],
                        '-storepass:env', 'ANDROID_OWNER_KEY_PASSWORD'], environment=environment)
        pin = hashlib.sha256(cert).hexdigest()
        require(public['certificates'].get(channel, pin) == pin, 'El certificado existente no coincide; no sustituir claves')
        require(channel != 'release' or not historical or pin == historical[0]['certificateSha256'],
                'La clave no corresponde a la Release histórica original')
        public['certificates'][channel] = pin
        metadata.write_text(json.dumps(settings)); metadata.chmod(0o600)
        public_file.write_text(json.dumps(public, indent=2) + '\n')
    ensure_environments(args.repository)
    check_existing_identities(args.repository, public)
    for channel in ('release', 'dev'):
        name = 'android-' + channel
        values = dict(SIGNING_KEYSTORE_B64=base64.b64encode((directory / (channel + '.p12')).read_bytes()).decode(),
                      SIGNING_STORE_PASSWORD=settings[channel]['password'], SIGNING_ALIAS=settings[channel]['alias'])
        provision_signing_secrets(args.repository, name, values)
        for key, value in dict(CERTIFICATE_SHA256=public['certificates'][channel]).items():
            command(['gh', 'variable', 'set', key, '--repo', args.repository, '--env', name, '--body', value])
        command(['gh', 'variable', 'set', channel.upper() + '_CERTIFICATE_SHA256', '--repo', args.repository,
                 '--env', 'android-publish', '--body', public['certificates'][channel]])
    if args.catalog:
        topics = gh_api(f'repos/{args.repository}/topics')['names']
        gh_api(f'repos/{args.repository}/topics', 'PUT', dict(names=sorted(set(topics + ['robstore-app']))))
    print('Alta completada. Publica desde RobPublisher. El respaldo cifrado es opcional con --backup.')


def import_key(source, destination, password, alias, expected=None):
    source = Path(source).expanduser().resolve()
    require(source.is_file() and source != destination and not destination.exists(), 'Keystore original no válido')
    old_password = getpass.getpass('Contraseña del keystore existente (solo esta importación): ')
    env = dict(os.environ, ANDROID_OWNER_SOURCE_PASSWORD=old_password, ANDROID_OWNER_KEY_PASSWORD=password)
    listing = command(['keytool', '-J-Duser.language=en', '-list', '-keystore', source,
                       '-storepass:env', 'ANDROID_OWNER_SOURCE_PASSWORD'], environment=env).decode()
    aliases = re.findall(r'^([^,]+),.*PrivateKeyEntry', listing, re.M)
    old_alias = aliases[0] if len(aliases) == 1 else input('Alias de la clave que se importa: ').strip()
    cert = command(['keytool', '-exportcert', '-keystore', source, '-alias', old_alias,
                    '-storepass:env', 'ANDROID_OWNER_SOURCE_PASSWORD'], environment=env)
    require(expected is None or hashlib.sha256(cert).hexdigest() == expected, 'El certificado no es el original; no importar')
    args = ['keytool', '-importkeystore', '-noprompt', '-srckeystore', source, '-srcalias', old_alias,
            '-srcstorepass:env', 'ANDROID_OWNER_SOURCE_PASSWORD', '-srckeypass:env', 'ANDROID_OWNER_SOURCE_PASSWORD',
            '-destkeystore', destination, '-deststoretype', 'PKCS12', '-destalias', alias,
            '-deststorepass:env', 'ANDROID_OWNER_KEY_PASSWORD', '-destkeypass:env', 'ANDROID_OWNER_KEY_PASSWORD']
    # Most Android keys use the store password. Ask separately only when that fails.
    try:
        command(args, environment=env)
    except ValueError:
        env['ANDROID_OWNER_SOURCE_KEY_PASSWORD'] = getpass.getpass('Contraseña de la clave (diferente a la del keystore): ')
        args[args.index('ANDROID_OWNER_SOURCE_PASSWORD', args.index('-srckeypass:env'))] = 'ANDROID_OWNER_SOURCE_KEY_PASSWORD'
        command(args, environment=env)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Alta detenida: ' + (str(error) if type(error) is ValueError else type(error).__name__))
        raise SystemExit(1)

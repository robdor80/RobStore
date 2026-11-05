#!/usr/bin/env python3
"""Verify Android output identities, SDK, alignment and signing; package only the real DEV APK."""
import argparse
import hashlib
import os
import re
from pathlib import Path
import shutil
import subprocess


def run(*args, success=True):
    result = subprocess.run(list(map(str, args)), text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if success and result.returncode:
        raise RuntimeError(result.stdout)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--unsigned-debug', action='store_true',
                        help='Validate unsigned Debug output without creating or using private keys')
    unsigned_debug = parser.parse_args().unsigned_debug
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    tools = sdk / 'build-tools/35.0.0'
    commit = run('git', 'rev-parse', 'HEAD').stdout.strip()
    output = Path('app/build/rs04-dev')
    output.mkdir(parents=True, exist_ok=True)
    for old in output.glob('RobStore-DEV-*.apk'):
        old.unlink()
    report = [f'# Verificación de APK — {commit}', '']
    for variant, package, label in [('debug', 'es.robertodorado.robstore.debug', 'RobStore DEV'),
                                     ('release', 'es.robertodorado.robstore', 'RobStore')]:
        apk = Path(f'app/build/outputs/apk/{variant}/app-{variant}' + ('-unsigned.apk' if variant == 'release' or unsigned_debug else '.apk'))
        badging = run(tools / 'aapt', 'dump', 'badging', apk).stdout
        assert f"package: name='{package}'" in badging, 'Unexpected package'
        assert f"application-label:'{label}'" in badging, 'Unexpected label'
        assert "sdkVersion:'26'" in badging and "targetSdkVersion:'36'" in badging, 'Unexpected SDK'
        assert 'android.permission.REQUEST_INSTALL_PACKAGES' in badging
        assert 'android.permission.WRITE_EXTERNAL_STORAGE' not in badging
        assert 'android.permission.MANAGE_EXTERNAL_STORAGE' not in badging
        assert 'android.permission.QUERY_ALL_PACKAGES' not in badging
        manifest = run(tools / 'aapt', 'dump', 'xmltree', apk, 'AndroidManifest.xml').stdout
        provider = next(block for block in manifest.split('E: provider') if 'androidx.core.content.FileProvider' in block)
        provider = provider.split('E: activity', 1)[0]
        assert f'="{package}.apkfiles"' in provider
        assert re.search(r'android:exported[^\n]*0x0\b', provider), 'Provider must not be exported'
        resources = run(tools / 'aapt', 'dump', '--values', 'resources', apk).stdout
        path_entries = re.findall(r'resource 0x[0-9a-f]+ [^\s]+:xml/apk_paths:[^\n]*\n\s*\(string8\) "([^"]+)"', resources)
        assert len(path_entries) == 1, 'Expected a single APK paths resource'
        paths = run(tools / 'aapt', 'dump', 'xmltree', apk, path_entries[0]).stdout
        assert re.findall(r'E: ([\w-]+)', paths) == ['paths', 'files-path']
        assert 'path="apk-downloads/verified/"' in paths
        run(tools / 'zipalign', '-c', '-p', '4', apk)
        signature = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk, success=False)
        assert (signature.returncode == 0) == (variant == 'debug' and not unsigned_debug), 'Unexpected signing state'
        with apk.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        report += [f'## {variant}', '', f'- Paquete: `{package}`; nombre: {label}.',
                   '- SDK 26/36, FileProvider limitado/no exportado y zipalign: PASS.',
                   '- Firma Debug válida: PASS.' if variant == 'debug' and not unsigned_debug else '- APK sin firma privada: confirmado.',
                   f'- SHA-256: `{digest}`.', '']
        if variant == 'debug' and not unsigned_debug:
            name = f'RobStore-DEV-{commit[:12]}.apk'
            shutil.copyfile(apk, output / name)
            (output / 'SHA256SUMS').write_text(f'{digest}  {name}\n')
            # Public certificate details only; the keystore is never copied.
            (output / 'firma-debug.txt').write_text(signature.stdout)
    (output / 'verificacion.md').write_text('\n'.join(report) + '\n')
    print('\n'.join(report))


if __name__ == '__main__':
    main()

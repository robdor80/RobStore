#!/usr/bin/env python3
"""Local integration of Gradle signing guards with two ephemeral TEST ONLY keys.
Never use this to configure owner keys. Requires JDK17, SDK and unsigned builds.
"""
import argparse
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import distribution as d


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("all", "guards", "signing"), default="all")
    phase = parser.parse_args().phase
    d.require(os.environ.get('CI') != 'true' and os.environ.get('GITHUB_ACTIONS') != 'true', 'Local fixture campaign only')
    secret = secrets.token_urlsafe(32)
    environment = dict(os.environ, ROBSTORE_TEST_PASSWORD=secret)
    environment.pop('ROBSTORE_SIGNING_FILE', None)
    environment.pop('ORG_GRADLE_PROJECT_robstoreDistribution', None)

    def execute(command, env=environment):
        result = subprocess.run(list(map(str, command)), env=env, capture_output=True, text=True)
        d.require(secret not in result.stdout + result.stderr, 'Secret leaked into tool output')
        return result

    with tempfile.TemporaryDirectory(prefix='robstore-ephemeral-signing-') as folder:
        private = Path(folder)
        pins = {}
        for channel in ('release', 'dev'):
            result = execute(['keytool', '-genkeypair', '-keystore', private / f'{channel}.p12',
                              '-alias', 'fixture', '-storetype', 'PKCS12', '-storepass:env',
                              'ROBSTORE_TEST_PASSWORD', '-keyalg', 'RSA', '-keysize', '3072',
                              '-validity', '2', '-dname', 'CN=TEST ONLY EPHEMERAL'])
            d.require(result.returncode == 0, 'Ephemeral key generation failed')
            cert = private / f'{channel}.cer'
            result = execute(['keytool', '-exportcert', '-keystore', private / f'{channel}.p12',
                              '-alias', 'fixture', '-storepass:env', 'ROBSTORE_TEST_PASSWORD', '-file', cert])
            d.require(result.returncode == 0, 'Ephemeral certificate export failed')
            pins[channel] = d.digest(cert)
        config = private / 'signing.properties'
        values = {}
        for channel in pins:
            values.update({f'{channel}.storeFile': (private / f'{channel}.p12').as_posix(),
                           f'{channel}.storePassword': secret, f'{channel}.keyPassword': secret,
                           f'{channel}.keyAlias': 'fixture', f'{channel}.certificateSha256': pins[channel]})

        def save(settings):
            config.write_text('\n'.join(f'{key}={value}' for key, value in settings.items()) + '\n')
            config.chmod(0o600)
        save(values)
        configured = dict(environment, ROBSTORE_SIGNING_FILE=str(config))
        wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'

        def gradle(label, tasks, env, expected=None, extra=()):
            result = execute([wrapper, *tasks, '--no-daemon', '--no-configuration-cache', *extra], env)
            if expected is None:
                d.require(result.returncode == 0, label + ' failed (private tool output suppressed)')
            else:
                d.require(result.returncode != 0 and expected in result.stdout + result.stderr, label + ' did not fail closed')
            print(label + ': PASS; no fixture password in output')

        if phase in ('all', 'guards'):
            for channel, task in [('release', ':app:assembleRelease'), ('dev', ':app:assembleDebug')]:
                gradle('Missing configuration ' + channel, [task, f'-ProbstoreDistribution={channel}'], environment,
                       'ROBSTORE_SIGNING_FILE must point')
            gradle('Implicit project property forbidden', [':app:assembleRelease'],
                   dict(configured, ORG_GRADLE_PROJECT_robstoreDistribution='release'),
                   'Distribution requires an explicit command-line')
            gradle('CI forbidden', [':app:assembleRelease', '-ProbstoreDistribution=release'],
                   dict(configured, CI='true'), 'Persistent distribution is local only')
            gradle('Mixed tasks forbidden', [':app:assembleRelease', ':app:testReleaseUnitTest', '-ProbstoreDistribution=release'],
                   configured, 'Distribution requires exactly')
            gradle('Configuration cache forbidden', [':app:assembleRelease', '-ProbstoreDistribution=release'], configured,
                   'Distribution forbids configuration cache', ('--configuration-cache',))
            save(dict(values, **{'release.certificateSha256': '00' * 32}))
            gradle('Wrong certificate', [':app:assembleRelease', '-ProbstoreDistribution=release'], configured,
                   'Unexpected distribution certificate')
            save(dict(values, **{'release.certificateSha256': pins['dev']}))
            gradle('Shared identity forbidden', [':app:assembleRelease', '-ProbstoreDistribution=release'], configured,
                   'Two distinct public certificate')
            save(dict(values, **{'release.storePassword': 'invalid-' + secrets.token_urlsafe(8)}))
            gradle('Wrong password redacted', [':app:assembleRelease', '-ProbstoreDistribution=release'], configured,
                   'details suppressed')
        save(values)
        override = private / 'late-unsigned.gradle'
        override.write_text("allprojects { plugins.withId('com.android.application') { afterEvaluate { android.buildTypes.configureEach { signingConfig = null } } } }\n")
        if phase in ('all', 'signing'):
            gradle('Unsigned override forbidden', [':app:assembleRelease', '-ProbstoreDistribution=release'], configured,
                   '', ('-I', str(override)))
            for channel, variant in [('release', 'release'), ('dev', 'debug')]:
                gradle('Persistent fixture ' + channel,
                       [f':app:assemble{variant.title()}', f'-ProbstoreDistribution={channel}'], configured)
                apk = Path(f'app/build/outputs/apk/{variant}/app-{variant}.apk')
                d.inspect_apk(apk, channel, pins[channel])
                wrong = pins['dev' if channel == 'release' else 'release']
                try:
                    d.inspect_apk(apk, channel, wrong)
                except ValueError:
                    pass
                else:
                    raise ValueError('Wrong fixture certificate accepted')
                print('Final signed APK identity/certificate ' + channel + ': PASS')
        daemon_logs = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'daemon'
        for log in daemon_logs.glob('*/*.out.log'):
            d.require(secret.encode() not in log.read_bytes(), 'Fixture password leaked into daemon logs')
        print('Gradle daemon logs: no fixture password found')
    print('Only ephemeral fixtures used; private directory removed. No owner signature validated.')


if __name__ == '__main__':
    main()

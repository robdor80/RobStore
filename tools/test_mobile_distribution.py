"""Mobile pipeline behavior; no owner secrets, GitHub writes or real publications."""
import argparse
import base64
import io
import json
import os
import secrets
import shutil
import subprocess
from pathlib import Path
import tempfile
import tarfile
import unittest
from unittest.mock import patch
import mobile_distribution as m
import distribution as d
import android_owner_setup as owner_setup
import test_distribution_signed as signed_fixtures

SOURCE = 'a' * 40
REPO = 'robdor80/RobStore'
ENV = dict(GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/main', GITHUB_ACTOR='robdor80',
           GITHUB_TRIGGERING_ACTOR='robdor80', GITHUB_REPOSITORY=REPO, GITHUB_RUN_ID='12345')
EVENT = dict(repository=dict(full_name=REPO, fork=False, default_branch='main'))


class MobilePolicyTests(unittest.TestCase):
    def test_owner_explicit_dispatch(self):
        with patch.dict(os.environ, ENV):
            m.identity(EVENT, REPO, SOURCE, 'release')
            m.identity(EVENT, REPO, SOURCE, 'dev')

    def test_unauthorized_events_branches_actors(self):
        for change in (dict(GITHUB_EVENT_NAME='push'), dict(GITHUB_EVENT_NAME='pull_request_target'),
                       dict(GITHUB_REF='refs/heads/feature'), dict(GITHUB_REF='refs/tags/v0.1.0'),
                       dict(GITHUB_ACTOR='someone'), dict(GITHUB_TRIGGERING_ACTOR='someone')):
            with self.subTest(change=change), patch.dict(os.environ, dict(ENV, **change)), self.assertRaises(ValueError):
                m.identity(EVENT, REPO, SOURCE, 'release')

    def test_invalid_channels_tags_and_forks(self):
        with patch.dict(os.environ, ENV):
            with self.assertRaises(ValueError):
                m.identity(EVENT, REPO, SOURCE, 'other')
            with self.assertRaises(ValueError):
                m.identity(EVENT, REPO, SOURCE, 'release', 'unsafe tag')
            with self.assertRaises(ValueError):
                m.identity(dict(repository=dict(EVENT['repository'], fork=True)), REPO, SOURCE, 'release')

    def test_environment_requires_no_approvals_and_only_main(self):
        good = dict(protection_rules=[dict(type='branch_policy')],
                    deployment_branch_policy=dict(custom_branch_policies=True, protected_branches=False))
        policies = dict(branch_policies=[dict(name='main', type='branch')])
        with patch.object(m, 'api', side_effect=[good, policies]):
            m.protected_environment(REPO, 'android-release')
        for environment, branches in ((dict(good, protection_rules=[dict(type='required_reviewers')]), policies),
                                      (dict(good, protection_rules=[dict(type='wait_timer')]), policies),
                                      (good, dict(branch_policies=[dict(name='*', type='branch')])),
                                      (good, dict(branch_policies=[dict(name='main', type='tag')]))):
            with self.subTest(branches=branches), patch.object(m, 'api', side_effect=[environment, branches]), self.assertRaises(ValueError):
                m.protected_environment(REPO, 'android-release')

    def test_setup_creates_environments_without_approval_or_secret_writes(self):
        calls = []
        def api(path, method='GET', value=None):
            calls.append((path, method, value))
            return dict(environments=[]) if method == 'GET' else {}
        with patch.object(owner_setup, 'gh_api', side_effect=api), patch.object(m, 'protected_environment'):
            owner_setup.ensure_environments(REPO)
        environments = [value for path, method, value in calls if method == 'PUT']
        self.assertEqual(len(environments), 3)
        for value in environments:
            self.assertEqual(value['reviewers'], [])
            self.assertEqual(value['wait_timer'], 0)
        self.assertFalse(any('/secrets' in path or '/variables' in path for path, _, _ in calls))

    def test_migration_has_no_password_keystore_or_secret_operations(self):
        with patch('sys.argv', ['setup', '--repository', REPO, '--environments-only']), \
                patch.object(owner_setup, 'gh_api', return_value=dict(login='robdor80')), \
                patch.object(owner_setup, 'ensure_environments') as configure, \
                patch.object(owner_setup.getpass, 'getpass', side_effect=AssertionError('No password prompt')), \
                patch.object(owner_setup, 'command', side_effect=AssertionError('No key or secret commands')):
            owner_setup.main()
        configure.assert_called_once_with(REPO)

    def test_setup_never_overwrites_existing_signing_secrets(self):
        values = dict(SIGNING_KEYSTORE_B64='fixture', SIGNING_ALIAS='fixture')
        with patch.object(owner_setup, 'gh_api', return_value=dict(secrets=[dict(name=k) for k in values])), \
                patch.object(owner_setup, 'command') as command:
            owner_setup.provision_signing_secrets(REPO, 'android-release', values)
        command.assert_not_called()

    def test_migration_clears_existing_approvals_without_recreating_environments(self):
        calls = []
        def api(path, method='GET', value=None):
            calls.append((path, method, value))
            return dict(environments=[dict(name='android-' + c) for c in ('release', 'dev', 'publish')])
        with patch.object(owner_setup, 'gh_api', side_effect=api), patch.object(m, 'protected_environment'):
            owner_setup.ensure_environments(REPO)
        writes = [(path, method, value) for path, method, value in calls if method != 'GET']
        self.assertEqual(len(writes), 3)
        self.assertTrue(all(method == 'PUT' and value['reviewers'] == [] and value['wait_timer'] == 0 for _, method, value in writes))
        self.assertFalse(any('/secrets' in path or '/variables' in path or '/deployment-branch-policies' in path for path, _, _ in writes))

    def guard_fixture(self, main=SOURCE, notes='Versioned fixture notes'):
        args = argparse.Namespace(source=SOURCE, channel='release', tag='', config_output='fixture')
        def api(path, *unused):
            if path.endswith('/branches/main'):
                return dict(commit=dict(sha=main))
            raise AssertionError('Unexpected API; no prior run or separate CI approval is required')
        with patch.dict(os.environ, dict(ENV, GITHUB_EVENT_PATH='fixture')), patch.object(d, 'load', return_value=EVENT), \
                patch.object(m, 'api', side_effect=api), patch.object(m, 'protected_environment') as environment, \
                patch.object(m, 'raw_file', side_effect=lambda repo, ref, path: Path('.github/android-distribution.json').read_text() if path == m.CONFIG else notes), patch.object(d, 'write'):
            m.guard(args)
        self.assertEqual(environment.call_args_list, [unittest.mock.call(REPO, 'android-release'), unittest.mock.call(REPO, 'android-publish')])

    def test_guard_requires_current_main_without_another_authorization(self):
        self.guard_fixture()
        with self.assertRaises(ValueError):
            self.guard_fixture(main='c' * 40)

    def test_configuration_separates_packages_and_paths(self):
        good = d.load('.github/android-distribution.json')
        m.config(good, REPO)
        for change in (dict(packages=dict(release=d.PACKAGES['release'], dev=d.PACKAGES['release'])),
                       dict(notesPath='../private'), dict(module='app;echo'), dict(repository='someone/RobStore')):
            with self.subTest(change=change), self.assertRaises(ValueError):
                m.config(dict(good, **change), REPO)

    def test_existing_setup_needs_no_passwords_or_local_keys(self):
        with patch('sys.argv', ['setup', '--repository', REPO]), \
                patch.object(owner_setup, 'gh_api', return_value=dict(login='robdor80')), \
                patch.object(owner_setup, 'configured', return_value=True), \
                patch.object(owner_setup, 'ensure_environments') as configure, \
                patch.object(owner_setup.getpass, 'getpass', side_effect=AssertionError('No passwords')), \
                patch.object(owner_setup, 'command', side_effect=AssertionError('No keys')):
            owner_setup.main()
        configure.assert_called_once_with(REPO)

    def test_initial_keys_never_enter_agent_terminal(self):
        with patch.dict(os.environ, CODEX_THREAD_ID='fixture'), \
                patch('sys.argv', ['setup', '--repository', REPO]), \
                patch.object(owner_setup, 'gh_api', return_value=dict(login='robdor80')), \
                patch.object(owner_setup, 'configured', return_value=False), self.assertRaises(ValueError):
            owner_setup.main()

    def test_online_versions_never_allow_equal_or_lower(self):
        contract = dict(channel='release', packageName=d.PACKAGES['release'], versionCode=2, apkAssetName='Android-release.apk', sha256='ab' * 32)
        release = dict(draft=False, prerelease=False, tag_name='v0.1.0', assets=[dict(name='Android-release.apk', state='uploaded', digest='sha256:' + 'ab' * 32)])
        for code in (2, 3):
            with self.subTest(code=code), patch.object(m, 'pages', return_value=[release]), \
                    patch.object(m, 'raw_file', return_value=json.dumps(dict(contract, versionCode=code))), self.assertRaises(ValueError):
                m.increasing(REPO, contract)
        with patch.object(m, 'pages', return_value=[release]), patch.object(m, 'raw_file', return_value=json.dumps(dict(contract, versionCode=1))):
            m.increasing(REPO, contract)

    def test_publish_dry_plan_never_writes(self):
        settings = d.load('.github/android-distribution.json')
        contract = dict(channel='release')
        args = argparse.Namespace(config_output='fixture', directory='fixture', source=SOURCE,
                                  tag='v0.1.0', execute=False)
        with patch.dict(os.environ, ENV), patch.object(m, 'guard'), patch.object(d, 'load', return_value=settings), \
                patch.object(m, 'candidate', return_value=(contract, {}, Path('fixture.apk'))), \
                patch.object(m, 'increasing'), patch.object(m, 'pages', return_value=[]), \
                patch.object(m, 'raw_file', return_value='Approved notes'), \
                patch.object(m, 'publication_files', return_value={d.CONTRACT: '{}'}), patch.object(m, 'api') as api:
            m.publish(args)
            api.assert_not_called()

    def test_main_source_changed_stops_before_candidate_or_writes(self):
        with patch.object(m, 'guard', side_effect=ValueError('Source changed')), patch.object(m, 'candidate') as candidate, patch.object(m, 'api') as api:
            with self.assertRaises(ValueError):
                m.publish(argparse.Namespace())
            candidate.assert_not_called()
            api.assert_not_called()

    def publication_fixture(self, upload_fails=False):
        with tempfile.TemporaryDirectory(prefix='publish-api-fixture-') as folder:
            root = Path(folder)
            apk = root / 'Android-release.apk'; apk.write_bytes(b'API transaction fixture; not a production APK')
            contract = dict(schemaVersion=1, channel='release', packageName=d.PACKAGES['release'], versionCode=1,
                            versionName='0.1.0', apkAssetName=apk.name, sha256=d.digest(apk))
            (root / 'SHA256SUMS').write_text(f"{contract['sha256']}  {apk.name}\n")
            settings = d.load('.github/android-distribution.json')
            calls = []
            def api(path, method='GET', data=None):
                calls.append((path, method, data))
                if path.endswith('/git/commits/' + SOURCE):
                    return dict(tree=dict(sha='b' * 40))
                if path.endswith('/git/blobs'):
                    return dict(sha='c' * 40)
                if path.endswith('/git/trees'):
                    return dict(sha='d' * 40)
                if path.endswith('/git/commits'):
                    return dict(sha='e' * 40)
                if '/compare/' in path:
                    return dict(commits=[{}], files=[dict(filename=d.CONTRACT, status='added')])
                if path.endswith('/branches/main'):
                    return dict(commit=dict(sha=SOURCE))
                if path.endswith('/git/tags'):
                    return dict(sha='f' * 40)
                if path.endswith('/git/refs'):
                    return {}
                if path.endswith('/releases'):
                    return dict(id=1, html_url='https://github.com/robdor80/RobStore/releases/tag/v0.1.0')
                if path.endswith('/releases/1'):
                    return dict(assets=[dict(name=apk.name, state='uploaded', size=apk.stat().st_size, digest='sha256:' + contract['sha256'])])
                raise AssertionError(path)
            def raw(repo, ref, path):
                return json.dumps(contract) if path == d.CONTRACT else 'Approved fixture notes'
            args = argparse.Namespace(config_output='fixture', source=SOURCE, tag='v0.1.0', channel='release', execute=True, directory=str(root))
            with patch.dict(os.environ, ENV), \
                    patch.object(m, 'guard'), patch.object(d, 'load', return_value=settings), \
                    patch.object(m, 'candidate', return_value=(contract, {}, apk)), patch.object(m, 'increasing'), \
                    patch.object(m, 'pages', return_value=[]), patch.object(m, 'raw_file', side_effect=raw), \
                    patch.object(m, 'publication_files', return_value={d.CONTRACT: json.dumps(contract)}), \
                    patch.object(m, 'api', side_effect=api), \
                    patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 1 if upload_fails else 0)), \
                    patch.object(d, 'run', side_effect=AssertionError('Never rebuild or sign during publication')), patch('builtins.print'):
                if upload_fails:
                    with self.assertRaises(ValueError):
                        m.publish(args)
                else:
                    m.publish(args)
            writes = [(path, method, data) for path, method, data in calls if method != 'GET']
            self.assertEqual(next(data['parents'] for path, method, data in writes if path.endswith('/git/commits')), [SOURCE])
            self.assertEqual(next(data['ref'] for path, method, data in writes if path.endswith('/git/refs')), 'refs/tags/v0.1.0')
            self.assertTrue(next(data['draft'] for path, method, data in writes if path.endswith('/releases')))
            finalizations = [data for path, method, data in writes if method == 'PATCH']
            self.assertEqual(finalizations, [] if upload_fails else [dict(draft=False)])

    def test_publication_transaction_without_rebuilding(self):
        self.publication_fixture()

    def test_failed_upload_keeps_draft_and_never_finalizes(self):
        self.publication_fixture(upload_fails=True)


class MobileSignedTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = signed_fixtures.SignedApkTests
        cls.fixture.setUpClass()
        cls.addClassCleanup(cls.fixture.doClassCleanups)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='mobile-fixture-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.settings = d.load('.github/android-distribution.json')
        self.config_path = self.root / 'config.json'
        d.write(self.config_path, self.settings)
        self.args = argparse.Namespace(source=SOURCE, channel='release', tag='v0.1.0',
            config_output=str(self.config_path), apk=str(self.fixture.source.resolve()), directory=str(self.root / 'candidate'))
        password = self.fixture.env['ROBSTORE_TEST_PASSWORD']
        self.env = dict(ENV, SIGNING_KEYSTORE_B64=base64.b64encode((self.fixture.directory / 'fixture-1.p12').read_bytes()).decode(),
                        SIGNING_STORE_PASSWORD=password, SIGNING_KEY_PASSWORD=password, SIGNING_ALIAS='fixture',
                        CERTIFICATE_SHA256=self.fixture.certificates[0], OTHER_CERTIFICATE_SHA256=self.fixture.certificates[1],
                        GITHUB_RUN_ID='12345', GITHUB_STEP_SUMMARY='', ACTIONS_STEP_DEBUG='', ACTIONS_RUNNER_DEBUG='')

    def sign(self):
        with patch.dict(os.environ, self.env), patch.object(m, 'increasing'), patch('builtins.print'):
            m.sign(self.args)
        self.contract = d.load(Path(self.args.directory) / d.CONTRACT)

    def test_real_sign_contract_and_same_run_provenance(self):
        self.sign()
        with patch.dict(os.environ, self.env):
            contract, receipt, apk = m.candidate(self.args, self.settings)
            self.assertEqual(receipt['sourceCommit'], SOURCE)
            self.assertEqual(d.digest(apk), contract['sha256'])
        with patch.dict(os.environ, dict(self.env, GITHUB_RUN_ID='99999')), self.assertRaises(ValueError):
            m.candidate(self.args, self.settings)

    def test_automatic_sign_derives_tag_from_apk(self):
        self.args.tag = ''
        self.sign()
        self.assertEqual(self.args.tag, 'v0.1.0')

    def test_wrong_key_and_shared_channel_pins(self):
        for pin in (self.fixture.certificates[1],):
            with patch.dict(os.environ, dict(self.env, CERTIFICATE_SHA256=pin)), self.assertRaises(ValueError):
                m.sign(self.args)
        with patch.dict(os.environ, dict(self.env, CERTIFICATE_SHA256='00' * 32)), patch.object(m, 'increasing'), self.assertRaises(ValueError):
            m.sign(self.args)

    def test_tampered_receipt_and_apk(self):
        self.sign()
        receipt_path = Path(self.args.directory) / d.RECEIPT
        receipt = d.load(receipt_path)
        d.write(receipt_path, dict(receipt, sourceCommit='c' * 40))
        with patch.dict(os.environ, self.env), self.assertRaises(ValueError):
            m.candidate(self.args, self.settings)
        d.write(receipt_path, receipt)
        apk = Path(self.args.directory) / self.contract['apkAssetName']
        body = bytearray(apk.read_bytes()); body[100] ^= 1; apk.write_bytes(body)
        with patch.dict(os.environ, self.env), self.assertRaises(ValueError):
            m.candidate(self.args, self.settings)

    def test_generic_application_policy(self):
        self.sign()
        settings = dict(self.settings, repository='robdor80/FixtureApp', robstorePolicy=False)
        apk = Path(self.args.directory) / self.contract['apkAssetName']
        m.inspect(apk, 'release', self.fixture.certificates[0], settings)
        with self.assertRaises(ValueError):
            m.inspect(apk, 'dev', self.fixture.certificates[0], settings)

    def test_import_actual_ephemeral_key_preserves_certificate(self):
        password = self.fixture.env['ROBSTORE_TEST_PASSWORD']
        destination = self.root / 'normalized-release.p12'
        with patch('builtins.input', return_value='fixture'), patch.object(owner_setup.getpass, 'getpass', return_value=password):
            owner_setup.import_key(self.fixture.directory / 'fixture-1.p12', destination, password, 'android-release',
                                       self.fixture.certificates[0])
        certificate = owner_setup.command(['keytool', '-exportcert', '-keystore', destination,
            '-alias', 'android-release', '-storepass:env', 'ROBSTORE_TEST_PASSWORD'], environment=self.fixture.env)
        self.assertEqual(__import__('hashlib').sha256(certificate).hexdigest(), self.fixture.certificates[0])

    def test_catalog_manifest_uses_final_apk_metadata(self):
        self.sign()
        icon = Path(self.settings['iconSource']).read_bytes()
        with patch.object(m, 'api', return_value=dict(encoding='base64', size=len(icon), content=base64.b64encode(icon).decode())):
            files = m.publication_files(self.settings, SOURCE, self.contract, {})
        manifest = json.loads(files['robstore.json'])
        for key in ('packageName', 'versionCode', 'versionName', 'apkAssetName'):
            self.assertEqual(manifest[key], self.contract[key])
        self.assertEqual(files['robstore/icon.png'], icon)


class BackupCryptoTests(unittest.TestCase):
    def recovery(self, tamper=False):
        with tempfile.TemporaryDirectory(prefix='android-backup-fixture-') as folder:
            root = Path(folder)
            for name, body in [('release.p12', b'test-only-release-bytes'), ('dev.p12', b'test-only-dev-bytes'),
                               ('public-identities.json', b'{"repository":"robdor80/FixtureApp"}')]:
                (root / name).write_bytes(body)
            home = root / 'gpg'; home.mkdir(mode=0o700)
            password = root / 'fixture-password'; password.write_text(secrets.token_urlsafe(32)); password.chmod(0o600)
            restored = root / 'drive-download.gpg'
            real_run = subprocess.run
            def gpg(args, **kwargs):
                cmd = ['gpg', '--batch', '--yes', '--pinentry-mode', 'loopback', '--passphrase-file', str(password)] + args[1:]
                kwargs['capture_output'] = True
                result = real_run(cmd, **kwargs)
                if '--symmetric' in args and result.returncode == 0:
                    shutil.copyfile(args[args.index('--output') + 1], restored)
                    if tamper:
                        restored.write_bytes(restored.read_bytes() + b'changed')
                return result
            with patch.dict(os.environ, GNUPGHOME=str(home)), patch.object(subprocess, 'run', side_effect=gpg), \
                    patch('builtins.input', return_value=str(restored)), patch('builtins.print'):
                owner_setup.encrypted_backup(root, 'robdor80/FixtureApp')
                destination = root / 'restored-private'
                owner_setup.restore_backup(destination, restored, 'robdor80/FixtureApp')
                self.assertEqual((destination / 'release.p12').read_bytes(), b'test-only-release-bytes')
                self.assertEqual((destination / 'dev.p12').stat().st_mode & 0o777, 0o600)

    def test_optional_local_encrypted_backup_and_recovery(self):
        self.recovery()

    def test_backup_path_traversal_rejected_before_extracting(self):
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode='w') as tar:
            entry = tarfile.TarInfo('../release.p12'); entry.size = 1
            tar.addfile(entry, io.BytesIO(b'x'))
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); ciphertext = root / 'fixture.gpg'; ciphertext.write_bytes(b'fixture')
            with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, archive.getvalue(), b'')), self.assertRaises(ValueError):
                owner_setup.restore_backup(root / 'private', ciphertext, 'robdor80/FixtureApp')
            self.assertFalse((root / 'private').exists())


if __name__ == '__main__':
    unittest.main()

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
            if path.endswith('/deployment-branch-policies') and method == 'GET':
                return dict(branch_policies=[])
            return dict(environments=[]) if method == 'GET' else {}
        with patch.object(owner_setup, 'gh_api', side_effect=api), patch.object(m, 'protected_environment'):
            owner_setup.ensure_environments(REPO)
        environments = [value for path, method, value in calls if method == 'PUT']
        self.assertEqual(len(environments), 3)
        for value in environments:
            self.assertEqual(value['reviewers'], [])
            self.assertEqual(value['wait_timer'], 0)
        self.assertFalse(any('/secrets' in path or '/variables' in path for path, _, _ in calls))

    def test_release_only_setup_needs_no_dev_environment(self):
        def api(path, method='GET', value=None):
            if path.endswith('/environments'):
                return {'environments': [{'name': 'android-release'}, {'name': 'android-publish'}]}
            if path.endswith('/android-release/secrets'):
                return {'secrets': [{'name': name} for name in ('SIGNING_KEYSTORE_B64', 'SIGNING_STORE_PASSWORD')]}
            if path.endswith('/android-release/variables'):
                return {'variables': [{'name': 'CERTIFICATE_SHA256', 'value': 'a' * 64}]}
            raise AssertionError('Unexpected GitHub API lookup: ' + path)
        with patch.object(owner_setup, 'gh_api', side_effect=api):
            self.assertTrue(owner_setup.configured(REPO, release_only=True))
            self.assertFalse(owner_setup.configured(REPO))

    def test_release_only_creates_no_dev_environment(self):
        calls = []
        def api(path, method='GET', value=None):
            calls.append((path, method, value))
            if path.endswith('/deployment-branch-policies') and method == 'GET':
                return {'branch_policies': []}
            return {'environments': []} if method == 'GET' else {}
        with patch.object(owner_setup, 'gh_api', side_effect=api), patch.object(m, 'protected_environment'):
            owner_setup.ensure_environments(REPO, release_only=True)
        writes = [path for path, method, _ in calls if method == 'PUT']
        self.assertEqual(len(writes), 2)
        self.assertTrue(any(path.endswith('/android-release') for path in writes))
        self.assertTrue(any(path.endswith('/android-publish') for path in writes))
        self.assertFalse(any('/android-dev' in path for path, _, _ in calls))

    def test_workflow_created_empty_environment_is_not_a_release_key(self):
        calls = []
        def api(path, method='GET', value=None):
            calls.append(path)
            if path.endswith('/android-publish/variables') or path.endswith('/android-release/variables'):
                return {'variables': []}
            if path.endswith('/android-release/secrets'):
                return {'secrets': []}
            raise AssertionError('Unexpected remote read: ' + path)
        with patch.object(owner_setup, 'gh_api', side_effect=api):
            recorded = owner_setup.recorded_signing_identities(
                REPO, ('release',), {'android-release', 'android-publish'})
        self.assertFalse(recorded['release'])
        self.assertFalse(any('android-dev' in p for p in calls))

    def test_existing_secret_or_certificate_pin_prevents_new_key(self):
        def api(path, method='GET', value=None):
            if path.endswith('/android-release/secrets'):
                return {'secrets': [{'name': 'SIGNING_KEYSTORE_B64'}]}
            if path.endswith('/android-release/variables') or path.endswith('/android-publish/variables'):
                return {'variables': []}
            raise AssertionError('Unexpected remote read: ' + path)
        with patch.object(owner_setup, 'gh_api', side_effect=api):
            recorded = owner_setup.recorded_signing_identities(
                REPO, ('release',), {'android-release', 'android-publish'})
        self.assertTrue(recorded['release'])

        def pinned(path, method='GET', value=None):
            if path.endswith('/android-publish/variables'):
                return {'variables': [{'name': 'RELEASE_CERTIFICATE_SHA256', 'value': 'a' * 64}]}
            if path.endswith('/android-release/secrets'):
                return {'secrets': []}
            if path.endswith('/android-release/variables'):
                return {'variables': []}
            raise AssertionError('Unexpected remote read: ' + path)
        with patch.object(owner_setup, 'gh_api', side_effect=pinned):
            recorded = owner_setup.recorded_signing_identities(
                REPO, ('release',), {'android-release', 'android-publish'})
        self.assertTrue(recorded['release'])

    def test_existing_workflow_environment_without_main_policy_is_repaired(self):
        calls = []
        def api(path, method='GET', value=None):
            calls.append((path, method, value))
            if path.endswith('/environments'):
                return {'environments': [{'name': 'android-release'}, {'name': 'android-publish'}]}
            if path.endswith('/deployment-branch-policies'):
                return {'branch_policies': []} if method == 'GET' else {}
            return {}
        with patch.object(owner_setup, 'gh_api', side_effect=api), patch.object(m, 'protected_environment'):
            owner_setup.ensure_environments(REPO, release_only=True)
        added = [(path, value) for path, method, value in calls if method == 'POST']
        self.assertEqual(len(added), 2)
        self.assertTrue(all(path.endswith('/deployment-branch-policies') and value == {'name': 'main', 'type': 'branch'}
                            for path, value in added))

    def test_migration_has_no_password_keystore_or_secret_operations(self):
        with patch('sys.argv', ['setup', '--repository', REPO, '--environments-only']), \
                patch.object(owner_setup, 'gh_api', return_value=dict(login='robdor80')), \
                patch.object(owner_setup, 'ensure_environments') as configure, \
                patch.object(owner_setup.getpass, 'getpass', side_effect=AssertionError('No password prompt')), \
                patch.object(owner_setup, 'command', side_effect=AssertionError('No key or secret commands')):
            owner_setup.main()
        configure.assert_called_once_with(REPO, release_only=False)

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
            if path.endswith('/deployment-branch-policies'):
                return dict(branch_policies=[dict(name='main', type='branch')])
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
        environment.assert_not_called()  # Las publicaciones personales no requieren entornos previos.

    def test_guard_requires_current_main_without_another_authorization(self):
        self.guard_fixture()
        with self.assertRaises(ValueError):
            self.guard_fixture(main='c' * 40)

    def test_tags_need_no_manual_edit_when_only_android_version_code_changes(self):
        first = dict(channel='release', versionName='1.0 (personal)', versionCode=1)
        second = dict(first, versionCode=2)
        self.assertNotEqual(m.expected_tag(first), m.expected_tag(second))
        m.validate_tag('release', m.expected_tag(first))
        m.validate_tag('release', m.expected_tag(second))

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
        configure.assert_called_once_with(REPO, release_only=False)

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

    def test_older_publication_date_cannot_hide_a_higher_version_code(self):
        contract = dict(channel='release', packageName=d.PACKAGES['release'], versionCode=4)
        releases = [dict(draft=False, prerelease=False, tag_name='recent', published_at='2026-11-08'),
                    dict(draft=False, prerelease=False, tag_name='older', published_at='2026-11-01')]
        def raw(repo, ref, path, **kwargs):
            return json.dumps(dict(contract, versionCode=3 if ref.endswith('/recent') else 5))
        with patch.object(m, 'pages', return_value=releases), patch.object(m, 'raw_file', side_effect=raw), \
                self.assertRaises(ValueError):
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

    def test_published_release_is_never_replaced_on_retry(self):
        args = argparse.Namespace(config_output='fixture', tag='v0.1.0')
        settings = d.load('.github/android-distribution.json')
        with patch.dict(os.environ, ENV), patch.object(m, 'guard'), patch.object(d, 'load', return_value=settings), \
                patch.object(m, 'candidate', return_value=({}, {}, Path('fixture.apk'))), patch.object(m, 'increasing'), \
                patch.object(m, 'pages', side_effect=lambda path: [dict(tag_name=args.tag, draft=False)] if path.endswith('/releases') else []), \
                patch.object(m, 'api') as api, self.assertRaises(ValueError):
            m.publish(args)
        api.assert_not_called()

    def test_main_source_changed_stops_before_candidate_or_writes(self):
        with patch.object(m, 'guard', side_effect=ValueError('Source changed')), patch.object(m, 'candidate') as candidate, patch.object(m, 'api') as api:
            with self.assertRaises(ValueError):
                m.publish(argparse.Namespace())
            candidate.assert_not_called()
            api.assert_not_called()

    def publication_fixture(self, upload_fails=False, existing_draft=False, tag_moved=False, injected_asset=False):
        with tempfile.TemporaryDirectory(prefix='publish-api-fixture-') as folder:
            root = Path(folder)
            apk = root / 'Android-release.apk'; apk.write_bytes(b'API transaction fixture; not a production APK')
            contract = dict(schemaVersion=1, channel='release', packageName=d.PACKAGES['release'], versionCode=1,
                            versionName='0.1.0', apkAssetName=apk.name, sha256=d.digest(apk))
            (root / 'SHA256SUMS').write_text(f"{contract['sha256']}  {apk.name}\n")
            settings = d.load('.github/android-distribution.json')
            calls = []
            uploaded_assets = [dict(name='unauthorized.apk')] if injected_asset else []
            def upload(command, **kwargs):
                self.assertNotIn('--clobber', command)
                if not upload_fails:
                    file = Path(command[4])
                    uploaded_assets.append(dict(name=file.name, state='uploaded', size=file.stat().st_size,
                                                digest='sha256:' + d.digest(file)))
                return subprocess.CompletedProcess(command, 1 if upload_fails else 0)
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
                if path.endswith('/git/refs') or '/git/refs/tags/' in path:
                    return {}
                if path.endswith('/releases'):
                    return dict(id=1, html_url='https://github.com/robdor80/RobStore/releases/tag/v0.1.0')
                if path.endswith('/releases/1'):
                    return dict(draft=True, prerelease=False, tag_name='v0.1.0', assets=uploaded_assets)
                raise AssertionError(path)
            def raw(repo, ref, path):
                return json.dumps(contract) if path == d.CONTRACT else 'Approved fixture notes'
            args = argparse.Namespace(config_output='fixture', source=SOURCE, tag='v0.1.0', channel='release', execute=True, directory=str(root))
            with patch.dict(os.environ, ENV), \
                    patch.object(m, 'guard'), patch.object(d, 'load', return_value=settings), \
                    patch.object(m, 'candidate', return_value=(contract, {}, apk)), patch.object(m, 'increasing'), \
                    patch.object(m, 'pages', side_effect=lambda path: ([dict(id=1, tag_name='v0.1.0', draft=True, html_url='fixture-release')] if path.endswith('/releases') else [dict(name='v0.1.0')] if path.endswith('/tags') else []) if existing_draft else []), patch.object(m, 'raw_file', side_effect=raw), \
                    patch.object(m, 'publication_files', return_value={d.CONTRACT: json.dumps(contract)}), \
                    patch.object(m, 'reusable_closure', return_value='e' * 40), \
                    patch.object(m, 'publication_commit', return_value=('0' if tag_moved else 'e') * 40), \
                    patch.object(m, 'api', side_effect=api), \
                    patch.object(subprocess, 'run', side_effect=upload), \
                    patch.object(d, 'run', side_effect=AssertionError('Never rebuild or sign during publication')), patch.object(m.time, 'sleep'), patch('builtins.print'):
                if upload_fails or tag_moved or injected_asset:
                    with self.assertRaises(ValueError):
                        m.publish(args)
                else:
                    m.publish(args)
            writes = [(path, method, data) for path, method, data in calls if method != 'GET']
            if existing_draft:
                self.assertFalse(any('/git/' in path for path, method, _ in writes))
                self.assertFalse(any(path.endswith('/releases') for path, _, _ in writes))
            else:
                self.assertEqual(next(data['parents'] for path, method, data in writes if path.endswith('/git/commits')), [SOURCE])
                self.assertEqual(next(data['ref'] for path, method, data in writes if path.endswith('/git/refs')), 'refs/tags/v0.1.0')
                self.assertTrue(next(data['draft'] for path, method, data in writes if path.endswith('/releases')))
            finalizations = [data for path, method, data in writes if method == 'PATCH' and data == dict(draft=False)]
            self.assertEqual(finalizations, [] if upload_fails or tag_moved or injected_asset else [dict(draft=False)])

    def test_retry_resumes_draft_without_manual_tag_or_release_cleanup(self):
        self.publication_fixture(existing_draft=True)

    def test_publication_transaction_without_rebuilding(self):
        self.publication_fixture()

    def test_failed_upload_keeps_draft_and_never_finalizes(self):
        self.publication_fixture(upload_fails=True)

    def test_tag_changed_during_upload_never_finalizes(self):
        self.publication_fixture(tag_moved=True)

    def test_unexpected_draft_asset_never_finalizes(self):
        self.publication_fixture(injected_asset=True)


class DraftRecoveryTests(unittest.TestCase):
    def closure(self, receipt_change=None, code_changed=False, file_changed=False, draft=True):
        settings = d.load('.github/android-distribution.json')
        args = argparse.Namespace(source=SOURCE, tag='v0.1.3-4', channel='release')
        contract = dict(schemaVersion=1, channel='release', packageName=d.PACKAGES['release'],
                        versionCode=4, versionName='0.1.3', apkAssetName='Android-release.apk', sha256='a' * 64)
        receipt = dict(schemaVersion=1, sourceCommit=SOURCE, channel='release', certificateSha256='b' * 64,
                       apkSha256=contract['sha256'], repository=REPO, preparationRun='222', tag=args.tag)
        previous = dict(receipt, preparationRun='111', **(receipt_change or {}))
        files = {d.CONTRACT: json.dumps(contract), d.RECEIPT: json.dumps(previous)}
        commit = 'c' * 40
        calls = []
        def api(path, method='GET', data=None):
            calls.append((path, method))
            self.assertEqual(method, 'GET')
            if '/git/ref/' in path:
                return dict(object=dict(type='tag', sha='d' * 40))
            if '/git/tags/' in path:
                return dict(object=dict(type='commit', sha=commit))
            if '/git/commits/' in path:
                return dict(parents=[dict(sha=SOURCE)])
            if '/compare/' in path:
                return dict(commits=[{}], files=[dict(filename='app/build.gradle.kts' if code_changed else d.CONTRACT, status='modified')])
            if '/contents/' in path:
                name = path.split('/contents/')[1].split('?')[0]
                body = b'altered' if file_changed else files[name].encode()
                return dict(encoding='base64', content=base64.b64encode(body).decode())
            raise AssertionError(path)
        with patch.object(m, 'api', side_effect=api), \
                patch.object(m, 'raw_file', side_effect=lambda repo, ref, name: files[name]), \
                patch.object(m, 'publication_files', return_value=files):
            result = m.reusable_closure(args, settings, contract, receipt,
                                        dict(draft=True, prerelease=False) if draft else None)
        self.assertTrue(calls)
        self.assertEqual(result, commit)

    def test_same_source_signer_and_bytes_reuse_original_immutable_tag(self):
        self.closure()

    def test_other_source_signer_hash_or_withdrawn_release_never_replaces_tag(self):
        for change in (dict(sourceCommit='e' * 40), dict(certificateSha256='e' * 64), dict(apkSha256='e' * 64)):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.closure(receipt_change=change)
        with self.assertRaises(ValueError):
            self.closure(draft=False)

    def test_changed_closure_or_application_code_blocks_retry(self):
        for change in (dict(code_changed=True), dict(file_changed=True)):
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.closure(**change)

    def upload(self, asset=None, fail_after_upload=False, draft=True):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / 'Android-release.apk'; file.write_bytes(b'exact verified fixture bytes')
            exact = dict(id=1, name=file.name, state='uploaded', size=file.stat().st_size, digest='sha256:' + d.digest(file))
            assets = [] if asset is None else [dict(exact, **asset)]
            calls = []
            def api(path, method='GET', data=None):
                calls.append((path, method))
                if method == 'DELETE':
                    assets.clear(); return None
                return dict(draft=draft, tag_name='v0.1.3-4', assets=assets)
            def upload(command, **kwargs):
                self.assertNotIn('--clobber', command)
                assets.append(exact)
                return subprocess.CompletedProcess(command, 1 if fail_after_upload else 0)
            with patch.object(m, 'api', side_effect=api), patch.object(subprocess, 'run', side_effect=upload) as run, \
                    patch.object(m.time, 'sleep'):
                m.upload_draft_file(REPO, 1, 'v0.1.3-4', file)
            return calls, run.call_count

    def test_exact_uploaded_asset_is_reused_without_upload_or_delete(self):
        calls, uploads = self.upload(asset={})
        self.assertEqual(uploads, 0)
        self.assertTrue(all(method == 'GET' for _, method in calls))

    def test_lost_upload_response_recovers_without_overwriting_bytes(self):
        _, uploads = self.upload(fail_after_upload=True)
        self.assertEqual(uploads, 1)

    def test_only_empty_starter_asset_can_be_removed_for_recovery(self):
        calls, uploads = self.upload(asset=dict(state='starter', size=0))
        self.assertEqual(uploads, 1)
        self.assertEqual(sum(method == 'DELETE' for _, method in calls), 1)
        for asset in (dict(digest='sha256:' + 'f' * 64), dict(state='starter', size=1)):
            with self.subTest(asset=asset), self.assertRaises(ValueError):
                self.upload(asset=asset)

    def test_release_published_during_retry_blocks_upload(self):
        with self.assertRaises(ValueError):
            self.upload(draft=False)


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
        self.assertEqual(self.args.tag, m.expected_tag(self.contract))

    def test_wrapped_base64_and_absent_alias_preserve_existing_signer(self):
        self.env['SIGNING_ALIAS'] = ''
        encoded = self.env['SIGNING_KEYSTORE_B64']
        self.env['SIGNING_KEYSTORE_B64'] = '\n'.join(encoded[i:i + 76] for i in range(0, len(encoded), 76)) + '\n'
        self.sign()
        receipt = d.load(Path(self.args.directory) / d.RECEIPT)
        self.assertEqual(receipt['certificateSha256'], self.fixture.certificates[0])

    def test_wrong_keys_fail_certificate_verification(self):
        for index, pin in enumerate((self.fixture.certificates[1], '00' * 32)):
            self.args.directory = str(self.root / f'bad-key-{index}')
            with self.subTest(pin=pin), patch.dict(os.environ, dict(self.env, CERTIFICATE_SHA256=pin)), \
                    patch.object(m, 'increasing'), self.assertRaises(ValueError):
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

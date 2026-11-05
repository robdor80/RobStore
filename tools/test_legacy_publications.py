"""Approved legacy transition: read-only GitHub fixtures and real APK verification."""
import copy
import json
import os
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch
import android_owner_setup as owner
import distribution as d
import mobile_distribution as m

REPO = 'robdor80/Cuadrante_App'
B = m.legacy_baselines(REPO)[0]
EXPECTED = dict(schemaVersion=1, channel='release', **{k: B[k] for k in
                ('packageName', 'versionCode', 'versionName', 'apkAssetName', 'sha256')})


class LegacyTests(unittest.TestCase):
    def setUp(self):
        self.b = copy.deepcopy(B)
        self.release = dict(tag_name=B['tag'], id=B['releaseId'], draft=False, prerelease=False,
            published_at='2026-11-04T01:47:08Z', url=f"https://api.github.com/repos/{REPO}/releases/{B['releaseId']}",
            assets=[dict(name=B['apkAssetName'], id=B['assetId'], state='uploaded', size=4,
                         digest='sha256:' + B['sha256'])])
        self.catalog = dict(packageName=B['packageName'], apkAssetName=B['apkAssetName'])
        self.reference = dict(object=dict(type='commit', sha=B['tagCommit'],
            url=f"https://api.github.com/repos/{REPO}/git/commits/{B['tagCommit']}"))

    def verify(self, actual=None, real=False, digest=None):
        def api(path):
            return self.reference if '/git/ref/' in path else []
        real_run = subprocess.run
        def download(args, **kwargs):
            if args[:2] == ['gh', 'api']:
                if real:
                    kwargs['stdout'].write(Path(os.environ['HISTORICAL_APK']).read_bytes())
                else:
                    kwargs['stdout'].write(b'test')
                return subprocess.CompletedProcess(args, 0, stderr=b'')
            return real_run(args, **kwargs)
        with patch.object(m, 'api', side_effect=api), patch.object(m, 'raw_file', return_value=json.dumps(self.catalog)), \
                patch.object(subprocess, 'run', side_effect=download):
            if real:
                return m.verified_legacy(REPO, self.release, self.b)
            with patch.object(d, 'digest', return_value=digest or B['sha256']), \
                    patch.object(m, 'inspect', return_value=actual or EXPECTED):
                return m.verified_legacy(REPO, self.release, self.b)

    def test_approved_cuadrante_legacy(self):
        self.assertEqual(self.verify(), EXPECTED)

    def test_wrong_or_missing_github_digest(self):
        for digest in ('sha256:' + '00'*32, None):
            self.release['assets'][0]['digest'] = digest
            with self.subTest(digest=digest), self.assertRaises(ValueError):
                self.verify()

    def test_downloaded_digest_mismatch(self):
        with self.assertRaisesRegex(ValueError, 'Downloaded historical APK'):
            self.verify(digest='00'*32)

    def test_wrong_tag(self):
        self.release['tag_name'] = 'v1.0.1'
        with self.assertRaises(ValueError): self.verify()

    def test_wrong_repository(self):
        self.b['repository'] = 'robdor80/Other'
        with self.assertRaises(ValueError): self.verify()

    def test_wrong_asset(self):
        for change in (dict(name='other.apk'), dict(id=1), dict(state='new')):
            saved = self.release['assets'][0].copy()
            self.release['assets'][0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError): self.verify()
            self.release['assets'][0] = saved

    def test_wrong_package_or_versions(self):
        for key, value in (('packageName', 'es.other'), ('versionCode', 2), ('versionName', '1.1.0')):
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify(dict(EXPECTED, **{key:value}))
            self.catalog[key] = value
            with self.subTest(catalog=key), self.assertRaises(ValueError): self.verify()
            self.catalog = dict(packageName=B['packageName'], apkAssetName=B['apkAssetName'])

    def test_moved_tag(self):
        self.reference['object']['sha'] = 'a'*40
        with self.assertRaises(ValueError): self.verify()

    def test_unpublished_or_wrong_release(self):
        for key, value in (('draft', True), ('prerelease', True), ('published_at', None), ('id', 1), ('url', 'https://example.org')):
            old = self.release[key]; self.release[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify()
            self.release[key] = old

    def test_api_error_never_falls_back(self):
        with patch.object(m, 'api', side_effect=ValueError('network')), self.assertRaises(ValueError): self.verify_api()

    def verify_api(self):
        return m.verified_legacy(REPO, self.release, self.b)

    def test_present_contract_is_never_bypassed(self):
        with patch.object(m, 'api', side_effect=[self.reference, [dict(name=d.CONTRACT)]]), \
                patch.object(m, 'raw_file', return_value='malformed'), self.assertRaises(ValueError):
            self.verify_api()

    def test_missing_contract_without_baseline_is_error(self):
        self.release['tag_name'] = 'v0.9.0'
        with patch.object(m, 'pages', return_value=[self.release]), \
                patch.object(m, 'raw_file', side_effect=ValueError('404')), self.assertRaises(ValueError):
            m.increasing("robdor80/FixtureApp", dict(EXPECTED, versionCode=2))

    def test_modern_valid(self):
        self.release['tag_name'] = 'v1.1.0'
        with patch.object(m, 'pages', return_value=[self.release]), patch.object(m, 'raw_file', return_value=json.dumps(EXPECTED)), \
                patch.object(m, 'verified_legacy') as legacy:
            m.increasing("robdor80/FixtureApp", dict(EXPECTED, versionCode=2))
            legacy.assert_not_called()

    def test_legacy_growth_equal_lower_rejected_higher_valid(self):
        with patch.object(m, 'pages', return_value=[self.release]), patch.object(m, 'verified_legacy', return_value=EXPECTED):
            for code in (0, 1):
                with self.subTest(code=code), self.assertRaisesRegex(ValueError, 'strictly increase'):
                    m.increasing(REPO, dict(EXPECTED, versionCode=code))
            m.increasing(REPO, dict(EXPECTED, versionCode=2, versionName='1.1.0'))

    def test_missing_or_unstable_approved_release_fails_closed(self):
        for releases in ([], [dict(self.release, draft=True)], [dict(self.release, prerelease=True)]):
            with self.subTest(releases=releases), patch.object(m, 'pages', return_value=releases), self.assertRaises(ValueError):
                m.increasing(REPO, dict(EXPECTED, versionCode=2))

    def test_historical_signing_pin(self):
        m.historical_certificate(REPO, 'release', B['certificateSha256'])
        m.historical_certificate(REPO, 'dev', 'ab'*32)
        with self.assertRaises(ValueError): m.historical_certificate(REPO, 'release', 'ab'*32)

    def test_apksigner_sdk_and_termux_certificate_formats(self):
        pin = B['certificateSha256']
        samples = (
            f'Signer #1 certificate SHA-256 digest: {pin}',
            f'Number of signers: 1\nV3.0 Signer: certificate SHA-256 digest: {pin}',
        )
        for signature in samples:
            with self.subTest(signature=signature):
                m.verify_signer_output(signature, pin)

    def test_apksigner_wrong_or_multiple_signers_still_rejected(self):
        pin = B['certificateSha256']
        with self.assertRaises(ValueError):
            m.verify_signer_output(f'V3.0 Signer: certificate SHA-256 digest: {"00"*32}', pin)
        with self.assertRaises(ValueError):
            m.verify_signer_output(
                f'Signer #1 certificate SHA-256 digest: {pin}\n'
                f'V3.0 Signer: certificate SHA-256 digest: {pin}',
                pin,
            )

    def test_import_mismatch_stops_before_import_or_generation(self):
        with __import__('tempfile').TemporaryDirectory() as folder:
            source = Path(folder)/'old.jks'; source.write_bytes(b'fixture')
            with patch('builtins.input', return_value='old-alias'), patch.object(owner.getpass, 'getpass', return_value='fixture-only'), \
                    patch.object(owner, 'command', return_value=b'another public certificate') as command, self.assertRaises(ValueError):
                owner.import_release(source, Path(folder)/'release.p12', 'fixture-only', B['certificateSha256'])
            self.assertEqual(len(command.call_args_list), 1)
            self.assertIn('-exportcert', command.call_args.args[0])

    def test_import_preserves_identity_and_passwords_not_argv(self):
        with __import__('tempfile').TemporaryDirectory() as folder:
            source = Path(folder)/'old.jks'; source.write_bytes(b'fixture')
            cert = b'public cert fixture'
            with patch('builtins.input', return_value='old-alias'), patch.object(owner.getpass, 'getpass', return_value='fixture-password'), \
                    patch.object(owner, 'command', return_value=cert) as command:
                owner.import_release(source, Path(folder)/'release.p12', 'new-fixture-password', __import__('hashlib').sha256(cert).hexdigest())
            self.assertEqual(len(command.call_args_list), 2)
            self.assertIn('-importkeystore', command.call_args.args[0])
            for call in command.call_args_list:
                self.assertNotIn('fixture-password', call.args[0]); self.assertNotIn('new-fixture-password', call.args[0])
                self.assertNotIn('-genkeypair', call.args[0])
            self.assertEqual(source.read_bytes(), b'fixture')

    def test_existing_identity_conflict_blocks_secret_replacement(self):
        public = dict(certificates=dict(release='ab'*32, dev='cd'*32))
        with patch.object(owner, 'gh_api', return_value=dict(variables=[dict(name='CERTIFICATE_SHA256', value='00'*32)])), \
                self.assertRaises(ValueError): owner.check_existing_identities(REPO, public)

    def test_historical_repository_forbids_release_generation(self):
        def api(path):
            if path == 'user': return dict(login='robdor80')
            if '/compare/' in path: return dict(status='ahead')
            return dict(fork=False, default_branch='main', private=False)
        with patch.dict(os.environ, dict(PREFIX='/data/data/com.termux/files/usr'), clear=True), \
                patch('sys.argv', ['setup', '--repository', REPO, '--private-directory', '/tmp/unused', '--tooling-sha', 'a'*40]), \
                patch.object(owner, 'gh_api', side_effect=api), patch.object(owner, 'command') as command, \
                self.assertRaisesRegex(ValueError, 'generation is forbidden'):
            owner.main()
        command.assert_not_called()

    def test_real_official_apk_when_supplied(self):
        # CI uses deterministic fixtures; cloud audit additionally supplies the downloaded official APK.
        if 'HISTORICAL_APK' not in os.environ:
            self.verify()
            return
        self.release['assets'][0]['size'] = Path(os.environ['HISTORICAL_APK']).stat().st_size
        self.assertEqual(self.verify(real=True), EXPECTED)


if __name__ == '__main__': unittest.main()

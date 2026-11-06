"""Original Android signing identity and first automatic transition to RS-05."""
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

class LegacyTests(unittest.TestCase):
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
                    patch.object(owner, 'command', side_effect=[b'old-alias, date, PrivateKeyEntry', b'another public certificate']) as command, self.assertRaises(ValueError):
                owner.import_key(source, Path(folder)/'release.p12', 'fixture-only', 'android-release', B['certificateSha256'])
            self.assertEqual(len(command.call_args_list), 2)
            self.assertIn('-exportcert', command.call_args.args[0])

    def test_import_preserves_identity_and_passwords_not_argv(self):
        with __import__('tempfile').TemporaryDirectory() as folder:
            source = Path(folder)/'old.jks'; source.write_bytes(b'fixture')
            cert = b'public cert fixture'
            with patch('builtins.input', return_value='old-alias'), patch.object(owner.getpass, 'getpass', return_value='fixture-password'), \
                    patch.object(owner, 'command', side_effect=[b'old-alias, date, PrivateKeyEntry', cert, b'']) as command:
                owner.import_key(source, Path(folder)/'release.p12', 'new-fixture-password', 'android-release', __import__('hashlib').sha256(cert).hexdigest())
            self.assertEqual(len(command.call_args_list), 3)
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
            return dict(environments=[])
        with __import__('tempfile').TemporaryDirectory() as directory, \
                patch.dict(os.environ, {}, clear=True), \
                patch('sys.argv', ['setup', '--repository', REPO, '--private-directory', directory]), \
                patch.object(owner, 'configured', return_value=False), \
                patch.object(owner, 'gh_api', side_effect=api), patch.object(owner, 'command') as command, \
                self.assertRaisesRegex(ValueError, 'generation is forbidden'):
            owner.main()
        command.assert_not_called()


    def test_legacy_version_uses_known_original_without_rechecking_old_approvals(self):
        contract = dict(channel='release', packageName=B['packageName'], versionCode=2)
        with patch.object(m, 'pages', return_value=[dict(tag_name=B['tag'], draft=False, prerelease=False)]), patch.object(m, 'raw_file', return_value=None):
            m.increasing(REPO, contract)
            with self.assertRaises(ValueError):
                m.increasing(REPO, dict(contract, versionCode=1))

    def test_other_app_adopts_its_old_apk_without_manual_hash_or_registry(self):
        contract = dict(channel='release', packageName='es.fixture.app', versionCode=2)
        release = dict(tag_name='old', draft=False, prerelease=False, assets=[dict(id=99, name='old.apk', size=4)])
        def download(args, **kwargs):
            kwargs['stdout'].write(b'test')
            return subprocess.CompletedProcess(args, 0)
        def tool(*args):
            if 'aapt' in str(args[0]): return "package: name='es.fixture.app' versionCode='1'"
            return 'Signer #1 certificate SHA-256 digest: ' + 'ab' * 32
        with patch.dict(os.environ, CERTIFICATE_SHA256='ab' * 32), patch.object(m, 'pages', return_value=[release]), patch.object(m, 'raw_file', return_value=None), patch.object(subprocess, 'run', side_effect=download), patch.object(d, 'run', side_effect=tool), patch.object(d, 'tools', return_value=Path('/sdk')):
            m.increasing('robdor80/FixtureApp', contract)

if __name__ == '__main__': unittest.main()

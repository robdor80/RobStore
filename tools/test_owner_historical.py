"""Personal Termux verification boundary; no owner CLI, real keys or GitHub writes."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import android_owner_setup as owner
import mobile_distribution as m

REPO = 'robdor80/Cuadrante_App'
B = m.legacy_baselines(REPO)[0]


class OwnerHistoricalTests(unittest.TestCase):
    def verify(self, signature):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            jar = root / 'share/java/apksigner.jar'
            jar.parent.mkdir(parents=True); jar.write_bytes(b'public tool path fixture')
            with patch.dict(os.environ, PREFIX=str(root)), patch.object(m, 'api', return_value={}), \
                    patch.object(m, 'legacy_metadata', return_value=None), \
                    patch.object(m, 'download_legacy_asset', return_value=root / 'official.apk') as download, \
                    patch.object(owner, 'command', return_value=signature.encode()) as command:
                pin = owner.verify_historical_release(REPO, B['tag'])
                download.assert_called_once()
                args = command.call_args.args[0]
                self.assertEqual(args[:3], ['java', '-jar', jar])
                self.assertEqual(args[3:6], ['verify', '--verbose', '--print-certs'])
                return pin

    def test_java_termux_verifier_needs_no_native_android_sdk_tools(self):
        self.assertEqual(self.verify('Signer #1 certificate SHA-256 digest: ' + B['certificateSha256'] +
                         '\nVerified using v2 scheme (APK Signature Scheme v2): true\n'), B['certificateSha256'])

    def test_wrong_multiple_or_unsigned_certificate_rejected(self):
        valid = 'Signer #1 certificate SHA-256 digest: ' + B['certificateSha256'] + '\n'
        scheme = 'Verified using v2 scheme (APK Signature Scheme v2): true\n'
        for signature in (valid, valid.replace(B['certificateSha256'], '00'*32) + scheme,
                          valid + valid.replace('#1', '#2') + scheme):
            with self.subTest(signature=signature), self.assertRaises(ValueError): self.verify(signature)

    def test_missing_java_verifier_stops_before_download(self):
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, PREFIX=folder), \
                patch.object(m, 'api', return_value={}), patch.object(m, 'legacy_metadata', return_value=None), \
                patch.object(m, 'download_legacy_asset') as download, patch.object(owner, 'command') as command, \
                self.assertRaisesRegex(ValueError, 'Termux apksigner'):
            owner.verify_historical_release(REPO, B['tag'])
        download.assert_not_called(); command.assert_not_called()

    def test_metadata_error_never_reaches_signer(self):
        with patch.object(m, 'api', side_effect=ValueError('GitHub denied')), patch.object(owner, 'command') as command, \
                self.assertRaises(ValueError): owner.verify_historical_release(REPO, B['tag'])
        command.assert_not_called()

    def test_modern_contract_cannot_substitute_historical_apk_evidence(self):
        with patch.object(m, 'api', return_value={}), patch.object(m, 'legacy_metadata', return_value={}), \
                patch.object(owner, 'command') as command, self.assertRaises(ValueError):
            owner.verify_historical_release(REPO, B['tag'])
        command.assert_not_called()

    def test_trusted_certificate_without_private_entry_rejected(self):
        with patch.object(owner, 'command', return_value=b'Entry type: trustedCertEntry'), self.assertRaises(ValueError):
            owner.require_private_entry(Path('unused'), 'release', 'fixture-secret')

    def test_private_entry_password_never_in_argv(self):
        with patch.object(owner, 'command', return_value=b'Entry type: PrivateKeyEntry') as command:
            owner.require_private_entry(Path('unused'), 'release', 'fixture-secret')
        self.assertNotIn('fixture-secret', command.call_args.args[0])
        self.assertEqual(command.call_args.kwargs['environment']['ANDROID_OWNER_KEY_PASSWORD'], 'fixture-secret')


if __name__ == '__main__': unittest.main()

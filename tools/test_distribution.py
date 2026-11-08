"""Safe fixtures: no production identities or private material is committed."""
import copy
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import distribution as d

CERT = 'ab' * 32
OTHER = 'cd' * 32


class DistributionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.apk = Path(self.temp.name) / 'RobStore-test.apk'
        self.apk.write_bytes(b'safe simulated APK fixture')

    def tool(self, *args):
        if 'apksigner' in str(args[0]):
            return f'Signer #1 certificate SHA-256 digest: {CERT}\nVerified using v2 scheme (APK Signature Scheme v2): true'
        if 'badging' in args:
            return "package: name='es.robertodorado.robstore' versionCode='2' versionName='0.2.0'\nsdkVersion:'26'\ntargetSdkVersion:'36'\nuses-permission: name='android.permission.REQUEST_INSTALL_PACKAGES'"
        if 'resources' in args:
            return 'resource 0x1234 package:xml/apk_paths:\n (string8) "res/xml/apk_paths.xml"'
        if args[-1] == 'res/xml/apk_paths.xml':
            return 'E: paths\n E: files-path\n path="apk-downloads/verified/"'
        if 'xmltree' in args:
            return 'E: provider\n androidx.core.content.FileProvider\n android:exported 0x0\n android:authorities="es.robertodorado.robstore.apkfiles"'
        return ''

    def inspect(self, channel='release', certificate=CERT):
        with patch.object(d, 'run', side_effect=self.tool), patch.object(d, 'tools', return_value=Path('/safe')):
            return d.inspect_apk(self.apk, channel, certificate)

    def test_final_metadata_and_hash(self):
        actual = self.inspect()
        self.assertEqual(actual['sha256'], hashlib.sha256(b'safe simulated APK fixture').hexdigest())
        self.assertEqual(actual['versionCode'], 2)
        self.assertEqual(actual['apkAssetName'], self.apk.name)
        self.assertEqual(actual['channel'], 'release')

    def test_wrong_certificate(self):
        with self.assertRaisesRegex(ValueError, 'signer'):
            self.inspect(certificate=OTHER)

    def test_cross_package(self):
        with self.assertRaisesRegex(ValueError, 'package'):
            self.inspect(channel='dev')

    def test_incoherent_contract_fields(self):
        actual = self.inspect()
        for field in actual:
            with self.subTest(field=field), self.assertRaises(ValueError):
                wrong = copy.deepcopy(actual)
                wrong[field] = 'incoherent'
                d.validate_contract(wrong, actual)

    def test_modified_apk(self):
        actual = self.inspect()
        self.apk.write_bytes(self.apk.read_bytes() + b'altered')
        with self.assertRaisesRegex(ValueError, 'Contract'):
            d.validate_contract(actual, self.inspect())

    def test_monotonic_versions(self):
        actual = self.inspect()
        for code in (2, 3):
            with self.subTest(code=code), self.assertRaises(ValueError):
                d.increasing(actual, dict(actual, versionCode=code))
        d.increasing(actual, dict(actual, versionCode=1))

    def test_previous_other_identity(self):
        with self.assertRaises(ValueError):
            d.increasing(self.inspect(), dict(self.inspect(), channel='dev'))

    def test_signature_failure_redacts_tool_output(self):
        with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, 'private sentinel', '')):
            with self.assertRaises(ValueError) as raised:
                d.run('signing-tool')
        self.assertNotIn('private sentinel', str(raised.exception))

    def test_gradle_failures_are_classified_without_echoing_private_output(self):
        cases = [('Cannot find a Java installation matching languageVersion=21', 'Java 21'),
                 ('UnknownHostException', 'red'), ('There were failing tests', 'pruebas'),
                 ('Lint found errors', 'Lint'), ('Compilation error', 'código'),
                 ('Verification of Gradle distribution failed', 'checksum'), ('unclassified', 'CI Android')]
        for output, expected in cases:
            with self.subTest(output=output), patch.object(subprocess, 'run', return_value=
                    subprocess.CompletedProcess([], 1, output, 'SECRET https://private.example /private/path')):
                with self.assertRaises(ValueError) as raised:
                    d.run('./gradlew', 'testReleaseUnitTest')
                self.assertIn(expected, str(raised.exception))
                self.assertNotIn('SECRET', str(raised.exception))
                self.assertNotIn('private.example', str(raised.exception))
                self.assertNotIn('/private/path', str(raised.exception))

    def test_apksigner_errors_identify_single_setup_correction_without_disclosing_output(self):
        cases = [('keystore password was incorrect', 'SIGNING_STORE_PASSWORD'),
                 ('java.security.UnrecoverableKeyException: Cannot recover key', 'SIGNING_KEY_PASSWORD'),
                 ('does not contain a key', 'SIGNING_ALIAS'),
                 ('Invalid keystore format', 'SIGNING_KEYSTORE_B64')]
        for error, field in cases:
            with self.subTest(field=field), patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, '', error + ' private sentinel')):
                with self.assertRaises(ValueError) as raised:
                    d.run('/sdk/apksigner', 'sign')
                self.assertIn(field, str(raised.exception))
                self.assertNotIn('private sentinel', str(raised.exception))

    def test_unsigned_apk(self):
        with patch.object(d, 'run', return_value=''), patch.object(d, 'tools', return_value=Path('/safe')):
            with self.assertRaises(ValueError):
                d.inspect_apk(self.apk, 'release', CERT)

    def test_distribution_security_properties(self):
        mutations = [
            lambda text: text.replace('verified/', ''),
            lambda text: text.replace('android:exported 0x0', 'android:exported 0xffffffff'),
            lambda text: text.replace("sdkVersion:'26'", "sdkVersion:'25'"),
            lambda text: text.replace("targetSdkVersion:'36'", "targetSdkVersion:'35'"),
            lambda text: text.replace('v2): true', 'v2): false'),
            lambda text: text + '\napplication-debuggable',
        ]
        for mutate in mutations:
            with self.subTest(mutation=mutate), patch.object(d, 'tools', return_value=Path('/safe')), \
                    patch.object(d, 'run', side_effect=lambda *args: mutate(self.tool(*args))), \
                    self.assertRaises(ValueError):
                d.inspect_apk(self.apk, 'release', CERT)


if __name__ == '__main__':
    unittest.main()

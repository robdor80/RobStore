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

    def test_provenance_changes_blocked(self):
        with patch.object(d, 'git'), patch.object(d, 'source_tree', side_effect=['source', 'changed']):
            with self.assertRaisesRegex(ValueError, 'changed'):
                d.provenance({'sourceCommit': 'a' * 40}, 'HEAD')

    def test_provenance_contract_only_allowed(self):
        with patch.object(d, 'git'), patch.object(d, 'source_tree', return_value='same'):
            d.provenance({'sourceCommit': 'a' * 40}, 'HEAD')

    def preflight(self, published, first=False, tag_exists=False):
        actual = self.inspect()
        receipt = dict(schemaVersion=1, sourceCommit='a' * 40, channel='release',
                       certificateSha256=CERT, apkSha256=actual['sha256'])
        def git(*args):
            if args[:2] == ('remote', 'get-url'):
                return 'https://github.com/robdor80/RobStore.git'
            if args[0] == 'rev-parse':
                return 'b' * 40
            if args[0] == 'ls-remote' and tag_exists:
                return 'existing public tag'
            return ''
        with patch.object(d, 'clean'), patch.object(d, 'load', side_effect=[actual, receipt]), \
                patch.object(d, 'inspect_apk', return_value=actual), patch.object(d, 'provenance'), \
                patch.object(d, 'git', side_effect=git), \
                patch.object(d, 'run', return_value=json.dumps({'sha': 'b' * 40})), \
                patch.object(d, 'published_contracts', return_value=published), patch('builtins.print'):
            d.preflight(argparse.Namespace(apk=str(self.apk), channel='release', certificate=CERT,
                                           tag='v0.2.0', first_publication=first))

    def test_preflight_previous_online_code(self):
        self.preflight([dict(self.inspect(), versionCode=1)])
        for code in (2, 3):
            with self.subTest(code=code), self.assertRaises(ValueError):
                self.preflight([dict(self.inspect(), versionCode=code)], first=True)

    def test_preflight_first_is_explicit(self):
        with self.assertRaises(ValueError):
            self.preflight([])
        self.preflight([], first=True)

    def test_preflight_never_moves_tag(self):
        with self.assertRaisesRegex(ValueError, 'tag already exists'):
            self.preflight([], first=True, tag_exists=True)

    def test_remote_contract_uses_tag_and_channel(self):
        actual = self.inspect()
        release = dict(draft=False, prerelease=False, tag_name='v0.2.0',
                       assets=[dict(name=actual['apkAssetName'], state='uploaded', digest='sha256:' + actual['sha256'])])
        with patch.object(d, 'run', side_effect=[json.dumps([release]) + '\n[]', json.dumps(actual)]) as run:
            self.assertEqual(d.published_contracts('release'), [actual])
            self.assertIn('ref=refs%2Ftags%2Fv0.2.0', run.call_args.args[-1])

    def test_remote_dev_excludes_stable(self):
        release = dict(draft=False, prerelease=False, tag_name='v0.2.0')
        with patch.object(d, 'run', return_value=json.dumps([release])) as run:
            self.assertEqual(d.published_contracts('dev'), [])
            self.assertEqual(run.call_count, 1)


if __name__ == '__main__':
    unittest.main()

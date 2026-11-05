#!/usr/bin/env python3
"""Integration against an already built unsigned Release; ephemeral test keys only.
Run manually after assembly. Private files live in a TemporaryDirectory outside Git.
"""
import hashlib
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import distribution as d


class SignedApkTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='robstore-test-only-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        cls.apk = cls.directory / 'RobStore-fixture.apk'
        cls.source = Path('app/build/outputs/apk/release/app-release-unsigned.apk')
        if not cls.source.is_file():
            raise RuntimeError('Build unsigned Release before running signed integration fixtures')
        cls.env = dict(os.environ, ROBSTORE_TEST_PASSWORD=secrets.token_urlsafe(32))
        cls.certificates = []
        for index in (1, 2):
            store = cls.directory / f'fixture-{index}.p12'
            cls.execute('keytool', '-genkeypair', '-keystore', store, '-alias', 'fixture',
                        '-storetype', 'PKCS12', '-storepass:env', 'ROBSTORE_TEST_PASSWORD',
                        '-keyalg', 'RSA', '-keysize', '3072', '-validity', '2', '-dname', 'CN=TEST ONLY EPHEMERAL')
            cert = cls.directory / f'public-{index}.cer'
            cls.execute('keytool', '-exportcert', '-keystore', store, '-alias', 'fixture',
                        '-storepass:env', 'ROBSTORE_TEST_PASSWORD', '-file', cert)
            cls.certificates.append(hashlib.sha256(cert.read_bytes()).hexdigest())
        shutil.copyfile(cls.source, cls.apk)
        cls.execute(d.tools() / 'apksigner', 'sign', '--ks', cls.directory / 'fixture-1.p12',
                    '--ks-key-alias', 'fixture', '--ks-pass', 'env:ROBSTORE_TEST_PASSWORD',
                    '--key-pass', 'env:ROBSTORE_TEST_PASSWORD', '--v1-signing-enabled', 'false', cls.apk)

    @classmethod
    def execute(cls, *args):
        result = subprocess.run(list(map(str, args)), env=cls.env, capture_output=True)
        if cls.env['ROBSTORE_TEST_PASSWORD'].encode() in result.stdout + result.stderr:
            raise RuntimeError('Fixture password leaked into tool output')
        if result.returncode:
            raise RuntimeError('Ephemeral fixture tool failed; sensitive output suppressed')

    def test_real_certificate_and_contract(self):
        actual = d.inspect_apk(self.apk, 'release', self.certificates[0])
        d.validate_contract(actual, d.inspect_apk(self.apk, 'release', self.certificates[0]))
        self.assertEqual(actual['sha256'], d.digest(self.apk))
        self.assertEqual(actual['versionCode'], 1)

    def test_real_wrong_certificate(self):
        with self.assertRaisesRegex(ValueError, 'signer'):
            d.inspect_apk(self.apk, 'release', self.certificates[1])

    def test_real_cross_variant(self):
        with self.assertRaisesRegex(ValueError, 'package'):
            d.inspect_apk(self.apk, 'dev', self.certificates[0])

    def test_real_tamper(self):
        changed = self.directory / 'RobStore-tampered.apk'
        body = bytearray(self.apk.read_bytes())
        body[100] ^= 1
        changed.write_bytes(body)
        with self.assertRaises(ValueError):
            d.inspect_apk(changed, 'release', self.certificates[0])

    def test_real_unsigned_rejected(self):
        with self.assertRaises(ValueError):
            d.inspect_apk(self.source, 'release', self.certificates[0])

    def test_real_prepare_and_provenance(self):
        # Isolated Git fixture: never creates a fake publication contract in RobStore.
        directory = self.directory / 'isolated-git'
        directory.mkdir()
        candidate = self.directory / 'candidate'
        candidate.mkdir()
        shutil.copyfile(self.apk, candidate / self.apk.name)
        contract = d.inspect_apk(candidate / self.apk.name, 'release', self.certificates[0])
        d.write(candidate / d.CONTRACT, contract)
        original = Path.cwd()
        try:
            os.chdir(directory)
            d.git('init', '-q')
            d.git('config', 'user.email', 'fixture@example.invalid')
            d.git('config', 'user.name', 'Fixture')
            Path('source.txt').write_text('unchanged code')
            d.git('add', '.')
            d.git('commit', '-qm', 'fixture source')
            source = d.git('rev-parse', 'HEAD')
            receipt = dict(schemaVersion=1, sourceCommit=source, channel='release',
                           certificateSha256=self.certificates[0], apkSha256=contract['sha256'])
            d.write(candidate / d.RECEIPT, receipt)
            import argparse
            d.prepare(argparse.Namespace(candidate=str(candidate), channel='release',
                                        certificate=self.certificates[0], previous=None, first_publication=True))
            d.git('add', d.CONTRACT, d.RECEIPT)
            d.git('commit', '-qm', 'fixture contract only')
            d.provenance(receipt, 'HEAD')
            Path('source.txt').write_text('changed code')
            d.git('add', 'source.txt')
            d.git('commit', '-qm', 'fixture changed code')
            with self.assertRaises(ValueError):
                d.provenance(receipt, 'HEAD')
        finally:
            os.chdir(original)


if __name__ == '__main__':
    unittest.main()

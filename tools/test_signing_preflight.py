"""Real JKS/PKCS12 compatibility tests; temporary fixture keys, never production secrets."""
import base64
import hashlib
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import mobile_distribution as m


class SigningPreflightTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='signing-test-only-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.env = dict(os.environ, SIGNING_STORE_PASSWORD=secrets.token_urlsafe(24),
                       SIGNING_KEY_PASSWORD=secrets.token_urlsafe(24), SIGNING_ALIAS='original-alias')
        cls.fixtures = {}
        for kind in ('JKS', 'PKCS12'):
            env = dict(cls.env)
            if kind == 'PKCS12':
                env['SIGNING_KEY_PASSWORD'] = env['SIGNING_STORE_PASSWORD']
            store = cls.root / (kind + '.p12')  # JKS deliberately uses a .p12 filename.
            cls.run_tool(env, 'keytool', '-genkeypair', '-storetype', kind, '-keystore', store,
                         '-alias', env['SIGNING_ALIAS'], '-storepass:env', 'SIGNING_STORE_PASSWORD',
                         '-keypass:env', 'SIGNING_KEY_PASSWORD', '-keyalg', 'RSA', '-validity', '2',
                         '-dname', 'CN=TEST ONLY EPHEMERAL')
            cert = cls.run_tool(env, 'keytool', '-exportcert', '-keystore', store, '-alias', env['SIGNING_ALIAS'],
                                '-storepass:env', 'SIGNING_STORE_PASSWORD')
            env['CERTIFICATE_SHA256'] = hashlib.sha256(cert).hexdigest()
            cls.fixtures[kind] = (store, env)

    @staticmethod
    def run_tool(env, *args):
        result = subprocess.run(list(map(str, args)), env=env, capture_output=True)
        if result.returncode:
            raise RuntimeError('Ephemeral fixture command failed; private output suppressed')
        return result.stdout

    def check(self, kind='PKCS12', changes=None, error=None):
        store, env = self.fixtures[kind]
        with patch.dict(os.environ, dict(env, **(changes or {}))), patch('builtins.print'):
            if error:
                with self.assertRaisesRegex(ValueError, error) as raised:
                    m.signing_preflight(store)
                for value in (env['SIGNING_STORE_PASSWORD'], env['SIGNING_KEY_PASSWORD'], env['SIGNING_ALIAS'], str(store)):
                    self.assertNotIn(value, str(raised.exception))
            else:
                m.signing_preflight(store)
                self.assertEqual(os.environ['SIGNING_ALIAS'], env['SIGNING_ALIAS'])

    def test_jks_with_distinct_key_password_and_p12_filename(self):
        self.check('JKS')

    def test_pkcs12(self):
        self.check()

    def test_absent_alias_resolves_historical_certificate_instead_of_guessing(self):
        self.check(changes=dict(SIGNING_ALIAS=''))

    def test_absent_pkcs12_key_password_uses_store_password(self):
        self.check(changes=dict(SIGNING_KEY_PASSWORD=''))

    def test_wrong_store_password(self):
        self.check(changes=dict(SIGNING_STORE_PASSWORD='wrong-test-only'), error='SIGNING_STORE_PASSWORD')

    def test_wrong_key_password(self):
        self.check('JKS', dict(SIGNING_KEY_PASSWORD='wrong-test-only'), 'SIGNING_KEY_PASSWORD')

    def test_wrong_alias(self):
        self.check(changes=dict(SIGNING_ALIAS='missing'), error='SIGNING_ALIAS')

    def test_changed_certificate_is_rejected(self):
        self.check(changes=dict(CERTIFICATE_SHA256='00' * 32), error='certificado histórico')


if __name__ == '__main__':
    unittest.main()

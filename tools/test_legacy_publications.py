"""Controlled legacy transition. All GitHub I/O is mocked; no publication or real keys."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import mobile_distribution as m
import distribution as d
import android_owner_setup as owner

REPO = 'robdor80/Cuadrante_App'


class LegacyPublicationTests(unittest.TestCase):
    def setUp(self):
        self.b = m.legacy_baselines(REPO)[0]
        b = self.b
        self.asset = dict(id=b['assetId'], name=b['apkAssetName'], state='uploaded', size=b['assetSize'], digest='sha256:' + b['sha256'])
        self.release = dict(id=b['releaseId'], tag_name=b['tag'], draft=False, prerelease=False, published_at='2026-11-04T01:47:08Z',
                            html_url=f'https://github.com/{REPO}/releases/tag/{b["tag"]}', assets=[self.asset])
        self.manifest = dict(schemaVersion=1, packageName=b['packageName'], apkAssetName=b['apkAssetName'])
        self.ref = dict(ref='refs/tags/' + b['tag'], object=dict(sha=b['tagCommit'], type='commit',
                        url=f'https://api.github.com/repos/{REPO}/git/commits/{b["tagCommit"]}'))
        self.actual = dict(schemaVersion=1, channel='release', **{k:b[k] for k in ('packageName','versionCode','versionName','apkAssetName','sha256')})

    def api(self, path):
        if '/releases/tags/' in path:
            return self.release
        if '/git/ref/' in path:
            return self.ref
        if '/contents/' in path:
            return [dict(name='robstore.json')]
        raise AssertionError(path)

    def verify(self):
        with patch.object(m, 'api', side_effect=self.api), patch.object(m, 'raw_file', return_value=json.dumps(self.manifest)):
            return m.verified_legacy_release(self.b)

    def increasing(self, code=2):
        with patch.object(m, 'pages', return_value=[self.release]), patch.object(m, 'historical_apk', return_value=self.actual):
            m.increasing(REPO, dict(self.actual, versionCode=code, versionName='1.1.0'))

    def test_modern_valid_release_unchanged(self):
        repo = 'robdor80/ModernApp'
        with patch.object(m, 'pages', return_value=[dict(self.release, tag_name='v1.1.0')]), \
                patch.object(m, 'raw_file', return_value=json.dumps(self.actual)) as raw, patch.object(m, 'historical_apk') as legacy:
            m.increasing(repo, dict(self.actual, versionCode=2))
            raw.assert_called_once_with(repo, 'refs/tags/v1.1.0', d.CONTRACT)
            legacy.assert_not_called()

    def test_modern_release_after_baseline_still_limits_growth(self):
        modern = dict(self.actual, versionCode=3, versionName='1.2.0')
        releases = [self.release, dict(self.release, id=2, tag_name='v1.2.0')]
        with patch.object(m, 'pages', return_value=releases), patch.object(m, 'historical_apk', return_value=self.actual), \
                patch.object(m, 'raw_file', return_value=json.dumps(modern)):
            with self.assertRaises(ValueError): m.increasing(REPO, dict(self.actual, versionCode=2))
            m.increasing(REPO, dict(self.actual, versionCode=4))

    def test_cuadrante_legacy_approved(self):
        self.assertEqual(self.verify(), self.release)
        self.increasing()

    def test_legacy_wrong_sha_rejected(self):
        for digest in ('sha256:' + '00' * 32, None):
            with self.subTest(digest=digest):
                self.asset['digest'] = digest
                with self.assertRaises(ValueError): self.verify()

    def test_legacy_wrong_tag_rejected(self):
        self.release['tag_name'] = 'v1.0.1'
        with self.assertRaises(ValueError): self.verify()

    def test_legacy_wrong_asset_rejected(self):
        self.asset['name'] = 'other.apk'
        with self.assertRaises(ValueError): self.verify()

    def test_legacy_incoherent_package_or_version_rejected(self):
        for key, value in [('packageName','es.other.app'), ('versionCode',2), ('versionName','1.0.1')]:
            with self.subTest(key=key):
                original = dict(self.manifest)
                self.manifest[key] = value
                with self.assertRaises(ValueError): self.verify()
                self.manifest = original

    def test_missing_contract_without_baseline_rejected(self):
        for repo, tag in [(REPO,'v0.9.0'), ('robdor80/OtherApp','v1.0.0')]:
            with self.subTest(repo=repo), patch.object(m,'pages',return_value=[dict(self.release,tag_name=tag)]), \
                    patch.object(m,'raw_file',side_effect=ValueError('Not found')), patch.object(m,'historical_apk') as legacy:
                with self.assertRaises(ValueError): m.increasing(repo,dict(self.actual,versionCode=2))
                legacy.assert_not_called()

    def test_new_version_equal_or_below_one_rejected(self):
        for code in (0,1):
            with self.subTest(code=code), self.assertRaises(ValueError): self.increasing(code)

    def test_new_version_above_one_valid(self):
        self.increasing(2)

    def test_missing_or_nonstable_approved_release_rejected(self):
        for changes in (dict(draft=True),dict(prerelease=True),dict(published_at=None)):
            original = dict(self.release)
            self.release.update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError): self.verify()
            self.release = original
        with patch.object(m,'pages',return_value=[]), self.assertRaises(ValueError):
            m.increasing(REPO,dict(self.actual,versionCode=2))

    def test_moved_tag_or_replaced_release_or_asset_rejected(self):
        for target, key, value in [(self.ref['object'],'sha','f'*40), (self.release,'id',1), (self.asset,'id',1), (self.asset,'state','new')]:
            original = target[key];target[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify()
            target[key] = original

    def test_api_denied_and_retroactive_contract_rejected(self):
        with patch.object(m,'api',side_effect=ValueError('API denied')), self.assertRaises(ValueError): m.verified_legacy_release(self.b)
        with patch.object(m,'api',side_effect=[self.release,self.ref,[dict(name=d.CONTRACT)]]), self.assertRaises(ValueError):
            m.verified_legacy_release(self.b)

    def test_download_bytes_and_signed_metadata_are_mandatory(self):
        def download(args, stdout, **kwargs):
            stdout.write(b'public APK fixture')
            return type('Result',(),dict(returncode=0))()
        with tempfile.TemporaryDirectory() as directory, patch.object(m,'verified_legacy_release'), \
                patch.object(m.subprocess,'run',side_effect=download), patch.object(d,'digest',return_value=self.b['sha256']), \
                patch.object(m,'inspect',return_value=self.actual):
            b = dict(self.b,assetSize=len(b'public APK fixture'))
            self.assertEqual(m.historical_apk(b,directory),self.actual)
        for key, value in [('sha256','00'*32),('packageName','es.wrong.app'),('versionCode',2),('versionName','1.0.1')]:
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory, patch.object(m,'verified_legacy_release'), \
                    patch.object(m.subprocess,'run',side_effect=download), patch.object(d,'digest',return_value=self.b['sha256']), \
                    patch.object(m,'inspect',return_value=dict(self.actual,**{key:value})), self.assertRaises(ValueError):
                m.historical_apk(dict(self.b,assetSize=len(b'public APK fixture')),directory)
        with tempfile.TemporaryDirectory() as directory, patch.object(m,'verified_legacy_release'), \
                patch.object(m.subprocess,'run',side_effect=download), patch.object(d,'digest',return_value='00'*32), \
                patch.object(m,'inspect') as inspect, self.assertRaises(ValueError):
            m.historical_apk(dict(self.b,assetSize=len(b'public APK fixture')),directory)
        inspect.assert_not_called()

    def test_historical_release_rejects_wrong_signing_pin_before_tools(self):
        with patch.object(d,'tools') as tools, self.assertRaises(ValueError):
            m.inspect('unused.apk','release','00'*32,m.legacy_settings(self.b))
        tools.assert_not_called()

    def test_configuration_rejects_historical_package_change(self):
        settings=d.load('.github/android-distribution.json')
        settings.update(repository=REPO,robstorePolicy=False,packages=dict(release='es.wrong.app',dev='es.wrong.app.debug'))
        with self.assertRaises(ValueError): m.config(settings,REPO)


class OwnerImportTests(unittest.TestCase):
    def owner_main_fixture(self, import_release=False):
        # No subprocesses or real identity writes: simulate the personal CLI boundary.
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            destination = root / 'private'
            source = 'a' * 40
            def api(path, *args):
                if path == 'user': return dict(login='robdor80')
                if path.endswith('/compare/' + source + '...main'): return dict(status='ahead')
                if path.endswith('/environments'): return dict(environments=[])
                if path == 'repos/' + REPO: return dict(fork=False, default_branch='main', private=False)
                raise AssertionError(path)
            def command(args, **kwargs):
                if args == ['git','rev-parse','--show-toplevel']: return str(root/'checkout').encode()
                if args == ['git','rev-parse','HEAD']: return source.encode()
                if args[:2] == ['git','status']: return b''
                raise AssertionError('No non-Git subprocess authorized in this fixture')
            def fake_import(store, *args): store.write_bytes(b'not-a-keystore')
            argv = ['setup','--repository',REPO,'--private-directory',str(destination),'--tooling-sha',source]
            if import_release: argv.append('--import-release')
            with patch.dict(m.os.environ,dict(PREFIX='/data/data/com.termux/files/usr'),clear=True), \
                    patch('sys.argv',argv), patch.object(Path,'home',return_value=root), \
                    patch.object(owner,'gh_api',side_effect=api),patch.object(owner,'command',side_effect=command), \
                    patch.object(owner,'historical_identity',return_value='aa'*32), \
                    patch('builtins.input',return_value='IMPORTAR IDENTIDAD HISTORICA'), \
                    patch.object(owner.getpass,'getpass',return_value='fixture-password-with-20-chars'), \
                    patch.object(owner,'import_release',side_effect=fake_import) as imported, \
                    patch.object(owner,'certificate_pin',return_value='bb'*32), \
                    patch.object(owner,'encrypted_backup') as backup, self.assertRaises(ValueError):
                owner.main()
            self.assertEqual(imported.call_count,1 if import_release else 0)
            backup.assert_not_called()

    def test_main_never_generates_release_for_historical_application(self):
        self.owner_main_fixture()

    def test_wrong_imported_certificate_aborts_before_dev_backup_or_secrets(self):
        self.owner_main_fixture(import_release=True)

    def test_historical_certificate_verified_before_import(self):
        with patch.object(m,'download_historical_apk') as apk, patch.object(owner,'command',return_value=(
            'Signer #1 certificate SHA-256 digest: ' + m.legacy_baselines(REPO)[0]['certificateSha256'] +
            '\nVerified using v2 scheme (APK Signature Scheme v2): true\n').encode()):
            self.assertEqual(owner.historical_identity(REPO),m.legacy_baselines(REPO)[0]['certificateSha256'])
            apk.assert_called_once()
        with patch.object(m,'download_historical_apk',side_effect=ValueError('wrong hash')), self.assertRaises(ValueError):
            owner.historical_identity(REPO)

    def test_wrong_official_apk_certificate_aborts(self):
        with patch.object(m, 'download_historical_apk'), patch.object(owner, 'command', return_value=(
                'Signer #1 certificate SHA-256 digest: ' + '00' * 32 +
                '\nVerified using v2 scheme (APK Signature Scheme v2): true\n').encode()), self.assertRaises(ValueError):
            owner.historical_identity(REPO)

    def test_import_passwords_use_environment_not_argv(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'historical.jks';source.write_bytes(b'fake input, never a key')
            calls=[]
            with patch.object(Path,'home',return_value=root), patch('builtins.input',side_effect=[str(source),'old-alias']), \
                    patch.object(owner.getpass,'getpass',side_effect=['old-store-secret','old-key-secret']), \
                    patch.object(owner,'command',side_effect=lambda args,**kw:calls.append((args,kw))):
                owner.import_release(root/'release.p12','new-store-secret',root/'checkout')
            args,kw=calls[0]
            self.assertIn('-importkeystore',args)
            self.assertNotIn('-genkeypair',args)
            for secret in ('old-store-secret','old-key-secret','new-store-secret'):
                self.assertNotIn(secret,' '.join(map(str,args)))
                self.assertIn(secret,kw['environment'].values())

    def test_existing_different_or_unpinned_identity_rejected(self):
        public=dict(certificates=dict(release='aa'*32,dev='bb'*32))
        def api(path):
            return dict(secrets=[dict(name='SIGNING_KEYSTORE_B64')]) if path.endswith('/secrets') else dict(variables=[])
        with patch.object(owner,'gh_api',side_effect=api),self.assertRaises(ValueError): owner.verify_existing_pins(REPO,public)
        with patch.object(owner,'gh_api',return_value=dict(variables=[dict(name='CERTIFICATE_SHA256',value='cc'*32)],secrets=[])),self.assertRaises(ValueError):
            owner.verify_existing_pins(REPO,public)

    def test_public_certificate_without_private_entry_rejected(self):
        with patch.object(owner,'command',return_value=b'Entry type: trustedCertEntry'),self.assertRaises(ValueError):
            owner.certificate_pin(Path('unused'),'release','secret')


if __name__ == '__main__':
    unittest.main()

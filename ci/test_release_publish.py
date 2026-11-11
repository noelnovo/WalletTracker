"""Synthetic-only tests: no production signing, credentials or publication."""
import json
import base64
import hashlib
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

from ci import release_publish as subject
from ci.vault_access import Vault


class ReleaseTests(unittest.TestCase):
    def test_real_signing_flow_uses_only_synthetic_key_and_env_references(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);unsigned=root/'app/build/outputs/apk/release/app-release-unsigned.apk'
            unsigned.parent.mkdir(parents=True);unsigned.write_bytes(b'synthetic unsigned APK')
            config=root/'app/build/generated/source/buildConfig/release/package/BuildConfig.java'
            config.parent.mkdir(parents=True)
            config.write_text('String API_BASE_URL = "https://api.downops.win"; String API_VERSION = "2"; boolean REGISTRATION_UI_AVAILABLE = true; String SIGN_SECRET = "";')
            receipt={'sha':'a'*40,'pipeline':'9','unsigned_sha256':hashlib.sha256(unsigned.read_bytes()).hexdigest()}
            (root/'release-input.json').write_text(json.dumps(receipt))
            values={'KEYSTORE_BASE64':base64.b64encode(b'synthetic key, never used cryptographically').decode(),
                    'KEYSTORE_PASSWORD':'synthetic store password','KEY_PASSWORD':'synthetic key password','KEY_ALIAS':'synthetic alias'}
            class Vault:
                def read(self,path):return values
            def command(args,env):
                self.assertNotIn(values['KEYSTORE_PASSWORD'],args)
                self.assertNotIn(values['KEY_PASSWORD'],args)
                if args[0].endswith('aapt2'):
                    return "package: name='win.downops.wallettracker' versionCode='30200' versionName='3.2.0-alpha'"
                if args[0].endswith('zipalign'):Path(args[-1]).write_bytes(unsigned.read_bytes())
                if 'sign' in args:
                    self.assertIn('env:PROD_STORE_PASSWORD',args)
                    self.assertEqual(env['PROD_STORE_PASSWORD'],values['KEYSTORE_PASSWORD'])
                    Path(args[args.index('--out')+1]).write_bytes(b'synthetic signed APK')
                return 'Signer #1 certificate SHA-256 digest: '+subject.SIGNER
            with patch.dict(os.environ,self.env(),clear=True),patch.object(subject,'command',side_effect=command):
                output=subject.sign(root,Path('/sdk'),'v3.2.0',Vault())
            self.assertEqual(len(list(output.iterdir())),3)
            self.assertFalse(any(root.rglob('production.p12')))
            published=json.loads((output/'release-receipt.json').read_text())
            self.assertEqual(published['unsigned_sha256'],receipt['unsigned_sha256'])
            self.assertEqual(published['signer_sha256'],subject.SIGNER)
            receipt['pipeline']='other';(root/'release-input.json').write_text(json.dumps(receipt))
            with patch.dict(os.environ,self.env(),clear=True),self.assertRaises(RuntimeError):
                subject.sign(root,Path('/sdk'),'v3.2.0',Vault())

    def test_package_upload_download_checksum_and_redirect_refusal(self):
        class Response(io.BytesIO):
            status=201
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'synthetic.apk';path.write_bytes(b'synthetic APK')
            with patch.object(subject,'open_url',side_effect=[Response(b'created'),Response(path.read_bytes()),Response(path.read_bytes())]):
                link=subject.upload('v3.2.0',path,{})
                self.assertEqual(link['name'],path.name)
                self.assertEqual(link['direct_asset_path'],'/'+path.name)
                self.assertTrue(link['url'].startswith(subject.PUBLIC_API))
            for responses in [[Response(b'created'),Response(b'changed')]]:
                with patch.object(subject,'open_url',side_effect=responses),self.assertRaises(RuntimeError):
                    subject.upload('v3.2.0',path,{})
            wrong=Response();wrong.status=200
            with patch.object(subject,'open_url',return_value=wrong),self.assertRaises(RuntimeError):
                subject.upload('v3.2.0',path,{})
        self.assertIsNone(subject.NoRedirect().redirect_request(None,None,None,None,None,None))
        with patch.object(subject.urllib.request,'build_opener') as opener:
            subject.open_url('synthetic-request')
            opener.return_value.open.assert_called_once_with('synthetic-request',timeout=120)

    def test_absence_proof_refuses_permission_errors_and_checks_all_destinations(self):
        for status in [404,403]:
            error=urllib.error.HTTPError('https://example.invalid',status,'synthetic',{},None)
            with patch.object(subject,'open_url',side_effect=error) as call:
                if status==404:
                    subject.ensure_absent('v3.2.0',{})
                    self.assertEqual(call.call_count,4)
                else:
                    with self.assertRaises(RuntimeError):subject.ensure_absent('v3.2.0',{})

    def test_main_publishes_only_verified_files_after_signer_token_revocation(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);notes=root/'docs/releases';notes.mkdir(parents=True)
            (notes/'3.2.0.md').write_text('Synthetic release notes')
            output=root/'artifacts';output.mkdir();(output/'synthetic.apk').write_bytes(b'synthetic APK')
            link={'name':'synthetic.apk','url':'https://example.invalid/synthetic.apk'}
            class Vault:
                closed=False
                def __init__(self,role):pass
                def close(self):Vault.closed=True
            with patch.dict(os.environ,dict(self.env(),CI_PROJECT_DIR=directory,CI_JOB_TOKEN='synthetic-job-token',ANDROID_HOME='/sdk'),clear=True), \
                 patch.object(subject,'identity',return_value='v3.2.0'),patch.object(subject,'verify_backend'), \
                 patch.object(subject,'ensure_absent'),patch.object(subject,'Vault',Vault), \
                 patch.object(subject,'sign',return_value=output),patch.object(subject,'upload',return_value=link), \
                 patch.object(subject,'request',side_effect=[{}, {'assets':{'links':[link]}}]),patch('builtins.print'):
                subject.main()
                self.assertTrue(Vault.closed)

    def test_backend_readiness_and_v2_auth_boundary(self):
        with patch.object(subject.urllib.request,'urlopen') as open_url:
            open_url.return_value.__enter__.return_value.status=200
            open_url.return_value.__enter__.return_value.read.return_value=b'{"success":true}'
            with patch.object(subject.json,'load',return_value={'success':True}):
                open_url.side_effect=[open_url.return_value,urllib.error.HTTPError('https://example.invalid',401,'auth required',{},None)]
                subject.verify_backend()
        with patch.object(subject.urllib.request,'urlopen') as open_url:
            open_url.return_value.__enter__.return_value.status=503
            with self.assertRaises(RuntimeError):subject.verify_backend()

    def env(self):
        return {'CI_PROJECT_ID':'31', 'CI_PIPELINE_SOURCE':'push', 'CI_COMMIT_REF_PROTECTED':'true',
                'CI_COMMIT_TAG':'v3.2.0', 'CI_COMMIT_SHA':'a'*40, 'CI_PIPELINE_ID':'9'}

    def test_exact_tag_current_main_and_same_pipeline_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'app').mkdir(); (root/'security-output').mkdir()
            (root/'app/build.gradle.kts').write_text('val majorVersion = 3\nval minorVersion = 2\nval patchVersion = 0')
            receipt = {'project':'31','pipeline':'9','head':'a'*40,'base':'a'*40,'target':'main','event':'push'}
            (root/'security-output/receipt.json').write_text(json.dumps(receipt))
            with patch.object(subject, 'command', return_value='a'*40+' refs/heads/main'):
                self.assertEqual(subject.identity(root, self.env()), 'v3.2.0')
                for key, value in [('CI_COMMIT_TAG','v3.1.1'),('CI_PROJECT_ID','10'),
                                   ('CI_COMMIT_REF_PROTECTED','false'),('CI_PIPELINE_SOURCE','web'),
                                   ('CI_PIPELINE_ID','10')]:
                    with self.subTest(key=key), self.assertRaises(RuntimeError):
                        subject.identity(root, dict(self.env(), **{key:value}))
            with patch.object(subject, 'command', return_value='b'*40+' refs/heads/main'):
                with self.assertRaises(RuntimeError):subject.identity(root, self.env())

    def test_signing_role_cannot_read_other_paths(self):
        vault = object.__new__(Vault)
        with patch('ci.vault_access.request') as call:
            with self.assertRaises(RuntimeError):vault.read('downops/data/services/sonarqube/clients/instance-005')
            call.assert_not_called()

    def test_original_certificate_required(self):
        with patch.object(subject, 'command', return_value='Signer #1 certificate SHA-256 digest: '+subject.SIGNER):
            subject.verify_signature(Path('synthetic.apk'),Path('/sdk'),{})
        for value in ['', 'Signer #1 certificate SHA-256 digest: '+'0'*64]:
            with patch.object(subject,'command',return_value=value), self.assertRaises(RuntimeError):
                subject.verify_signature(Path('synthetic.apk'),Path('/sdk'),{})

    def test_production_package_and_non_debuggable_version_required(self):
        correct = "package: name='win.downops.wallettracker' versionCode='30200' versionName='3.2.0-alpha'"
        with patch.object(subject,'command',return_value=correct):
            subject.verify_apk(Path('synthetic.apk'),Path('/sdk'),{},'3.2.0')
        for value in [correct+'\napplication-debuggable',correct.replace('30200','30101'),correct.replace('wallettracker','other')]:
            with patch.object(subject,'command',return_value=value), self.assertRaises(RuntimeError):
                subject.verify_apk(Path('synthetic.apk'),Path('/sdk'),{},'3.2.0')

    def test_existing_destination_prevents_signing(self):
        with patch.object(subject,'open_url'):
            with self.assertRaises(RuntimeError):subject.ensure_absent('v3.2.0',{})

    def test_command_error_does_not_disclose_tool_output(self):
        with patch.object(subject.subprocess,'run') as run:
            run.return_value.returncode=1
            run.return_value.stdout='synthetic diagnostic'
            with self.assertRaisesRegex(RuntimeError,'sensitive diagnostics withheld'):
                subject.command(['synthetic-tool'],{})

    def test_resume_existing_package_verifies_source_digest_and_signer_without_signing(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);unsigned=root/'app/build/outputs/apk/release/app-release-unsigned.apk'
            unsigned.parent.mkdir(parents=True);unsigned.write_bytes(b'synthetic unsigned')
            signed=b'synthetic already-signed';digest=hashlib.sha256(signed).hexdigest()
            receipt={'tag':'v3.2.0','source_revision':'a'*40,'pipeline':'9','signer_sha256':subject.SIGNER,
                     'apk_sha256':digest,'unsigned_sha256':hashlib.sha256(unsigned.read_bytes()).hexdigest()}
            files={'WalletTracker-v3.2.0.apk':signed,'WalletTracker-v3.2.0.apk.sha256':(digest+'  WalletTracker-v3.2.0.apk\n').encode(),
                   'release-receipt.json':json.dumps(receipt).encode()}
            def open_url(req):return io.BytesIO(files[req.full_url.rsplit('/',1)[1]])
            env=dict(self.env(),RELEASE_RESUME_VERIFIED_PACKAGE='9:'+'a'*40+':v3.2.0')
            with patch.dict(os.environ,env,clear=True),patch.object(subject,'open_url',side_effect=open_url), \
                 patch.object(subject,'verify_signature') as signature,patch.object(subject,'verify_apk') as apk, \
                 patch.object(subject,'Vault') as vault,patch.object(subject,'sign') as sign,patch('builtins.print'):
                output=subject.resume_package(root,Path('/sdk'),'v3.2.0',{})
                signature.assert_called_once();apk.assert_called_once();vault.assert_not_called();sign.assert_not_called()
                links=subject.existing_links('v3.2.0',output,{})
                self.assertEqual(3,len(links))
                self.assertTrue(all(link['url'].startswith(subject.PUBLIC_API) for link in links))

    def test_resume_approval_must_bind_the_exact_pipeline_source_and_tag(self):
        with tempfile.TemporaryDirectory() as directory,patch.dict(os.environ,self.env(),clear=True), \
             patch.object(subject,'open_url') as download,self.assertRaises(RuntimeError):
            subject.resume_package(Path(directory),Path('/sdk'),'v3.2.0',{})
        download.assert_not_called()

    def test_metadata_resume_main_never_logs_into_vault_or_uploads_again(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);notes=root/'docs/releases';notes.mkdir(parents=True)
            (notes/'3.2.0.md').write_text('Synthetic notes');output=root/'artifacts';output.mkdir()
            link={'name':'synthetic.apk','url':subject.PUBLIC_API+'/synthetic.apk'}
            env=dict(self.env(),CI_PROJECT_DIR=directory,CI_JOB_TOKEN='synthetic-job',ANDROID_HOME='/sdk',
                     RELEASE_RESUME_VERIFIED_PACKAGE='9:'+'a'*40+':v3.2.0')
            with patch.dict(os.environ,env,clear=True),patch.object(subject,'identity',return_value='v3.2.0'), \
                 patch.object(subject,'verify_backend'),patch.object(subject,'resume_package',return_value=output), \
                 patch.object(subject,'existing_links',return_value=[link]),patch.object(subject,'Vault') as vault, \
                 patch.object(subject,'sign') as sign,patch.object(subject,'upload') as upload, \
                 patch.object(subject,'request',side_effect=[RuntimeError('HTTP 404'),{}, {'assets':{'links':[link]}}]), \
                 patch('builtins.print'):
                subject.main()
                vault.assert_not_called();sign.assert_not_called();upload.assert_not_called()

    def test_resume_rejects_tampered_receipt_before_signature_or_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);unsigned=root/'app/build/outputs/apk/release/app-release-unsigned.apk'
            unsigned.parent.mkdir(parents=True);unsigned.write_bytes(b'synthetic unsigned')
            files={'WalletTracker-v3.2.0.apk':b'synthetic signed','WalletTracker-v3.2.0.apk.sha256':b'0'*64,
                   'release-receipt.json':b'{"source_revision":"wrong"}'}
            with patch.dict(os.environ,dict(self.env(),RELEASE_RESUME_VERIFIED_PACKAGE='9:'+'a'*40+':v3.2.0'),clear=True), \
                 patch.object(subject,'open_url',side_effect=lambda req:io.BytesIO(files[req.full_url.rsplit('/',1)[1]])), \
                 patch.object(subject,'verify_signature') as signature,self.assertRaises(RuntimeError):
                subject.resume_package(root,Path('/sdk'),'v3.2.0',{})
            signature.assert_not_called()


if __name__ == '__main__':
    unittest.main()

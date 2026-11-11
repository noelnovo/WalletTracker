"""Synthetic SDK/archive/HTTPS tests. Never download or access real credentials."""
import hashlib
import io
import json
import os
import runpy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from ci import setup_android as sdk
from ci import vault_access as access


class SDKTests(unittest.TestCase):
    def test_historical_signer_wrapper_forwards_validation_without_signing(self):
        for error in [None,RuntimeError('synthetic failure')]:
            with patch('scripts.build.build_prod.main',side_effect=error) as main, \
                 patch('scripts.build.build_prod.report_failure') as report:
                if error:
                    with self.assertRaises(RuntimeError):runpy.run_module('scripts.release.check_prod',run_name='__main__')
                    report.assert_called_once()
                else:
                    runpy.run_module('scripts.release.check_prod',run_name='__main__')
                main.assert_called_once_with(validation=True)

    def archive(self, name='tools/bin/manager'):
        out=io.BytesIO()
        with zipfile.ZipFile(out,'w') as archive:
            info=zipfile.ZipInfo(name);info.external_attr=0o100755<<16
            archive.writestr(info,b'synthetic tool, not executed')
        return out.getvalue()

    def test_checksum_verified_extraction_and_executable_mode(self):
        raw=self.archive()
        with tempfile.TemporaryDirectory() as directory, patch.dict(sdk.FILES,{'fixture.zip':hashlib.sha256(raw).hexdigest()}), \
             patch.object(sdk.urllib.request,'urlopen',return_value=io.BytesIO(raw)):
            sdk.extract('fixture.zip',Path(directory))
            target=Path(directory)/'tools/bin/manager'
            self.assertEqual(target.read_bytes(),b'synthetic tool, not executed')
            self.assertEqual(target.stat().st_mode&0o777,0o755)

    def test_changed_archive_and_traversal_are_rejected(self):
        for raw,checksum in [(self.archive(),'0'*64),(self.archive('../outside'),None)]:
            with tempfile.TemporaryDirectory() as directory, \
                 patch.dict(sdk.FILES,{'fixture.zip':checksum or hashlib.sha256(raw).hexdigest()}), \
                 patch.object(sdk.urllib.request,'urlopen',return_value=io.BytesIO(raw)),self.assertRaises(RuntimeError):
                sdk.extract('fixture.zip',Path(directory))

    def test_build_installs_official_tools_and_accepts_licenses_without_shell_pipe(self):
        with tempfile.TemporaryDirectory() as directory:
            def extract(name,target):
                (target/'cmdline-tools/bin').mkdir(parents=True)
                (target/'cmdline-tools/bin/sdkmanager').write_text('synthetic, never executed')
            with patch.dict(os.environ,{'ANDROID_HOME':directory,'SIGNING_SDK_ONLY':'false'}), \
                 patch.object(sdk,'extract',side_effect=extract) as download,patch.object(sdk.subprocess,'run') as run:
                sdk.main()
                self.assertEqual(download.call_count,1)
                self.assertEqual(run.call_count,2)
                self.assertIn('platforms;android-36',run.call_args.args[0])
                sdk.main()
                self.assertEqual(download.call_count,1)

    def test_signing_uses_only_independent_pinned_sdk(self):
        with tempfile.TemporaryDirectory() as directory:
            def extract(name,target):(target/'official-tools').mkdir()
            with patch.dict(os.environ,{'ANDROID_HOME':directory,'SIGNING_SDK_ONLY':'true'}), \
                 patch.object(sdk,'extract',side_effect=extract),patch.object(sdk.subprocess,'run') as run:
                sdk.main()
                self.assertTrue((Path(directory)/'build-tools/36.0.0').is_dir())
                run.assert_not_called()

    def test_signing_rejects_ambiguous_archive_layout(self):
        with tempfile.TemporaryDirectory() as directory:
            def extract(name,target):
                (target/'one').mkdir();(target/'two').mkdir()
            with patch.dict(os.environ,{'ANDROID_HOME':directory,'SIGNING_SDK_ONLY':'true'}), \
                 patch.object(sdk,'extract',side_effect=extract),self.assertRaises(RuntimeError):sdk.main()


class VaultTests(unittest.TestCase):
    def env(self):
        return {'GITLAB_CI':'true','CI_PROJECT_ID':'31','CI_PROJECT_PATH':'downops/wallet-tracker-android',
                'CI_PIPELINE_SOURCE':'push','VAULT_ID_TOKEN':'synthetic-jwt'}

    def test_signer_login_read_and_revoke_are_separate_from_scanner(self):
        env=self.env()
        with patch.dict(os.environ,env,clear=True),patch.object(access,'request') as request:
            request.side_effect=[{'auth':{'client_token':'synthetic-token'}},{'data':{'data':{'KEY_ALIAS':'synthetic-alias'}}},{}]
            vault=access.Vault('wallettracker-android-gitlab-prod')
            self.assertEqual(vault.read('downops/data/apps/wallettracker/prod/mobile'),{'KEY_ALIAS':'synthetic-alias'})
            vault.close()
            self.assertTrue(request.call_args.args[0].endswith('/revoke-self'))

    def test_invalid_identity_role_mr_and_missing_jwt_fail_before_login(self):
        cases=[(dict(self.env(),CI_PROJECT_ID='1'),'wallettracker-android-gitlab-prod'),
               (self.env(),'scanner'),(dict(self.env(),CI_PIPELINE_SOURCE='merge_request_event'),'wallettracker-android-gitlab-prod'),
               (dict(self.env(),VAULT_ID_TOKEN=''),'wallettracker-android-gitlab-prod')]
        for env,role in cases:
            with patch.dict(os.environ,env,clear=True),patch.object(access,'request') as request,self.assertRaises(RuntimeError):
                access.Vault(role)
            request.assert_not_called()

    def test_https_transport_has_no_url_credentials_and_refuses_errors(self):
        class Response:
            status=200
            def read(self):return b'{"ok":true}'
            def getheaders(self):return [('Content-Type','application/json')]
        instances=[]
        class Connection:
            def __init__(self,host,port,context,timeout):
                self.host=host;self.port=port;self._context=context;instances.append(self)
            def request(self,*args,**kwargs):self.sent=(args,kwargs)
            def getresponse(self):return Response()
            def close(self):self.closed=True
        for url in ['http://example.invalid','https://user:password@example.invalid']:
            with self.assertRaises(RuntimeError):access.raw_request(url)
        with patch.object(access.http.client,'HTTPSConnection',Connection), \
             patch.object(access.ssl,'create_default_context') as context,patch.object(access.socket,'create_connection') as connect:
            self.assertEqual(access.request('https://vault.int.downops.win/path?mode=test','POST',{'data':'synthetic'}),{'ok':True})
            instances[-1].connect()
            self.assertEqual(connect.call_args.args[0],('10.2.0.10',443))
            self.assertTrue(instances[-1].closed)
            access.raw_request('https://example.invalid/path')
            instances[-1].connect()
            self.assertEqual(connect.call_args.args[0],('example.invalid',443))
            Response.status=403
            with self.assertRaisesRegex(RuntimeError,'HTTP 403'):access.raw_request('https://example.invalid')
        with patch.object(access,'raw_request',return_value=(b'',{})):
            self.assertEqual(access.request('https://example.invalid'),{})


if __name__=='__main__':unittest.main()

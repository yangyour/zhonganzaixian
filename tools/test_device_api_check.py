import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('device_check',Path(__file__).with_name('check-device-api.py'))
check=importlib.util.module_from_spec(spec);spec.loader.exec_module(check)


class DeviceCheckPayloadTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.root=Path(self.directory.name)
        (self.root/'deploy/private').mkdir(parents=True)
        self.patch=patch.object(check,'ROOT',self.root);self.patch.start()
    def tearDown(self):self.patch.stop();self.directory.cleanup()
    def write(self,file,value):(self.root/'deploy/private'/file).write_text(json.dumps(value),encoding='utf-8')
    def credentials(self,host,authorized):return {'target':host,'username':'SYNTHETIC_USER','password':'SYNTHETIC_PASSWORD','temporary_certificate_bypass_authorized':authorized}
    def test_wrong_target_does_not_send_a_different_devices_credentials(self):
        self.write('firewall-credentials.json',self.credentials('192.168.1.179',True))
        with self.assertRaises(ValueError):check.payload('firewall','TEST_INSECURE')
    def test_test_mode_requires_recorded_authorization(self):
        self.write('firewall-credentials.json',self.credentials('192.169.11.1',False))
        with self.assertRaises(ValueError):check.payload('firewall','TEST_INSECURE')
        self.assertEqual('SYSTEM',check.payload('firewall','SYSTEM')['configuration']['tls_mode'])
    def test_diagnostic_does_not_change_private_configuration_or_invent_scope(self):
        self.write('scp-password-credentials.json',self.credentials('192.168.1.179',True))
        config={'base_url':'https://192.168.1.179','enabled':False,'resource_pool_id':None,'scp_project_id':None,'tls_mode':'CA_FILE'}
        self.write('scp-connection.json',config)
        value=check.payload('scp','TEST_INSECURE')
        self.assertIsNone(value['configuration']['resource_pool_id']);self.assertIsNone(value['configuration']['scp_project_id'])
        self.assertEqual(config,json.loads((self.root/'deploy/private/scp-connection.json').read_text(encoding='utf-8')))


if __name__=='__main__':unittest.main()

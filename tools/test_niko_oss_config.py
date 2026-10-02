"""Synthetic data only. These checks do not authenticate to any storage provider."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('oss_config', Path(__file__).parent/'niko-test/oss_config.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
BUCKET = 'mop-unit-only'
PREFIX = 'mop-niko-test/12345678-1234-1234-1234-123456789abc/'


def config():
    return {
        'OBJECT_STORAGE_ENABLED':'True', 'OBJECT_STORAGE_PROVIDER':'oss',
        'OBJECT_STORAGE_ENDPOINT':module.ENDPOINT, 'OBJECT_STORAGE_PUBLIC_ENDPOINT':module.ENDPOINT,
        'OBJECT_STORAGE_REGION':'cn-beijing', 'OBJECT_STORAGE_BUCKET':BUCKET,
        'OBJECT_STORAGE_ACCESS_KEY':'synthetic-access', 'OBJECT_STORAGE_SECRET_KEY':'synthetic-secret',
        'OBJECT_STORAGE_PRESIGN_EXPIRES':'3600', 'MOP_OBJECT_STORAGE_PREFIX':PREFIX,
    }


class OssPrivateConfigTests(unittest.TestCase):
    def test_json_bom_and_independent_mapping(self):
        value = module.parse_bytes(b'\xef\xbb\xbf'+json.dumps(config()).encode())
        result = module.validate(value, BUCKET, PREFIX)
        self.assertEqual(config(), result)
        self.assertIsNot(value, result)

    def test_literal_dotenv_supports_quotes_comments_and_equals_in_token(self):
        values = config() | {'OBJECT_STORAGE_SECURITY_TOKEN':'synthetic=token=='}
        raw = '# private\n'+'\n'.join(f'{k}="{v}"' for k,v in values.items())
        self.assertEqual(values, module.validate(module.parse_bytes(raw.encode()), BUCKET, PREFIX))

    def test_duplicate_json_and_dotenv_rejected(self):
        for raw in (b'{"OBJECT_STORAGE_BUCKET":"a","OBJECT_STORAGE_BUCKET":"b"}', b'OBJECT_STORAGE_BUCKET=a\nOBJECT_STORAGE_BUCKET=b'):
            with self.subTest(raw=raw), self.assertRaises(module.InvalidConfiguration):
                module.parse_bytes(raw)

    def test_malformed_nested_nonstring_unknown_and_overlarge_rejected(self):
        for raw in (b'{',b'[]',b'{"OBJECT_STORAGE_ENABLED":true}',b'{"OBJECT_STORAGE_ENABLED":{}}', b'{"PASSWORD":"no"}',b'\xff', b' '*65537):
            with self.subTest(raw=raw[:50]), self.assertRaises(module.InvalidConfiguration):
                module.parse_bytes(raw)

    def test_no_shell_evaluation_or_interpolation(self):
        for value in ('$(touch /tmp/never-execute)', '`id`', '${HOME}', 'a\\nb'):
            with self.subTest(value=value), self.assertRaises(module.InvalidConfiguration):
                module.parse_bytes(json.dumps(config() | {'OBJECT_STORAGE_SECRET_KEY':value}).encode())
        with self.assertRaises(module.InvalidConfiguration):
            module.parse_bytes(b'export OBJECT_STORAGE_ENABLED=true')

    def test_every_required_field_is_explicit(self):
        for key in module.REQUIRED:
            values = config(); del values[key]
            with self.subTest(key=key), self.assertRaises(module.InvalidConfiguration):
                module.validate(values, BUCKET, PREFIX)

    def test_wrong_endpoint_region_provider_and_scope_rejected(self):
        for key,value in [('OBJECT_STORAGE_ENABLED','false'),('OBJECT_STORAGE_PROVIDER','minio'),('OBJECT_STORAGE_ENDPOINT','http://oss-cn-beijing.aliyuncs.com'),('OBJECT_STORAGE_PUBLIC_ENDPOINT','https://example.com'),('OBJECT_STORAGE_REGION','cn-shanghai'),('OBJECT_STORAGE_BUCKET','another-bucket'),('MOP_OBJECT_STORAGE_PREFIX','photos/')]:
            with self.subTest(key=key), self.assertRaises(module.InvalidConfiguration):
                module.validate(config() | {key:value}, BUCKET, PREFIX)

    def test_expected_scope_cannot_be_omitted_or_replaced(self):
        for bucket,prefix in [('',PREFIX),(BUCKET,''),(BUCKET,PREFIX.replace('12345678','87654321')),('BAD',PREFIX)]:
            with self.subTest(bucket=bucket,prefix=prefix), self.assertRaises(module.InvalidConfiguration):
                module.validate(config(), bucket, prefix)

    def test_expiry_boundaries(self):
        for value in ('0','3601','-1','1.5','1e3',' 60','99999'):
            with self.subTest(value=value), self.assertRaises(module.InvalidConfiguration):
                module.validate(config() | {'OBJECT_STORAGE_PRESIGN_EXPIRES':value}, BUCKET, PREFIX)
        for value in ('1','3600'):
            module.validate(config() | {'OBJECT_STORAGE_PRESIGN_EXPIRES':value}, BUCKET, PREFIX)

    def test_empty_and_control_credentials_rejected(self):
        for key in ('OBJECT_STORAGE_ACCESS_KEY','OBJECT_STORAGE_SECRET_KEY','OBJECT_STORAGE_SECURITY_TOKEN'):
            for value in ('','a b','a\nb','a\x7fb','a\x85b'):
                with self.subTest(key=key,value=repr(value)), self.assertRaises(module.InvalidConfiguration):
                    module.validate(config() | {key:value}, BUCKET, PREFIX)

    def test_private_read_does_not_mutate_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'candidate.json'; path.write_text(json.dumps(config())); path.chmod(0o600)
            before = path.read_bytes()
            self.assertEqual(config(), module.read_private_file(path, BUCKET, PREFIX))
            self.assertEqual(before, path.read_bytes())
            self.assertEqual([path], list(Path(directory).iterdir()))

    def test_world_readable_symlink_directory_and_fifo_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); path=root/'candidate'; path.write_text(json.dumps(config())); path.chmod(0o644)
            link=root/'link'; link.symlink_to(path)
            fifo=root/'fifo'; os.mkfifo(fifo,0o600)
            for target in (path,link,root,fifo):
                with self.subTest(kind=target.name), self.assertRaises(module.InvalidConfiguration):
                    module.read_private_file(target, BUCKET, PREFIX)

    def test_cli_success_is_explicitly_not_cloud_verified(self):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'candidate'; path.write_text(json.dumps(config())); path.chmod(0o600)
            output=io.StringIO()
            with contextlib.redirect_stdout(output):
                code=module.main(['--file',str(path),'--expected-bucket',BUCKET,'--expected-prefix',PREFIX])
            self.assertEqual(0,code)
            self.assertEqual('CONFIG_VALID_NOT_ACTIVE_NOT_VERIFIED',json.loads(output.getvalue())['status'])
            self.assertNotIn('synthetic',output.getvalue())

    def test_cli_error_never_echoes_arguments_or_file_content(self):
        for args in (['--secret','synthetic-sensitive'], ['--file','synthetic-sensitive']):
            output=io.StringIO()
            with contextlib.redirect_stderr(output):
                self.assertEqual(2,module.main(args))
            self.assertNotIn('synthetic',output.getvalue())
            self.assertEqual('CONFIG_REJECTED',json.loads(output.getvalue())['status'])
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'candidate'; path.write_text('synthetic-sensitive'); path.chmod(0o600)
            output=io.StringIO()
            with contextlib.redirect_stderr(output):
                self.assertEqual(2,module.main(['--file',str(path),'--expected-bucket',BUCKET,'--expected-prefix',PREFIX]))
            self.assertNotIn('synthetic',output.getvalue())


if __name__ == '__main__':
    unittest.main()

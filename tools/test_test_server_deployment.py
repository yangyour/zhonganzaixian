"""Pure deployment safeguards; never reads private config or connects to a server."""
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('deploy_server', Path(__file__).with_name('deploy-test-server.py'))
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)

class DeploymentSafeguards(unittest.TestCase):
    def test_proxy_pins_host_and_only_exposes_acceptance_realm(self):
        config = deploy.nginx_config({'public_origin': 'http://192.0.2.20:10010'})
        self.assertIn('if ($http_host != "192.0.2.20:10010") { return 400; }', config)
        self.assertIn('proxy_set_header Host "192.0.2.20:10010";', config)
        self.assertIn('proxy_set_header X-Forwarded-For $remote_addr;', config)
        self.assertIn('proxy_set_header Forwarded "";', config)
        self.assertIn('location /admin/ { return 404; }', config)
        self.assertIn('location /realms/ { return 404; }', config)
        self.assertIn('location /realms/mop-acceptance/ { proxy_pass http://127.0.0.1:12110; }', config)
        self.assertIn('access_log off;', config)
        self.assertNotIn('$proxy_add_x_forwarded_for', config)
        self.assertIn('listen 127.0.0.1:19000;', config)
        self.assertNotIn('listen 19000;', config)

    def test_callback_logout_and_api_reach_same_loopback_application(self):
        config = deploy.nginx_config({'public_origin': 'http://192.0.2.20:10010'})
        for path in ['/api/', '/oauth2/', '/login/oauth2/', '/auth/']:
            self.assertIn('location '+path+' { proxy_pass http://127.0.0.1:18010; }', config)
        self.assertIn('location /assets/ { try_files $uri =404;', config)
        self.assertIn('location = /assets { try_files /index.html =404; }', config)
        self.assertIn('location = /assets/ { try_files /index.html =404; }', config)

    def test_missing_or_ambiguous_artifacts_fail(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(deploy, 'ROOT', Path(folder)):
            with self.assertRaises(RuntimeError): deploy.artifact('*.jar')
            (Path(folder)/'one.jar').write_bytes(b'unit')
            self.assertEqual(deploy.artifact('*.jar').name, 'one.jar')
            (Path(folder)/'two.jar').write_bytes(b'unit')
            with self.assertRaises(RuntimeError): deploy.artifact('*.jar')

    def test_remote_failure_never_returns_stderr_or_command(self):
        output, error = io.BytesIO(b'private-output'), io.BytesIO(b'secret-example')
        output.channel = Mock(); output.channel.recv_exit_status.return_value = 3
        ssh = Mock(); ssh.exec_command.return_value = (Mock(), output, error)
        with self.assertRaisesRegex(RuntimeError, '^Remote project operation failed with exit 3$'):
            deploy.command(ssh, ['irrelevant-unit-command'])

    def test_remote_arguments_are_shell_quoted(self):
        output, error = io.BytesIO(b'ok'), io.BytesIO(b'')
        output.channel = Mock(); output.channel.recv_exit_status.return_value = 0
        ssh = Mock(); ssh.exec_command.return_value = (Mock(), output, error)
        self.assertEqual(deploy.command(ssh, ['printf', 'literal;$(ignored)']), 'ok')
        self.assertEqual(ssh.exec_command.call_args.args[0], "printf 'literal;$(ignored)'")

if __name__ == '__main__': unittest.main()

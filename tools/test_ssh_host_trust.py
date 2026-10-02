"""Isolated host-trust regression only. No real credentials, SSH connections or components."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import paramiko
import inspect_test_server as target


class HostTrustTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.private = self.root / 'deploy/private'
        self.private.mkdir(parents=True)
        self.keys = self.private / 'known_hosts'
        self.keys.write_text('unit-only-public-key-placeholder\n')
        self.client = MagicMock()
        self.client.get_host_keys.return_value.lookup.return_value = {'ssh-ed25519': object()}
        for name, value in [('ROOT', self.root), ('connection_values', lambda: ('192.0.2.1', 'unit-user', 'unit-password'))]:
            mock = patch.object(target, name, value); mock.start(); self.addCleanup(mock.stop)
        mock = patch.object(target.paramiko, 'SSHClient', return_value=self.client)
        mock.start(); self.addCleanup(mock.stop)

    def test_pinned_host_uses_reject_policy_and_never_rewrites_trust(self):
        before = self.keys.read_bytes()
        self.assertIs(target.connect(), self.client)
        self.client.load_host_keys.assert_called_once_with(str(self.keys))
        self.assertIsInstance(self.client.set_missing_host_key_policy.call_args.args[0], paramiko.RejectPolicy)
        self.client.get_host_keys.return_value.lookup.assert_called_once_with('192.0.2.1')
        self.client.connect.assert_called_once_with('192.0.2.1', username='unit-user', password='unit-password',
            timeout=15, auth_timeout=15, banner_timeout=15, look_for_keys=False, allow_agent=False)
        self.client.save_host_keys.assert_not_called()
        self.assertEqual(before, self.keys.read_bytes())

    def test_missing_pinned_file_rejects_before_authentication(self):
        with patch.object(Path, 'is_file', return_value=False):
            with self.assertRaisesRegex(ValueError, 'Pinned SSH host keys'):
                target.connect()
        self.client.connect.assert_not_called(); self.client.close.assert_called_once()

    def test_missing_target_pin_rejects_before_authentication(self):
        self.client.get_host_keys.return_value.lookup.return_value = None
        with self.assertRaisesRegex(ValueError, 'no pinned key'):
            target.connect()
        self.client.connect.assert_not_called(); self.client.close.assert_called_once()
        self.client.save_host_keys.assert_not_called()

    def test_invalid_trust_file_does_not_connect_or_rewrite(self):
        self.client.load_host_keys.side_effect = ValueError('invalid test trust file')
        with self.assertRaises(ValueError): target.connect()
        self.client.connect.assert_not_called(); self.client.close.assert_called_once()
        self.client.save_host_keys.assert_not_called()

    def test_handshake_failure_closes_client_without_changing_pin(self):
        self.client.connect.side_effect = paramiko.SSHException('unit handshake failure')
        with self.assertRaises(paramiko.SSHException): target.connect()
        self.client.close.assert_called_once(); self.client.save_host_keys.assert_not_called()


if __name__ == '__main__':
    unittest.main()

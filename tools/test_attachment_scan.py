"""Synthetic bytes and socket doubles only; no antivirus engine or file publication is simulated as real."""
import hashlib
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import attachment_scan as scanner

CONTENT = b'SYNTHETIC_UNIT_CONTENT'
SHA = hashlib.sha256(CONTENT).hexdigest()
VERSION = b'ClamAV 1.4.3/12345/Mon Oct 19 00:00:00 2026'


class AttachmentScanTest(unittest.TestCase):
    def scan(self, replies):
        with patch.object(scanner, 'exchange', side_effect=replies):
            return scanner.scan_bytes(CONTENT, SHA, 19310, True)

    def test_disabled_never_opens_connection_or_treats_unknown_as_clean(self):
        with patch.object(scanner.socket, 'create_connection') as connection:
            result = scanner.scan_bytes(CONTENT, SHA, 19310)
        connection.assert_not_called()
        self.assertEqual(result['verdict'], 'UNKNOWN')
        self.assertFalse(result['release_allowed'])
        self.assertFalse(result['scan_attempted'])

    def test_invalid_digest_port_content_and_limit_fail_before_network(self):
        with patch.object(scanner.socket, 'create_connection') as connection:
            for content, sha, port in [(CONTENT, '0' * 64, 19310), (CONTENT, SHA, True), (CONTENT, SHA, 0),
                                       (CONTENT, SHA, 65536), (b'', SHA, 19310), (bytearray(CONTENT), SHA, 19310)]:
                self.assertEqual(scanner.scan_bytes(content, sha, port, True)['verdict'], 'UNKNOWN')
            with patch.object(scanner, 'MAX_CONTENT_BYTES', 1):
                self.assertEqual(scanner.scan_bytes(CONTENT, SHA, 19310, True)['code'], 'INVALID_SCAN_INPUT')
        connection.assert_not_called()

    def test_no_threat_observation_never_authorizes_release(self):
        result = self.scan([VERSION, b'stream: OK', VERSION])
        self.assertEqual(result['verdict'], 'NO_THREAT_REPORTED')
        self.assertEqual(result['content_sha256'], SHA)
        self.assertEqual(result['engine_version'], '1.4.3')
        self.assertFalse(result['release_allowed'])
        self.assertFalse(result['signature_freshness_verified'])
        self.assertFalse(result['scanner_policy_verified'])

    def test_threat_response_is_redacted_and_cannot_release(self):
        result = self.scan([VERSION, b'stream: PRIVATE_DETECTION_NAME FOUND', VERSION])
        self.assertEqual(result['verdict'], 'THREAT_REPORTED')
        self.assertFalse(result['release_allowed'])
        self.assertNotIn('PRIVATE_DETECTION_NAME', json.dumps(result))

    def test_engine_or_signature_reload_during_scan_is_unknown(self):
        result = self.scan([VERSION, b'stream: OK', VERSION.replace(b'12345', b'12346')])
        self.assertEqual(result['code'], 'SCANNER_VERSION_CHANGED')
        self.assertEqual(result['verdict'], 'UNKNOWN')

    def test_unsupported_version_does_not_submit_attachment(self):
        with patch.object(scanner, 'exchange', return_value=b'COMMAND UNAVAILABLE') as exchange:
            result = scanner.scan_bytes(CONTENT, SHA, 19310, True)
        self.assertEqual(exchange.call_count, 1)
        self.assertFalse(result['scan_attempted'])

    def test_error_malformed_and_forged_success_are_unknown(self):
        for value in [b'stream: size limit exceeded ERROR', b'OK', b'stream: OK\n', b'other: OK', b'stream: \nmalicious FOUND']:
            self.assertEqual(self.scan([VERSION, value, VERSION])['verdict'], 'UNKNOWN')

    def test_connection_timeout_and_partial_submission_do_not_retry_or_leak(self):
        with patch.object(scanner, 'exchange', side_effect=[VERSION, TimeoutError('PRIVATE_DIAGNOSTIC')]) as exchange:
            result = scanner.scan_bytes(CONTENT, SHA, 19310, True)
        self.assertEqual(exchange.call_count, 2)
        self.assertTrue(result['scan_attempted'])
        self.assertEqual(result['verdict'], 'UNKNOWN')
        self.assertNotIn('PRIVATE_DIAGNOSTIC', json.dumps(result))

    def transport(self, received):
        connection = MagicMock()
        connection.__enter__.return_value = connection
        connection.recv.side_effect = received
        return connection

    def test_transport_uses_only_loopback_and_frames_exact_bytes_without_filename(self):
        content = CONTENT * 5000
        connection = self.transport([b'stream: OK\0', b''])
        with patch.object(scanner.socket, 'create_connection', return_value=connection) as connect:
            self.assertEqual(scanner.exchange(19310, b'zINSTREAM\0', scanner.time.monotonic() + 15, content), b'stream: OK')
        self.assertEqual(connect.call_args.args[0], ('127.0.0.1', 19310))
        sent = [call.args[0] for call in connection.sendall.call_args_list]
        self.assertEqual(sent[0], b'zINSTREAM\0')
        self.assertEqual(sent[-1], b'\0\0\0\0')
        chunks = []
        for frame in sent[1:-1]:
            self.assertEqual(struct.unpack('!I', frame[:4])[0], len(frame) - 4)
            chunks.append(frame[4:])
        self.assertEqual(b''.join(chunks), content)

    def test_truncated_multiple_and_oversized_replies_are_rejected(self):
        for received in [[b'stream: OK', b''], [b'stream: OK\0stream: threat FOUND\0', b''], [b'x' * 4097]]:
            connection = self.transport(received)
            with patch.object(scanner.socket, 'create_connection', return_value=connection):
                with self.assertRaises(ValueError):
                    scanner.exchange(19310, b'zVERSION\0', scanner.time.monotonic() + 15)

    def test_administration_commands_are_never_sent(self):
        with patch.object(scanner.socket, 'create_connection') as connection:
            for command in [b'zRELOAD\0', b'zSHUTDOWN\0', b'zSCAN /private/file\0']:
                with self.assertRaises(ValueError):
                    scanner.exchange(19310, command, scanner.time.monotonic() + 15)
        connection.assert_not_called()

    def test_shared_total_deadline_applies_before_every_io(self):
        connection = self.transport([b'stream: OK\0', b''])
        with patch.object(scanner.socket, 'create_connection', return_value=connection):
            with patch.object(scanner.time, 'monotonic', side_effect=[0, 1, 20]):
                with self.assertRaises(TimeoutError):
                    scanner.exchange(19310, b'zINSTREAM\0', 15, CONTENT)
        self.assertEqual(connection.sendall.call_count, 1)

    def test_cli_defaults_to_disabled_and_does_not_read_file_or_overwrite_report(self):
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / 'report.json'
            args = ['--file', 'nonexistent-private-file', '--sha256', SHA, '--output', str(output)]
            with patch.object(scanner.socket, 'create_connection') as connection:
                with patch('sys.stdout', new_callable=io.StringIO):
                    self.assertEqual(scanner.main(args), 2)
                    original = output.read_bytes()
                    self.assertEqual(scanner.main(args), 1)
                    self.assertEqual(output.read_bytes(), original)
            connection.assert_not_called()
            self.assertEqual(json.loads(original)['code'], 'SCAN_DISABLED')


if __name__ == '__main__':
    unittest.main()

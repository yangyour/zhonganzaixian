import importlib.util
import errno
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import patch

import huawei_snmp_probe as probe

spec = importlib.util.spec_from_file_location('huawei_probe_cli', Path(__file__).with_name('probe-huawei-snmp.py'))
cli = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cli)


def reply(values, request_id=7, community='unit-test-only', version=1, error=0):
    bindings = b''.join(probe.tlv(48, probe.encode_oid(oid) + probe.tlv(tag, raw))
                        for oid, tag, raw in values)
    return probe.tlv(48, probe.integer(version) + probe.tlv(4, community.encode()) + probe.tlv(
        162, probe.integer(request_id) + probe.integer(error) + probe.integer(0) + probe.tlv(48, bindings)))


class SnmpDiagnosticTest(unittest.TestCase):
    def parse(self, raw, oids=probe.IDENTITY, next_value=False):
        return probe.response(raw, 'unit-test-only', 7, oids, next_value)

    def config(self):
        return {'target': probe.TARGET, 'version': '2c', 'community': 'unit-test-only',
                'columns': [cli.COLUMNS['cpu']]}

    def identity(self, vendor='2011', tag=4):
        return {'status': 'RESPONSE', 'values': [
            {'asn1_tag': tag, 'status': 'VALUE', 'value': 'diagnostic test'},
            {'asn1_tag': 6, 'status': 'VALUE', 'value': '1.3.6.1.4.1.' + vendor + '.1'},
            {'asn1_tag': 67, 'status': 'VALUE', 'value': '100'}]}

    def test_oid_multi_byte_first_arc_and_large_interface_index(self):
        for value in ('1.3.6.1.4.1.2011.5.25.31.67108864', '2.999.4294967295', '0.39.0'):
            raw = probe.Reader(probe.encode_oid(value)).read(6)[1]
            self.assertEqual(value, probe.decode_oid(raw))

    def test_invalid_oid(self):
        for value in ('1.40.1', '3.1', '1.-1', '1.3.-1', '1.3.4294967296'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                probe.encode_oid(value)
        with self.assertRaises(ValueError):
            probe.decode_oid(b'\x81')

    def test_request_is_get_or_getnext_never_set(self):
        for next_value in (False, True):
            msg = probe.Reader(probe.Reader(probe.request('unit-test-only', probe.IDENTITY, 7, next_value)).read(48)[1])
            self.assertEqual(1, msg.number())
            self.assertEqual(b'unit-test-only', msg.read(4)[1])
            self.assertEqual(161 if next_value else 160, msg.read()[0])

    def test_identity_response(self):
        oid_value = probe.Reader(probe.encode_oid('1.3.6.1.4.1.2011.1')).read(6)[1]
        raw = reply([(probe.IDENTITY[0], 4, b'Huawei'), (probe.IDENTITY[1], 6, oid_value),
                     (probe.IDENTITY[2], 67, b'\x01\x00')])
        result = self.parse(raw)
        self.assertEqual(['Huawei', '1.3.6.1.4.1.2011.1', '256'], [v['value'] for v in result['values']])

    def test_identity_mismatch_rejected(self):
        for changes in ({'request_id': 8}, {'community': 'other'}, {'version': 0}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.parse(reply([], **changes), [])

    def test_truncation_bad_lengths_and_trailing_bytes(self):
        raw = reply([(probe.IDENTITY[0], 4, b'identity')])
        for cut in range(len(raw)):
            with self.subTest(cut=cut), self.assertRaises(ValueError):
                self.parse(raw[:cut], [probe.IDENTITY[0]])
        for bad in (raw + b'\0', b'\x30\x80', b'\x30\x84\0\0\0\0'):
            with self.assertRaises(ValueError):
                self.parse(bad, [probe.IDENTITY[0]])

    def test_wrong_oid_or_binding_count_fails_closed(self):
        for rows in ([], [(probe.IDENTITY[1], 4, b'wrong')],
                     [(probe.IDENTITY[0], 4, b'ok'), (probe.IDENTITY[1], 4, b'extra')]):
            with self.assertRaises(ValueError):
                self.parse(reply(rows), [probe.IDENTITY[0]])

    def test_signed_optical_power_and_counter64_preserved(self):
        oid = cli.COLUMNS['optical_rx_power'] + '.67108864'
        result = self.parse(reply([(oid, 2, (-1234).to_bytes(2, 'big', signed=True))]), [oid])
        self.assertEqual('-1234', result['values'][0]['value'])
        raw = b'\0' + (2 ** 64 - 1).to_bytes(8, 'big')
        result = self.parse(reply([(oid, 70, raw)]), [oid])
        self.assertEqual(str(2 ** 64 - 1), result['values'][0]['value'])

    def test_numeric_overflow_rejected(self):
        for tag, value in ((2, 2 ** 31), (65, 2 ** 32), (70, 2 ** 64)):
            raw = value.to_bytes((value.bit_length() + 7) // 8, 'big')
            if tag == 2:
                raw = b'\0' + raw  # Positive BER Integer32 overflow, not valid -2147483648.
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.parse(reply([(probe.IDENTITY[0], tag, raw)]), [probe.IDENTITY[0]])

    def test_no_such_and_end_are_null(self):
        for tag, expected in ((128, 'NO_SUCH_OBJECT'), (129, 'NO_SUCH_INSTANCE'), (130, 'END_OF_MIB')):
            result = self.parse(reply([(probe.IDENTITY[0], tag, b'')]), [probe.IDENTITY[0]])
            self.assertEqual(expected, result['values'][0]['status'])
            self.assertIsNone(result['values'][0]['value'])

    def test_getnext_must_advance(self):
        for oid in (cli.COLUMNS['cpu'], '1.3.6.1.2.1.1.1.0'):
            with self.assertRaises(ValueError):
                self.parse(reply([(oid, 2, b'\0')]), [cli.COLUMNS['cpu']], True)

    def test_reflected_secret_and_control_chars_removed(self):
        raw = reply([(probe.IDENTITY[0], 4, b'agent unit-test-only\n\x00')])
        result = self.parse(raw, [probe.IDENTITY[0]])
        self.assertNotIn('unit-test-only', str(result))
        self.assertNotIn('\n', result['values'][0]['value'])

    def test_agent_error_remains_distinct(self):
        self.assertEqual('AGENT_ERROR', self.parse(reply([], error=16))['status'])

    def test_wrong_target_version_and_secret_fail_before_network(self):
        for field, value in (('target', '192.168.1.3'), ('version', '3'), ('community', ''),
                             ('community', 'line\nbreak'), ('community', 'x' * 256), ('columns', [])):
            config = self.config()
            config[field] = value
            with patch.object(probe, 'exchange') as exchange, self.assertRaises(ValueError):
                probe.probe(config)
            exchange.assert_not_called()

    def test_missing_secret_or_unknown_fields_do_not_guess(self):
        config = self.config()
        del config['community']
        with patch.object(probe, 'exchange') as exchange, self.assertRaises(ValueError):
            probe.probe(config)
        exchange.assert_not_called()

    def test_no_response_stops_before_columns_and_never_ready(self):
        with patch.object(probe, 'exchange', return_value={'status': 'TIMEOUT_NO_RESPONSE', 'values': []}) as exchange:
            result = probe.probe(self.config())
        self.assertEqual(1, exchange.call_count)
        self.assertEqual('TIMEOUT_NO_RESPONSE', result['assessment'])
        self.assertFalse(result['monitoring_ready'])
        self.assertEqual([], result['columns'])

    def test_vendor_and_type_checked_before_private_columns(self):
        for identity in (self.identity(vendor='9'), self.identity(tag=2)):
            with patch.object(probe, 'exchange', return_value=identity) as exchange:
                result = probe.probe(self.config())
            self.assertEqual(1, exchange.call_count)
            self.assertEqual('IDENTITY_NOT_VERIFIED', result['assessment'])

    def test_getnext_outside_column_is_unknown_not_a_sample(self):
        next_result = {'status': 'RESPONSE', 'values': [{'oid': cli.COLUMNS['memory'] + '.1',
                       'status': 'VALUE', 'value': '12', 'asn1_tag': 2}]}
        with patch.object(probe, 'exchange', side_effect=[self.identity(), next_result]):
            result = probe.probe(self.config())
        row = result['columns'][0]['values'][0]
        self.assertEqual('COLUMN_NOT_RETURNED', row['status'])
        self.assertIsNone(row['value'])
        self.assertFalse(result['monitoring_ready'])

    def test_timeout_and_socket_failure_remain_distinct(self):
        for error, status in ((socket.timeout(), 'TIMEOUT_NO_RESPONSE'), (OSError(), 'UDP_TRANSPORT_UNAVAILABLE'),
                              (ConnectionRefusedError(errno.ECONNREFUSED, 'omitted'), 'UDP_PORT_UNREACHABLE'),
                              (OSError(errno.EHOSTUNREACH, 'omitted'), 'HOST_UNREACHABLE'),
                              (PermissionError(errno.EACCES, 'omitted'), 'LOCAL_ACCESS_DENIED')):
            with patch.object(socket, 'socket', side_effect=error):
                self.assertEqual(status, probe.exchange('unit-test-only', probe.IDENTITY)['status'])

    def test_credentials_cannot_be_read_from_public_path(self):
        with self.assertRaises(ValueError):
            cli.private_config(cli.ROOT / 'deploy/device-snmp/example.json')

    def test_main_records_logical_reference_for_shared_private_directory(self):
        import json
        import sys
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report_path = root / 'docs/evidence/result.json'
            credential = root / 'deploy/private/huawei-snmp.json'
            actual_resolve = Path.resolve

            def resolve(path, *args, **kwargs):
                # Model a junction to a private directory outside this checkout.
                if path == root / 'deploy/private':
                    return root.parent / 'shared-private'
                if path == credential:
                    return root.parent / 'shared-private/huawei-snmp.json'
                return actual_resolve(path, *args, **kwargs)
            report = {'monitoring_ready': False, 'vantage_points': {
                'test_server': {'assessment': 'TIMEOUT_NO_RESPONSE'},
                'zabbix_network_namespace': {'assessment': 'TIMEOUT_NO_RESPONSE'}}}
            with patch.object(cli, 'ROOT', root), patch.object(cli, 'private_config', return_value={}), \
                    patch.object(cli, 'inspect', return_value=report), \
                    patch.object(Path, 'resolve', resolve), \
                    patch.object(sys, 'argv', ['probe', '--credentials', str(credential), '--output', str(report_path)]):
                self.assertEqual(2, cli.main())
            saved = json.loads(report_path.read_text(encoding='utf-8'))
            self.assertEqual('deploy/private/huawei-snmp.json', saved['credential_reference'])
            self.assertNotIn('error_class', saved)


if __name__ == '__main__':
    unittest.main()

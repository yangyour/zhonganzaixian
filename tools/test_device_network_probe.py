"""Isolated validation/error-classification tests; these are not device evidence."""
import copy
import errno
import importlib.util
import json
from pathlib import Path
import socket
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

import device_network_probe as probe

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('lan_probe', ROOT / 'tools/probe-lan-devices.py')
lan = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lan)


def manifest():
    return json.loads((ROOT / 'deploy/lan-test/devices.json').read_text(encoding='utf-8'))


class ManifestTests(unittest.TestCase):
    def test_four_user_targets_and_models_preserved(self):
        value = probe.validate_manifest(manifest())
        self.assertEqual(['192.168.11.1', '192.168.1.170', '192.168.1.2', '192.168.1.125'],
                         [d['ip'] for d in value['devices']])
        self.assertEqual('aRS6300-50Q-EI-24X', value['devices'][3]['model'])

    def test_no_network_for_invalid_manifest(self):
        with patch.object(probe, 'probe_device') as network:
            with self.assertRaises(ValueError):
                probe.probe_manifest({'devices': []})
            network.assert_not_called()

    def test_unapproved_destination_shapes_rejected(self):
        for ip in ('8.8.8.8', '127.0.0.1', '169.254.169.254', '224.0.0.1', '::1',
                   '192.168.1.0/24', 'switch.local', '192.168.01.2', '192.168.1.2;reboot'):
            with self.subTest(ip=ip):
                value = manifest()
                value['devices'][0]['ip'] = ip
                with self.assertRaises(ValueError):
                    probe.validate_manifest(value)

    def test_credentials_commands_and_extra_fields_rejected(self):
        for field in ('password', 'community', 'command', 'ports', 'token'):
            with self.subTest(field=field):
                value = manifest()
                value['devices'][0][field] = 'never print this'
                with self.assertRaises(ValueError):
                    probe.validate_manifest(value)

    def test_duplicate_ip_or_key_rejected(self):
        for field in ('ip', 'key'):
            value = manifest()
            value['devices'][1][field] = value['devices'][0][field]
            with self.assertRaises(ValueError):
                probe.validate_manifest(value)

    def test_control_characters_and_oversize_text_rejected(self):
        for text in ('', ' model', 'model\n', 'model\x1b', '\ud800', 'x' * 129):
            value = manifest()
            value['devices'][0]['model'] = text
            with self.assertRaises(ValueError):
                probe.validate_manifest(value)

    def test_limit_environment_and_boolean_version_rejected(self):
        for key, value in [('schema_version', True), ('environment', 'production'),
                           ('devices', []), ('devices', manifest()['devices'] * 9)]:
            data = manifest()
            data[key] = value
            with self.assertRaises(ValueError):
                probe.validate_manifest(data)

    def test_validation_copies_inventory(self):
        source = manifest()
        validated = probe.validate_manifest(source)
        source['devices'][0]['model'] = 'changed'
        self.assertEqual('AF-1000-B1800', validated['devices'][0]['model'])


class ProbeTests(unittest.TestCase):
    def test_open_tcp_is_bounded_and_connection_closed(self):
        context = MagicMock()
        with patch.object(socket, 'create_connection', return_value=context) as call:
            result = probe.tcp_probe('192.168.1.2', 443)
        self.assertEqual('OPEN', result['state'])
        call.assert_called_once_with(('192.168.1.2', 443), timeout=2)
        context.__exit__.assert_called_once()

    def test_tcp_errors_distinguish_denial_refusal_and_reachability(self):
        for number, state in [(errno.ECONNREFUSED, 'REFUSED'), (errno.EHOSTUNREACH, 'HOST_UNREACHABLE'),
                              (errno.ENETUNREACH, 'NETWORK_UNREACHABLE'), (errno.EACCES, 'PROBE_DENIED'),
                              (errno.EPERM, 'PROBE_DENIED'), (errno.EIO, 'PROBE_ERROR')]:
            with self.subTest(state=state), patch.object(socket, 'create_connection',
                                                        side_effect=OSError(number, 'secret diagnostic')):
                result = probe.tcp_probe('192.168.1.2', 443)
                self.assertEqual(state, result['state'])
                self.assertNotIn('secret', json.dumps(result))

    def test_tcp_timeout_is_not_offline(self):
        with patch.object(socket, 'create_connection', side_effect=TimeoutError):
            result = probe.tcp_probe('192.168.1.2', 443)
        self.assertEqual('TIMEOUT', result['state'])
        self.assertEqual('UNCONFIRMED', probe.assess({'state': 'NO_REPLY'}, [result])['network_observation'])

    def test_ping_reply_keeps_observed_loss_and_rtt(self):
        result = subprocess.CompletedProcess([], 0, b'2 packets transmitted, 1 received, 50% packet loss\n'
                                             b'rtt min/avg/max/mdev = 1.1/1.2/1.3/0.1 ms\n')
        with patch.object(subprocess, 'run', return_value=result) as run:
            observed = probe.icmp_probe('192.168.1.2')
        self.assertEqual({'state': 'REPLY', 'sent': 2, 'received': 1, 'average_rtt_ms': 1.2}, observed)
        self.assertFalse(run.call_args.kwargs.get('shell', False))

    def test_ping_no_response_keeps_unknown(self):
        with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess(
                [], 1, b'2 packets transmitted, 0 received, +2 errors, 100% packet loss')):
            self.assertEqual('NO_REPLY', probe.icmp_probe('192.168.11.1')['state'])

    def test_ping_failure_or_malformed_output_never_becomes_no_reply(self):
        for code, output in [(2, b'permission denied'), (0, b''),
                             (0, b'2 packets transmitted, 3 received')]:
            with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], code, output)):
                self.assertEqual('PROBE_ERROR', probe.icmp_probe('192.168.1.2')['state'])

    def test_missing_and_timed_out_ping_are_probe_errors(self):
        for error, state in [(FileNotFoundError(), 'TOOL_MISSING'),
                             (subprocess.TimeoutExpired('ping', 7), 'PROBE_TIMEOUT')]:
            with patch.object(subprocess, 'run', side_effect=error):
                self.assertEqual(state, probe.icmp_probe('192.168.1.2')['state'])

    def test_route_only_exposes_selected_fields(self):
        raw = json.dumps([{'dst': '192.168.11.1', 'gateway': '192.168.1.1', 'dev': 'eth0',
                           'cache': [], 'unexpected': 'secret'}]).encode()
        with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, raw)):
            route = probe.route_probe('192.168.11.1')
        self.assertEqual('FOUND', route['state'])
        self.assertNotIn('unexpected', route)

    def test_route_errors_are_unknown(self):
        with patch.object(subprocess, 'run', side_effect=FileNotFoundError):
            self.assertEqual({'state': 'UNCONFIRMED'}, probe.route_probe('192.168.11.1'))

    def test_network_response_never_becomes_monitoring_or_identity(self):
        for icmp, tcp in [('REPLY', 'TIMEOUT'), ('NO_REPLY', 'OPEN')]:
            result = probe.assess({'state': icmp}, [{'state': tcp}])
            self.assertEqual('RESPONSE_OBSERVED', result['network_observation'])
            self.assertFalse(result['monitoring_ready'])
            self.assertFalse(result['device_identity_verified'])
            self.assertIsNone(result['device_health'])
            self.assertIsNone(result['firmware'])
            self.assertEqual('NOT_ATTEMPTED', result['snmp']['status'])

    def test_refusal_alone_is_not_proven_device_reachability(self):
        result = probe.assess({'state': 'NO_REPLY'}, [{'state': 'REFUSED'}])
        self.assertEqual('UNCONFIRMED', result['network_observation'])

    def test_explicit_targets_ports_and_deterministic_inventory_order(self):
        with patch.object(probe, 'icmp_probe', return_value={'state': 'NO_REPLY'}), \
                patch.object(probe, 'tcp_probe', side_effect=lambda ip, port: {'port': port, 'state': 'TIMEOUT'}) as tcp, \
                patch.object(probe, 'route_probe', return_value={'state': 'UNCONFIRMED'}):
            result = probe.probe_manifest(manifest())
        self.assertEqual([d['key'] for d in manifest()['devices']], [d['key'] for d in result['devices']])
        self.assertEqual(12, tcp.call_count)
        self.assertEqual({22, 80, 443}, {call.args[1] for call in tcp.call_args_list})


class RunnerTests(unittest.TestCase):
    def test_unowned_or_stopped_collector_refused(self):
        good = {'labels': {'mop.project': 'mop-lan-test'}, 'name': '/mop-lan-zabbix-server',
                'running': True, 'pid': 200}
        for field, value in [('labels', {}), ('labels', None), ('name', '/other'),
                             ('running', False), ('pid', 1), ('pid', True)]:
            candidate = {**good, field: value}
            with self.assertRaises(ValueError):
                lan.validate_collector(candidate)

    def test_exit_codes_do_not_hide_failed_vantage_or_unknown_device(self):
        row = {'status': 'COMPLETED', 'probe': {'devices': [{'network_observation': 'RESPONSE_OBSERVED'}]}}
        report = {'vantage_points': {'test_server': row, 'zabbix_network_namespace': copy.deepcopy(row)}}
        self.assertEqual(0, lan.exit_status(report))
        report['vantage_points']['test_server']['probe']['devices'][0]['network_observation'] = 'UNCONFIRMED'
        self.assertEqual(2, lan.exit_status(report))
        report['vantage_points']['zabbix_network_namespace'] = {'status': 'FAILED', 'probe': None}
        self.assertEqual(1, lan.exit_status(report))

    def test_failed_invocation_replaces_stale_success_without_exception_text(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'report.json'
            output.write_text('{"monitoring_ready":true}')
            with patch('sys.argv', ['probe-lan-devices.py', '--output', str(output)]), \
                    patch.object(lan, 'inspect', side_effect=RuntimeError('private password')), patch('builtins.print'):
                self.assertEqual(1, lan.main())
            report = json.loads(output.read_text())
            self.assertFalse(report['monitoring_ready'])
            self.assertEqual('FAILED', report['status'])
            self.assertNotIn('private password', json.dumps(report))


class SshBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'deploy/private').mkdir(parents=True)
        (self.root / 'tools').mkdir()
        (self.root / 'tools/device_network_probe.py').write_text('pass\n')
        self.key = MagicMock()
        self.key.asbytes.return_value = b'explicit synthetic key'
        self.cfg = {'host': '192.168.1.215', 'environment': 'lan-test-20261008',
                    'username': 'synthetic', 'password': 'synthetic-secret-not-in-argv',
                    'expected_host_key_sha256': lan.base64.b64encode(
                        lan.hashlib.sha256(self.key.asbytes()).digest()).decode().rstrip('=')}
        self.client = MagicMock()
        self.client.get_host_keys.return_value.lookup.return_value = {'ssh-test': self.key}
        self.collector = {'labels': {'mop.project': 'mop-lan-test'}, 'name': '/mop-lan-zabbix-server',
                          'running': True, 'pid': 200, 'id': 'synthetic-id',
                          'image': 'synthetic-image', 'started_at': '2026-10-10T00:00:00Z'}
        self.results = {'devices': [{**d, 'network_observation': 'RESPONSE_OBSERVED'} for d in manifest()['devices']]}
        self.fail_host = False
        self.change_collector = False
        self.collector_calls = 0
        self.commands, self.inputs = [], []

        def execute(command, timeout):
            self.commands.append(command)
            code = 0
            if command.startswith('cat '):
                value = {'project': 'mop-lan-test', 'environment': self.cfg['environment']}
            elif 'docker inspect' in command:
                self.collector_calls += 1
                value = dict(self.collector)
                if self.change_collector and self.collector_calls == 2:
                    value['pid'] = 201
            else:
                value = self.results
                if command.startswith('python3') and self.fail_host:
                    code = 1
                    value = {'error': 'private diagnostic'}
            stdin, stdout, stderr = MagicMock(), MagicMock(), MagicMock()
            self.inputs.append(stdin)
            stdout.read.return_value = json.dumps(value).encode()
            stdout.channel.recv_exit_status.return_value = code
            stderr.read.return_value = b'private diagnostic'
            return stdin, stdout, stderr

        self.client.exec_command.side_effect = execute
        module = SimpleNamespace(SSHClient=lambda: self.client, RejectPolicy=lambda: object())
        self.addCleanup(patch.stopall)
        patch.dict('sys.modules', {'paramiko': module}).start()
        patch.object(lan, 'ROOT', self.root).start()

    def run_probe(self):
        (self.root / 'deploy/private/lan-test-connection.json').write_text(json.dumps(self.cfg))
        return lan.inspect(manifest())

    def test_password_only_stdin_and_no_service_mutation(self):
        report = self.run_probe()
        self.assertEqual(0, lan.exit_status(report))
        self.assertTrue(any('nsenter --target 200 --net' in command for command in self.commands))
        self.assertTrue(all(command.startswith(('cat ', 'python3 ', "sudo -S -p '' -- docker inspect ",
                                               "sudo -S -p '' -- nsenter ")) for command in self.commands))
        self.assertNotIn(self.cfg['password'], '\n'.join(self.commands) + json.dumps(report))
        self.assertTrue(any(call.args == (self.cfg['password'] + '\n',)
                            for stdin in self.inputs for call in stdin.write.call_args_list))
        self.client.close.assert_called_once()

    def test_pin_mismatch_prevents_authentication(self):
        self.cfg['expected_host_key_sha256'] = 'wrong-key'
        with self.assertRaises(ValueError):
            self.run_probe()
        self.client.connect.assert_not_called()

    def test_host_failure_is_recorded_and_collector_still_checked(self):
        self.fail_host = True
        report = self.run_probe()
        self.assertEqual(1, lan.exit_status(report))
        self.assertEqual('FAILED', report['vantage_points']['test_server']['status'])
        self.assertEqual('COMPLETED', report['vantage_points']['zabbix_network_namespace']['status'])
        self.assertNotIn('private diagnostic', json.dumps(report))

    def test_restarted_collector_discards_samples(self):
        self.change_collector = True
        report = self.run_probe()
        row = report['vantage_points']['zabbix_network_namespace']
        self.assertEqual('FAILED', row['status'])
        self.assertIsNone(row['probe'])
        self.assertEqual(1, lan.exit_status(report))

    def test_response_for_wrong_target_is_rejected(self):
        self.results['devices'][0]['ip'] = '192.168.1.99'
        report = self.run_probe()
        self.assertEqual(1, lan.exit_status(report))
        self.assertTrue(all(row['status'] == 'FAILED' for row in report['vantage_points'].values()))


if __name__ == '__main__':
    unittest.main()

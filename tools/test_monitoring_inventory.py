import contextlib
import copy
import io
import json
import os
import socket
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import monitoring_inventory as inventory
import monitoring_presets as presets

EXAMPLE = presets.load_json(presets.ROOT / 'deploy/examples/monitoring/inventory.disabled.json')


def sample():
    return copy.deepcopy(EXAMPLE)


class InventoryPlan(unittest.TestCase):
    def test_all_thirteen_presets_and_multiple_services_compile_without_network(self):
        with patch.object(socket, 'create_connection', side_effect=AssertionError('network')), patch.object(socket, 'getaddrinfo', side_effect=AssertionError('DNS')):
            plan = inventory.compile_inventory(sample())
        self.assertTrue(plan['valid'])
        self.assertEqual(plan['target_count'], 15)
        self.assertEqual(plan['asset_count'], 3)
        self.assertEqual({t['preset_id'] for t in plan['configuration']['targets']}, set(presets.PRESETS))
        self.assertTrue(all(not t['enabled'] and t['interval_seconds'] is None for t in plan['configuration']['targets']))
        self.assertFalse(plan['network_accessed'])
        self.assertFalse(plan['asset_identity_verified'])

    def test_cross_language_golden_is_exact(self):
        golden = presets.load_json(presets.ROOT / 'fixtures/monitoring-inventory-golden.json')
        plan = inventory.compile_inventory(sample())
        self.assertEqual(plan['configuration'], golden['configuration'])
        self.assertEqual(plan['configuration_sha256'], golden['configuration_sha256'])
        for t in plan['configuration']['targets']:
            self.assertEqual(presets.fingerprint({'version': 1, 'targets': [t]}), golden['target_hashes'][t['id']])

    def test_inventory_order_does_not_change_hash_or_ids(self):
        first = inventory.compile_inventory(sample())
        source = sample()
        source['assets'].reverse()
        for asset in source['assets']:
            asset['profiles'].reverse()
        self.assertEqual(first, inventory.compile_inventory(source))

    def test_address_change_retains_logical_ids_but_changes_digest(self):
        first = inventory.compile_inventory(sample())
        source = sample()
        source['assets'][0]['address'] = '192.0.2.11'
        changed = inventory.compile_inventory(source)
        self.assertTrue(changed['valid'])
        self.assertEqual([t['id'] for t in first['configuration']['targets']], [t['id'] for t in changed['configuration']['targets']])
        self.assertNotEqual(first['configuration_sha256'], changed['configuration_sha256'])

    def test_tenant_change_changes_all_target_identities(self):
        first = inventory.compile_inventory(sample())
        source = sample()
        source['tenant_id'] = '79000000-0000-4000-8000-000000000002'
        changed = inventory.compile_inventory(source)
        self.assertTrue(changed['valid'])
        self.assertFalse(set(t['id'] for t in first['configuration']['targets']) & set(t['id'] for t in changed['configuration']['targets']))

    def test_defaults_ports_and_explicit_overrides(self):
        source = sample()
        source['defaults'] = {'interval_seconds': 60, 'timeout_seconds': 7, 'exporter_scheme': 'https'}
        source['assets'][0]['profiles'][0] = {'preset_id': 'linux-node', 'port': 19100}
        plan = inventory.compile_inventory(source)
        self.assertTrue(plan['valid'])
        row = next(t for t in plan['configuration']['targets'] if t['preset_id'] == 'linux-node')
        self.assertEqual(row['endpoint'], 'https://192.0.2.10:19100/metrics')
        self.assertEqual(row['interval_seconds'], 60)
        self.assertEqual(row['timeout_seconds'], 7)
        self.assertFalse(row['enabled'])

    def test_duplicate_logical_service_keys_reject_entire_plan(self):
        source = sample()
        source['assets'][0]['profiles'].append({'preset_id': 'tcp-service', 'key': 'ssh', 'port': 23})
        plan = inventory.compile_inventory(source)
        self.assertFalse(plan['valid'])
        self.assertIsNone(plan['configuration'])
        self.assertEqual(plan['diagnostics'][0]['code'], 'DUPLICATE_LOGICAL_SERVICE_KEY')

    def test_equivalent_http_and_tcp_destinations_are_duplicate(self):
        config = inventory.compile_inventory(sample())['configuration']
        for preset_id, first, second in [('http-service', 'https://192.0.2.10', 'https://192.0.2.10:443/'), ('tcp-service', '192.0.2.10:22', '192.0.2.10:00022')]:
            row = copy.deepcopy(next(t for t in config['targets'] if t['preset_id'] == preset_id))
            row['target'] = first
            other = copy.deepcopy(row)
            other.update(id='79000000-0000-4000-8000-000000000099', target=second)
            with self.assertRaises(presets.Invalid):
                presets.validate({'version': 1, 'targets': [row, other]})

    def test_same_destination_with_different_key_is_not_double_polled(self):
        source = sample()
        source['assets'][0]['profiles'].append({'preset_id': 'tcp-service', 'key': 'ssh-copy', 'port': 22})
        plan = inventory.compile_inventory(source)
        self.assertFalse(plan['valid'])
        self.assertEqual(plan['diagnostics'][0]['code'], 'DUPLICATE_MONITORING_DESTINATION')

    def test_errors_accumulate_by_position_without_values_or_partial_plan(self):
        source = sample()
        source['assets'][0]['profiles'] = [{'preset_id': 'secret-value'}, {'preset_id': 'tcp-service', 'port': False}, {'preset_id': 'http-service', 'scheme': 'https', 'path': '/?password=secret-value'}]
        plan = inventory.compile_inventory(source)
        self.assertFalse(plan['valid'])
        self.assertEqual([d['profile_index'] for d in plan['diagnostics']], [1, 2, 3])
        self.assertIsNone(plan['configuration'])
        self.assertNotIn('secret-value', json.dumps(plan))

    def test_unknown_fields_and_credentials_are_never_accepted(self):
        for mutate in [lambda s: s.update(password='secret'), lambda s: s['defaults'].update(enabled=True), lambda s: s['assets'][0].update(password='secret'), lambda s: s['collectors']['snmp'].update(community='secret')]:
            source = sample()
            mutate(source)
            with self.subTest(source=str(type(source))):
                self.assertFalse(inventory.compile_inventory(source)['valid'])

    def test_root_and_default_types_fail_closed(self):
        for root in [None, [], {}, {'version': True, 'tenant_id': EXAMPLE['tenant_id'], 'assets': EXAMPLE['assets']}]:
            self.assertFalse(inventory.compile_inventory(root)['valid'])
        for defaults in [None, [], {'interval_seconds': True}, {'interval_seconds': 4}, {'timeout_seconds': 0}, {'interval_seconds': 5, 'timeout_seconds': 5}, {'exporter_scheme': []}]:
            source = sample()
            source['defaults'] = defaults
            self.assertFalse(inventory.compile_inventory(source)['valid'])

    def test_collector_failures_and_wrong_types_are_diagnostics(self):
        for collectors in [None, [], {}, {'blackbox': {'kind': 'bad', 'endpoint': 'secret'}}, {'blackbox': {'kind': [], 'endpoint': 'secret'}}, {'blackbox': {'kind': 'blackbox', 'endpoint': 'http://localhost:9115/probe'}}, {'blackbox': {'kind': 'blackbox', 'endpoint': 'http://198.51.100.10:9115/probe', 'auth_profile': 'secret'}}]:
            source = sample()
            source['collectors'] = collectors
            self.assertFalse(inventory.compile_inventory(source)['valid'])

    def test_collector_kind_and_missing_snmp_reference(self):
        source = sample()
        source['assets'][0]['profiles'] = [{'preset_id': 'icmp-device', 'collector': 'snmp'}]
        self.assertEqual(inventory.compile_inventory(source)['diagnostics'][0]['code'], 'COLLECTOR_KIND_MISMATCH')
        source = sample()
        del source['collectors']['snmp']['auth_profile']
        self.assertFalse(inventory.compile_inventory(source)['valid'])

    def test_asset_identity_and_ipv4_bounds(self):
        for address in ['hostname.example', '0.0.0.0', '169.254.169.254', '224.0.0.1', '::1', '192.000.2.1', [], None]:
            source = sample()
            source['assets'][0]['address'] = address
            self.assertFalse(inventory.compile_inventory(source)['valid'])
        source = sample()
        source['assets'].append(copy.deepcopy(source['assets'][0]))
        self.assertFalse(inventory.compile_inventory(source)['valid'])

    def test_profiles_require_applicable_fields_and_safe_paths(self):
        for profile in [None, [], {'preset_id': []}, {'preset_id': 'linux-node', 'path': '/secret'}, {'preset_id': 'http-service'}, {'preset_id': 'http-service', 'scheme': 'https', 'path': '/中文'}, {'preset_id': 'http-service', 'scheme': 'https', 'path': '/bad[uri]'}, {'preset_id': 'http-service', 'scheme': 'https', 'path': '//evil'}, {'preset_id': 'tcp-service', 'port': 65536}, {'preset_id': 'network-snmp', 'auth_profile': 'secret with spaces'}, {'preset_id': 'icmp-device', 'port': 80}]:
            source = sample()
            source['assets'][0]['profiles'] = [profile]
            self.assertFalse(inventory.compile_inventory(source)['valid'])

    def test_64_target_bound_no_silent_chunk_or_truncation(self):
        source = sample()
        original = source['assets'][0]
        source['assets'] = []
        for i in range(64):
            asset = copy.deepcopy(original)
            asset['asset_id'] = f'79000000-0000-4000-8000-{i:012d}'
            asset['profiles'] = ['linux-node']
            source['assets'].append(asset)
        self.assertEqual(inventory.compile_inventory(source)['target_count'], 64)
        source['assets'][0]['profiles'].append('postgresql')
        self.assertEqual(inventory.compile_inventory(source)['diagnostics'][0]['code'], 'TARGET_COUNT')
        source['assets'].append(copy.deepcopy(original))
        self.assertFalse(inventory.compile_inventory(source)['valid'])

    def test_diff_has_no_changes_for_reordered_inventory(self):
        plan = inventory.compile_inventory(sample())
        diff = inventory.compare_configurations(plan['configuration'], plan['configuration'])
        self.assertEqual(diff['unchanged_count'], 15)
        self.assertEqual(diff['added_target_ids'], [])
        self.assertEqual(diff['removed_target_ids'], [])
        self.assertEqual(diff['changed_targets'], [])
        self.assertFalse(diff['applied'])

    def test_diff_preserves_identity_for_address_change_and_never_deletes(self):
        first = inventory.compile_inventory(sample())
        source = sample()
        source['assets'][0]['address'] = '192.0.2.11'
        source['assets'].pop()
        changed = inventory.compile_inventory(source)
        diff = inventory.compare_configurations(first['configuration'], changed['configuration'])
        self.assertEqual(len(diff['changed_targets']), 11)
        self.assertEqual(len(diff['removed_target_ids']), 2)
        self.assertEqual(diff['added_target_ids'], [])
        self.assertEqual(diff['unchanged_count'], 2)
        self.assertTrue(diff['removal_requires_review'])
        self.assertNotIn('192.0.2.', json.dumps(diff))
        self.assertFalse(diff['applied'])

    def test_diff_rejects_cross_tenant_previous_configuration(self):
        first = inventory.compile_inventory(sample())
        other = copy.deepcopy(first['configuration'])
        for row in other['targets']:
            row['tenant_id'] = '79000000-0000-4000-8000-000000000002'
        self.assertRaises(presets.Invalid, inventory.compare_configurations, other, first['configuration'])

    def test_cli_change_preview_is_written_without_applying(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'previous.json'
            config.write_text(json.dumps(inventory.compile_inventory(sample())['configuration']))
            dest = Path(directory) / 'bundle'
            with contextlib.redirect_stdout(io.StringIO()) as out:
                code = inventory.main(['--inventory', str(presets.ROOT / 'deploy/examples/monitoring/inventory.disabled.json'), '--previous-config', str(config), '--output-dir', str(dest)])
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(out.getvalue())['diff']['unchanged_count'], 15)
            self.assertEqual(presets.load_json(dest / 'plan.json')['diff']['unchanged_count'], 15)

    def test_plan_is_detached_from_input(self):
        source = sample()
        plan = inventory.compile_inventory(source)
        before = json.dumps(plan, sort_keys=True)
        source['assets'][0]['address'] = '192.0.2.99'
        source['collectors']['blackbox']['endpoint'] = 'secret'
        self.assertEqual(before, json.dumps(plan, sort_keys=True))

    def test_bundle_is_disabled_and_allowlist_is_only_proposal(self):
        plan = inventory.compile_inventory(sample())
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / 'bundle'
            inventory.write_bundle(plan, dest)
            self.assertEqual(presets.load_json(dest / 'targets.disabled.json'), plan['configuration'])
            self.assertFalse(presets.load_json(dest / 'allowlist.review.json')['approved'])
            # Execute only the generated disabled environment example, never network commands.
            command = '. "$1"; python -c \'import os,json;print(json.dumps({k:v for k,v in os.environ.items() if k.startswith("MOP_MONITORING_PRESET_")}))\''
            result = subprocess.run(['sh', '-c', command, 'sh', str(dest / 'server.env.sh.example')], capture_output=True, text=True, check=True)
            settings = json.loads(result.stdout)
            self.assertEqual(settings['MOP_MONITORING_PRESET_TEST_ENABLED'], 'false')
            self.assertEqual(settings['MOP_MONITORING_PRESET_ALLOWED_ENDPOINTS'], '[]')
            self.assertEqual(settings['MOP_MONITORING_PRESET_ALLOWED_TARGETS'], '[]')
            self.assertEqual(json.loads(settings['MOP_MONITORING_PRESET_TARGETS']), plan['configuration'])
            self.assertTrue((dest / 'plan.json').exists())

    def test_mutated_plan_cannot_publish_stale_hash_or_enabled_target(self):
        with tempfile.TemporaryDirectory() as directory:
            for enabled in [False, True]:
                plan = inventory.compile_inventory(sample())
                plan['configuration']['targets'][0]['interval_seconds'] = 60
                plan['configuration']['targets'][0]['enabled'] = enabled
                if enabled:
                    plan['configuration_sha256'] = presets.fingerprint(plan['configuration'])
                dest = Path(directory) / ('enabled' if enabled else 'changed')
                self.assertRaises(presets.Invalid, inventory.write_bundle, plan, dest)
                self.assertFalse(dest.exists())

    def test_existing_directory_and_symlink_are_never_overwritten(self):
        plan = inventory.compile_inventory(sample())
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / 'existing'
            dest.mkdir()
            marker = dest / 'mine'
            marker.write_text('keep')
            self.assertRaises(presets.Invalid, inventory.write_bundle, plan, dest)
            link = Path(directory) / 'link'
            link.symlink_to(dest)
            self.assertRaises(presets.Invalid, inventory.write_bundle, plan, link)
            dangling = Path(directory) / 'dangling'
            dangling.symlink_to(Path(directory) / 'absent')
            self.assertRaises(presets.Invalid, inventory.write_bundle, plan, dangling)
            self.assertEqual(marker.read_text(), 'keep')

    def test_shell_metacharacters_are_data_not_commands(self):
        source = sample()
        source['assets'][0]['profiles'] = [{'preset_id': 'http-service', 'scheme': 'https', 'path': "/$(touch-MOP_UNSAFE)'"}]
        plan = inventory.compile_inventory(source)
        self.assertTrue(plan['valid'])
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / 'bundle'
            inventory.write_bundle(plan, dest)
            result = subprocess.run(['sh', '-c', '. "$1"; printf %s "$MOP_MONITORING_PRESET_TARGETS"', 'sh', str(dest / 'server.env.sh.example')], capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(result.stdout), plan['configuration'])
            self.assertEqual(result.stderr, '')

    def test_invalid_cli_creates_no_directory_and_echoes_no_sensitive_values(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / 'bad.json'
            source.write_text('{"version":1,"version":2,"secret":"never-echo"}')
            dest = Path(directory) / 'bundle'
            with contextlib.redirect_stdout(io.StringIO()) as out:
                code = inventory.main(['--inventory', str(source), '--output-dir', str(dest)])
            self.assertEqual(code, 2)
            self.assertFalse(dest.exists())
            self.assertNotIn('never-echo', out.getvalue())
            self.assertFalse(json.loads(out.getvalue())['valid'])

    def test_success_cli_reports_no_addresses_and_writes_bundle(self):
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / 'bundle'
            with contextlib.redirect_stdout(io.StringIO()) as out:
                code = inventory.main(['--inventory', str(presets.ROOT / 'deploy/examples/monitoring/inventory.disabled.json'), '--output-dir', str(dest)])
            self.assertEqual(code, 0)
            self.assertNotIn('192.0.2.', out.getvalue())
            self.assertEqual(json.loads(out.getvalue())['target_count'], 15)
            self.assertTrue((dest / 'plan.json').exists())


if __name__ == '__main__':
    unittest.main()

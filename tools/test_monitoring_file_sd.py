import contextlib
import copy
import io
import json
import socket
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import monitoring_file_sd as sd
import monitoring_inventory as inventory
import monitoring_presets as presets


def configuration(enabled=True):
    source = presets.load_json(presets.ROOT / 'deploy/examples/monitoring/inventory.disabled.json')
    result = inventory.compile_inventory(source)['configuration']
    if enabled:
        for target in result['targets']:
            target.update(enabled=True, interval_seconds=30)
    return result


def one():
    return {'version': 1, 'targets': [next(t for t in configuration()['targets'] if t['preset_id'] == 'linux-node')]}


class FileDiscovery(unittest.TestCase):
    def test_all_presets_render_to_grouped_file_sd_without_network(self):
        with patch.object(socket, 'create_connection', side_effect=AssertionError('network')), patch.object(socket, 'getaddrinfo', side_effect=AssertionError('DNS')):
            bundle = sd.render(configuration(), 5)
        self.assertEqual(bundle['enabled_prometheus_target_count'], 12)
        self.assertEqual(bundle['job_count'], 10)
        self.assertEqual(len(bundle['zabbix_handoffs']), 3)
        self.assertFalse(bundle['network_accessed'])
        self.assertFalse(bundle['applied'])
        for job in bundle['prometheus']['scrape_configs']:
            self.assertNotIn('static_configs', job)
            self.assertEqual(job['file_sd_configs'][0]['refresh_interval'], '5s')
            self.assertIn(job['file_sd_configs'][0]['files'][0], bundle['file_sd_files'])
            self.assertFalse(job['honor_labels'])
            self.assertFalse(job['honor_timestamps'])
            self.assertFalse(job['follow_redirects'])

    def test_disabled_targets_never_become_discovery_entries(self):
        bundle = sd.render(configuration(False), 5)
        self.assertEqual(bundle['enabled_prometheus_target_count'], 0)
        self.assertEqual(bundle['prometheus']['scrape_configs'], [])
        self.assertEqual(bundle['file_sd_files'], {})

    def test_input_and_order_are_detached_and_deterministic(self):
        source = configuration()
        bundle = sd.render(source, 5)
        source['targets'].reverse()
        reordered = sd.render(source, 5)
        self.assertNotEqual(bundle['configuration_sha256'], reordered['configuration_sha256'])
        self.assertEqual({k: v for k, v in bundle.items() if k != 'configuration_sha256'}, {k: v for k, v in reordered.items() if k != 'configuration_sha256'})
        source['targets'][0]['endpoint'] = 'secret'
        self.assertNotIn('secret', json.dumps(bundle))

    def test_new_machine_same_profile_changes_only_target_file(self):
        source = one()
        before = sd.render(source, 5)
        second = copy.deepcopy(source['targets'][0])
        second.update(id='79000000-0000-4000-8000-000000000099', asset_id='79000000-0000-4000-8000-000000000098', endpoint='http://192.0.2.99:9100/metrics')
        source['targets'].append(second)
        after = sd.render(source, 5)
        change = sd.changes(before, after)
        self.assertFalse(change['configuration_reload_required'])
        self.assertEqual(len(change['updated_files']), 1)
        self.assertEqual(change['added_files'], [])
        self.assertEqual(after['enabled_prometheus_target_count'], 2)

    def test_address_change_does_not_change_scrape_layout(self):
        source = one()
        before = sd.render(source, 5)
        source['targets'][0]['endpoint'] = 'http://192.0.2.99:19100/metrics'
        after = sd.render(source, 5)
        self.assertFalse(sd.changes(before, after)['configuration_reload_required'])
        self.assertEqual(len(sd.changes(before, after)['updated_files']), 1)

    def test_changed_period_scheme_profile_or_refresh_requires_reload(self):
        for update in [{'interval_seconds': 60}, {'endpoint': 'https://192.0.2.10:9100/metrics'}, {'preset_id': 'postgresql', 'endpoint': 'http://192.0.2.10:9187/metrics'}]:
            source = one()
            before = sd.render(source, 5)
            source['targets'][0].update(update)
            self.assertTrue(sd.changes(before, sd.render(source, 5))['configuration_reload_required'])
        self.assertTrue(sd.changes(sd.render(one(), 5), sd.render(one(), 10))['configuration_reload_required'])

    def test_tenants_never_share_a_discovery_job(self):
        source = one()
        second = copy.deepcopy(source['targets'][0])
        second.update(id='79000000-0000-4000-8000-000000000099', tenant_id='79000000-0000-4000-8000-000000000002')
        source['targets'].append(second)
        bundle = sd.render(source, 5)
        self.assertEqual(bundle['job_count'], 2)
        for entries in bundle['file_sd_files'].values():
            self.assertEqual(len({e['labels']['mop_tenant_id'] for e in entries}), 1)

    def test_presets_with_same_transport_still_get_separate_jobs(self):
        source = one()
        second = copy.deepcopy(source['targets'][0])
        second.update(id='79000000-0000-4000-8000-000000000099', preset_id='windows-exporter', endpoint='http://192.0.2.10:9182/metrics')
        source['targets'].append(second)
        self.assertEqual(sd.render(source, 5)['job_count'], 2)

    def test_blackbox_targets_and_relay_are_not_interchanged(self):
        bundle = sd.render(configuration(), 5)
        job = next(j for j in bundle['prometheus']['scrape_configs'] if j.get('params', {}).get('module') == ['mop_http'])
        self.assertEqual(job['relabel_configs'][-1]['replacement'], '198.51.100.10:9115')
        rows = bundle['file_sd_files'][job['file_sd_configs'][0]['files'][0]]
        self.assertEqual({row['targets'][0] for row in rows}, {'https://192.0.2.10:443/', 'https://192.0.2.10:443/health'})
        self.assertEqual(job['params']['module'], ['mop_http'])

    def test_different_snmp_auth_references_require_separate_jobs(self):
        source = {'version': 1, 'targets': [next(t for t in configuration()['targets'] if t['preset_id'] == 'network-snmp')]}
        second = copy.deepcopy(source['targets'][0])
        second.update(id='79000000-0000-4000-8000-000000000099', asset_id='79000000-0000-4000-8000-000000000098', target='192.0.2.99', auth_profile='another_profile')
        source['targets'].append(second)
        bundle = sd.render(source, 5)
        self.assertEqual(bundle['job_count'], 2)
        self.assertEqual({j['params']['auth'][0] for j in bundle['prometheus']['scrape_configs']}, {'another_profile', 'replace_with_configured_snmp_profile'})

    def test_refresh_is_explicit_and_bounded(self):
        for value in [None, True, 0, 301, '5']:
            self.assertRaises(presets.Invalid, sd.render, one(), value)

    def test_file_bundle_writes_exact_json_and_does_not_overwrite(self):
        bundle = sd.render(configuration(), 5)
        with tempfile.TemporaryDirectory() as directory:
            dest = Path(directory) / 'bundle'
            sd.write_bundle(bundle, dest)
            self.assertEqual(presets.load_json(dest / 'prometheus.json'), bundle['prometheus'])
            for name, entries in bundle['file_sd_files'].items():
                self.assertEqual(presets.load_json(dest / name), entries)
            self.assertTrue((dest / 'manifest.json').exists())
            self.assertRaises(presets.Invalid, sd.write_bundle, bundle, dest)
            link = Path(directory) / 'link'
            link.symlink_to(dest)
            self.assertRaises(presets.Invalid, sd.write_bundle, bundle, link)

    def test_tampered_bundle_and_paths_fail_before_directory_creation(self):
        with tempfile.TemporaryDirectory() as directory:
            bundle = sd.render(one(), 5)
            bundle['prometheus']['scrape_configs'].clear()
            dest = Path(directory) / 'bad'
            self.assertRaises(presets.Invalid, sd.write_bundle, bundle, dest)
            self.assertFalse(dest.exists())
            bundle = sd.render(one(), 5)
            entries = next(iter(bundle['file_sd_files'].values()))
            bundle['file_sd_files'] = {'../outside.json': entries}
            bundle['file_sd_sha256'] = {'../outside.json': sd.digest(entries)}
            self.assertRaises(presets.Invalid, sd.write_bundle, bundle, dest)
            self.assertFalse(dest.exists())

    def test_missing_official_tool_is_not_a_pass(self):
        for executable in [None, 'promtool', '/definitely/missing/promtool']:
            result = sd.official_check(one(), 5, executable)
            self.assertEqual(result['official_config_check'], 'NOT_RUN')
            self.assertFalse(result['target_network_tested'])

    def test_official_check_uses_fixed_arguments_no_shell_and_no_raw_logs(self):
        with patch.object(subprocess, 'run', return_value=subprocess.CompletedProcess([], 0)) as run:
            result = sd.official_check(one(), 5, sys.executable)
        self.assertEqual(result['official_config_check'], 'PASSED')
        args, kwargs = run.call_args
        self.assertEqual(args[0][:4], [sys.executable, 'check', 'config', '--lint-fatal'])
        self.assertNotIn('shell', kwargs)
        self.assertEqual(kwargs['timeout'], 15)
        self.assertEqual(kwargs['stdout'], subprocess.DEVNULL)
        self.assertFalse(Path(args[0][-1]).exists())

    def test_tool_rejection_timeout_and_start_failure_remain_distinct(self):
        for returned, error, expected in [(subprocess.CompletedProcess([], 1), None, 'PROMTOOL_REJECTED_CONFIG'), (None, subprocess.TimeoutExpired('private', 15), 'PROMTOOL_TIMEOUT'), (None, OSError('private'), 'PROMTOOL_START_FAILED')]:
            with patch.object(subprocess, 'run', return_value=returned, side_effect=error):
                result = sd.official_check(one(), 5, sys.executable)
            self.assertEqual(result['reason'], expected)
            self.assertNotIn('private', json.dumps(result))

    def test_cli_check_missing_dependency_and_render_mode_are_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps(one()))
            with contextlib.redirect_stdout(io.StringIO()) as out:
                result = sd.main(['check', '--config', str(config), '--refresh-seconds', '5'])
            self.assertEqual(result, 2)
            self.assertEqual(json.loads(out.getvalue())['official_config_check'], 'NOT_RUN')
            with contextlib.redirect_stdout(io.StringIO()) as out:
                result = sd.main(['render', '--config', str(config), '--refresh-seconds', '5'])
            self.assertEqual(result, 0)
            self.assertNotIn('192.0.2.', out.getvalue())

    def test_comparison_requires_previous_refresh_and_detects_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps(one()))
            base = ['render', '--config', str(config), '--refresh-seconds', '5', '--previous-config', str(config)]
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(sd.main(base), 2)
            self.assertIn('PREVIOUS_CONFIG_AND_REFRESH_REQUIRED_TOGETHER', out.getvalue())
            with contextlib.redirect_stdout(io.StringIO()) as out:
                self.assertEqual(sd.main(base + ['--previous-refresh-seconds', '10']), 0)
            self.assertTrue(json.loads(out.getvalue())['changes']['configuration_reload_required'])


if __name__ == '__main__':
    unittest.main()

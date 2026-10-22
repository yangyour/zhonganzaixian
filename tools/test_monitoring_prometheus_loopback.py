"""Opt-in official Prometheus integration, using only temporary loopback fixtures.

Set MOP_PROMETHEUS_BIN to a reviewed official local binary. No database, business
namespace, external device, browser, production config or network discovery is used.
"""
import copy
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request

import monitoring_file_sd as sd

TENANT = '79000000-0000-4000-8000-000000000001'
FIRST = '79000000-0000-4000-8000-000000000011'
SECOND = '79000000-0000-4000-8000-000000000012'


def exporter():
    state = {'hits': 0, 'fail': False}
    lock = threading.Lock()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != '/metrics':
                self.send_error(404)
                return
            with lock:
                state['hits'] += 1
                value, fail = state['hits'], state['fail']
            # Deliberate untrusted label collision: target labels must remain trusted.
            body = (f'node_cpu_seconds_total{{cpu="0",mop_tenant_id="spoofed",mop_asset_id="spoofed",mop_target_id="spoofed"}} {value}\n').encode()
            self.send_response(503 if fail else 200)
            self.send_header('Content-Type', 'text/plain; version=0.0.4; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, thread, state, lock


def target(identifier, port):
    return {'id': identifier, 'tenant_id': TENANT, 'asset_id': identifier,
            'preset_id': 'linux-node', 'enabled': True, 'endpoint': f'http://127.0.0.1:{port}/metrics',
            'target': None, 'auth_profile': None, 'interval_seconds': 5, 'timeout_seconds': 1}


@unittest.skipUnless(os.environ.get('MOP_PROMETHEUS_BIN'), 'official Prometheus binary not explicitly configured')
class PrometheusLoopback(unittest.TestCase):
    def test_continuous_scrapes_dynamic_target_and_failure_recovery(self):
        binary = Path(os.environ['MOP_PROMETHEUS_BIN'])
        self.assertTrue(binary.is_absolute() and binary.is_file())
        process, servers = None, []
        report = {'scope': 'LOCAL_SYNTHETIC_PROMETHEUS_ONLY', 'completed': False,
                  'real_device_integration': False, 'business_database_accessed': False,
                  'production_applied': False, 'browser_executed': False}
        with tempfile.TemporaryDirectory(prefix='mop-prometheus-loopback-') as directory:
            root = Path(directory)
            with (root / 'prometheus.log').open('w') as log:
                try:
                    first = exporter()
                    servers.append(first)
                    second = exporter()
                    servers.append(second)
                    config = {'version': 1, 'targets': [target(FIRST, first[0].server_port)]}
                    initial = sd.render(config, 1)
                    checked = sd.official_check(config, 1, str(binary.with_name('promtool')))
                    self.assertEqual(checked['official_config_check'], 'PASSED')
                    self.assertEqual(first[2]['hits'] + second[2]['hits'], 0)
                    report['official_config_check'] = 'PASSED'
                    destination = root / 'candidate'
                    sd.write_bundle(initial, destination)
                    with socket.socket() as reservation:
                        reservation.bind(('127.0.0.1', 0))
                        port = reservation.getsockname()[1]
                    process = subprocess.Popen([str(binary), '--config.file=' + str(destination / 'prometheus.json'),
                                                '--web.listen-address=127.0.0.1:' + str(port),
                                                '--storage.tsdb.path=' + str(root / 'tsdb'),
                                                '--storage.tsdb.retention.time=1h', '--storage.tsdb.retention.size=32MB'],
                                               stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
                    pid = process.pid
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

                    def get(path):
                        with opener.open(f'http://127.0.0.1:{port}{path}', timeout=2) as response:
                            raw = response.read(2 * 1024 * 1024 + 1)
                            self.assertLessEqual(len(raw), 2 * 1024 * 1024)
                            return raw

                    def until(callback, timeout=20):
                        deadline = time.monotonic() + timeout
                        while time.monotonic() < deadline:
                            self.assertIsNone(process.poll(), 'local Prometheus exited')
                            try:
                                value = callback()
                                if value:
                                    return value
                            except (urllib.error.URLError, TimeoutError):
                                pass
                            time.sleep(.2)
                        self.fail('bounded local collector observation timed out')

                    def query(expression):
                        body = json.loads(get('/api/v1/query?' + urllib.parse.urlencode({'query': expression})))
                        self.assertEqual(body['status'], 'success')
                        return body['data']['result']

                    def value(identifier, name='up'):
                        rows = query(name + '{mop_target_id="' + identifier + '"}')
                        if not rows:
                            return None
                        self.assertEqual(len(rows), 1)
                        return rows[0]

                    until(lambda: get('/-/ready'))
                    one = until(lambda: value(FIRST, 'node_cpu_seconds_total'))
                    first_value = float(one['value'][1])
                    until(lambda: (row if row and float(row['value'][1]) > first_value else None)
                          if (row := value(FIRST, 'node_cpu_seconds_total')) else None)
                    self.assertEqual(one['metric']['mop_tenant_id'], TENANT)
                    self.assertEqual(one['metric']['mop_asset_id'], FIRST)
                    self.assertEqual(one['metric']['mop_target_id'], FIRST)
                    self.assertEqual(one['metric']['exported_mop_tenant_id'], 'spoofed')
                    self.assertEqual(one['metric']['exported_mop_asset_id'], 'spoofed')
                    self.assertEqual(one['metric']['exported_mop_target_id'], 'spoofed')
                    report['repeated_samples_observed'] = True
                    report['untrusted_label_override_prevented'] = True

                    config['targets'].append(target(SECOND, second[0].server_port))
                    expanded = sd.render(config, 1)
                    delta = sd.changes(initial, expanded)
                    self.assertFalse(delta['configuration_reload_required'])
                    self.assertEqual(len(delta['updated_files']), 1)
                    relative = delta['updated_files'][0]
                    pending = destination / (relative + '.pending')
                    pending.write_text(json.dumps(expanded['file_sd_files'][relative]))
                    os.replace(pending, destination / relative)  # Own temporary test file only.
                    until(lambda: (row if row and row['value'][1] == '1' else None)
                          if (row := value(SECOND)) else None)
                    self.assertEqual(process.pid, pid)
                    self.assertIsNone(process.poll())
                    report['second_target_discovered_without_restart_or_reload'] = True

                    with first[3]:
                        first[2]['fail'] = True
                    until(lambda: (row if row and row['value'][1] == '0' else None)
                          if (row := value(FIRST)) else None)
                    with first[3]:
                        first[2]['fail'] = False
                    until(lambda: (row if row and row['value'][1] == '1' else None)
                          if (row := value(FIRST)) else None)
                    report['scrape_failure_and_recovery_observed'] = True
                    report['exporter_scrape_counts'] = [first[2]['hits'], second[2]['hits']]
                    report['completed'] = True
                finally:
                    if process is not None and process.poll() is None:
                        process.terminate()
                        try:
                            process.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            process.kill()
                            process.wait(timeout=5)
                    report['collector_stopped'] = process is None or process.poll() is not None
                    for server, thread, _, _ in servers:
                        server.shutdown()
                        server.server_close()
                        thread.join(timeout=2)
                    report['exporters_stopped'] = all(not thread.is_alive() for _, thread, _, _ in servers)
            report['temporary_storage_removed'] = False
        report['temporary_storage_removed'] = not root.exists()
        output = os.environ.get('MOP_MONITORING_LOOPBACK_REPORT')
        if output:
            Path(output).write_text(json.dumps(report, indent=2) + '\n')
        self.assertTrue(report['completed'])
        self.assertTrue(report['collector_stopped'] and report['exporters_stopped'] and report['temporary_storage_removed'])


if __name__ == '__main__':
    unittest.main()

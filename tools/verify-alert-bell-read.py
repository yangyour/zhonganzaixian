"""Verify the real bell reader on existing isolated DEMO tenants; never seeds data or changes components."""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import select
import socketserver
import subprocess
import threading
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--private-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    private = args.private_directory.resolve()
    if private.name != 'private' or private.parent.name != 'deploy':
        raise ValueError('Expected ignored private configuration directory')
    if subprocess.run(['git', 'check-ignore', '--quiet', str(private / 'demo-full-20261023.json')], cwd=private.parent.parent).returncode:
        raise ValueError('Private input must remain ignored')
    output = args.output.resolve()
    if not output.is_relative_to((ROOT / '.local').resolve()):
        raise ValueError('Evidence staging must stay in the ignored local directory')
    output.mkdir(parents=True, exist_ok=False)
    demo = json.loads((private / 'demo-full-20261023.json').read_text(encoding='utf-8-sig'))
    runtime = json.loads((private / 'lan-test-runtime.json').read_text(encoding='utf-8-sig'))
    if demo.get('origin') != 'http://mop.all-online.com.cn' or set(demo['contexts']) != {'padmin', 'msp', 'tadmin'}:
        raise ValueError('Unverified demo scope')
    spec = importlib.util.spec_from_file_location('bell_read_connection', ROOT / 'tools/update-lan-test-server.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.ROOT = private.parent.parent
    server = None
    tunnel = None
    report = {'status': 'NOT_RUN', 'environment': 'lan-test-20261008', 'business_writes': 0,
              'schema_migrations': 0, 'new_components': 0, 'external_messages': 0,
              'scope': 'EXISTING_DEMO_READ_ONLY', 'credentials_recorded': False}
    try:
        server = module.Server()
        item = json.loads(server.run(['docker', 'inspect', 'mop-lan-postgres'], True))[0]
        if item['Config']['Labels'].get('mop.project') != 'mop-lan-test' or not item['State']['Running']:
            raise ValueError('Designated PostgreSQL ownership/state mismatch')
        networks = list(item['NetworkSettings']['Networks'].values())
        if len(networks) != 1 or not networks[0]['IPAddress']:
            raise ValueError('Ambiguous database route')
        destination = (networks[0]['IPAddress'], 5432)
        report['postgresql'] = server.run(['docker', 'exec', 'mop-lan-postgres', 'psql', '-U', 'postgres',
                                         '-d', 'mop_test', '-At', '-c', 'SHOW server_version;'], True).strip()
        transport = server.client.get_transport()

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                channel = transport.open_channel('direct-tcpip', destination, self.request.getpeername(), timeout=15)
                try:
                    while True:
                        readable, _, _ = select.select([self.request, channel], [], [], 30)
                        for source in readable:
                            data = source.recv(65536)
                            if not data:
                                return
                            (channel if source is self.request else self.request).sendall(data)
                finally:
                    channel.close()

        class Tunnel(socketserver.ThreadingTCPServer):
            allow_reuse_address = False
            daemon_threads = True

        tunnel = Tunnel(('127.0.0.1', 0), Handler)
        threading.Thread(target=tunnel.serve_forever, daemon=True).start()
        env = {key: value for key, value in os.environ.items() if not key.startswith(('MOP_', 'SPRING_', 'OBJECT_STORAGE_'))}
        env.update({'MOP_RUN_ALERT_BELL_READ_TESTS': 'true', 'MOP_ALERT_BELL_TEST_SCOPE': 'EXISTING_DEMO_READ_ONLY',
                    'MOP_ALERT_BELL_TEST_JDBC': f'jdbc:postgresql://127.0.0.1:{tunnel.server_address[1]}/mop_test',
                    'MOP_ALERT_BELL_RUNTIME_PASSWORD': runtime['runtime_password'],
                    'MOP_ALERT_BELL_TEST_ACTOR': demo['users']['main']['id'],
                    'MOP_ALERT_BELL_ADMIN_TENANT': demo['contexts']['padmin']['tenant_id'],
                    'MOP_ALERT_BELL_ENGINEER_TENANT': demo['contexts']['msp']['tenant_id'],
                    'MOP_ALERT_BELL_CUSTOMER_TENANT': demo['contexts']['tadmin']['tenant_id'], 'PYTHONUTF8': '1'})
        with (output / 'maven.log').open('wb') as log:
            result = subprocess.run(['mvn.cmd', '-B', '-ntp', '-pl', 'server/persistence', '-am',
                                     '-Dtest=PostgresDashboardAlertBellReadTest', '-Dsurefire.failIfNoSpecifiedTests=false', 'test'],
                                    cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        report['maven_exit_code'] = result.returncode
        xml = ROOT / 'server/persistence/target/surefire-reports/TEST-cn.mop.persistence.PostgresDashboardAlertBellReadTest.xml'
        if xml.exists():
            result_xml = ET.parse(xml).getroot()
            report.update({key: int(result_xml.get(key, 0)) for key in ('tests', 'failures', 'errors', 'skipped')})
        report['status'] = 'PASSED' if result.returncode == 0 and report.get('tests') == 3 and not any(report.get(key, 1) for key in ('failures', 'errors', 'skipped')) else 'FAILED'
        paths = ['server/domain/src/main/java/cn/mop/domain/dashboard/DashboardAlertBellReader.java',
                 'server/persistence/src/main/java/cn/mop/persistence/PostgresDashboardAlertBellReader.java']
        report['source_sha256'] = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in paths}
    except Exception as error:
        report.update(status='FAILED', exception_type=type(error).__name__, details='withheld')
    finally:
        if tunnel:
            tunnel.shutdown()
            tunnel.server_close()
        if server:
            server.client.close()
        report['verified_at'] = datetime.now(timezone.utc).isoformat()
        (output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report))
    return 0 if report['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())

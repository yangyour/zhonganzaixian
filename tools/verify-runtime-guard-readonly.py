"""Exercise the Java runtime privilege guard against designated PostgreSQL through a pinned owned SSH tunnel. Read-only."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import threading
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OWNER = Path('E:/work/wxw9/zhonganzaixian/mop-platform')


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(output):
    output = Path(output)
    if output.exists():
        raise ValueError('Evidence already exists')
    work = ROOT / '.local' / ('runtime-guard-' + uuid.uuid4().hex)
    work.mkdir(parents=True)
    summary = {'environment': 'lan-test-20261008', 'scope': 'DESIGNATED_READ_ONLY_RUNTIME_PRIVILEGES',
               'status': 'NOT_RUN', 'database_writes': 0, 'ddl_executed': 0, 'new_components': 0,
               'production_database_approved': False, 'formal_contract_acceptance': 'NOT_RUN'}
    server = None
    tunnel = None
    try:
        runtime = json.loads((OWNER / 'deploy/private/lan-test-runtime.json').read_text(encoding='utf-8-sig'))
        module = load(ROOT / 'tools/update-lan-test-server.py', 'runtime_guard_server')
        module.ROOT = OWNER
        server = module.Server()
        forwarder = load(ROOT / 'tools/wecom-dev-tunnel.py', 'runtime_guard_forwarder')
        tunnel = forwarder.Server(('127.0.0.1', 0), forwarder.handler(server.client.get_transport(), False))
        threading.Thread(target=tunnel.serve_forever, daemon=True).start()
        history_before = server.schema()
        version = server.run(['docker', 'exec', 'mop-lan-postgres', 'psql', '-X', '-qAt', '-U', 'postgres',
                              '-d', 'mop_test', '-c', 'BEGIN READ ONLY; SHOW server_version; COMMIT;'], True).strip()
        summary['postgresql_version'] = version
        env = os.environ.copy()
        env.pop('MOP_RUN_REMOTE_TESTS', None)
        env.update({'SPRING_DATASOURCE_URL': f'jdbc:postgresql://127.0.0.1:{tunnel.server_address[1]}/mop_test',
                    'SPRING_DATASOURCE_USERNAME': 'mop_test_runtime', 'SPRING_DATASOURCE_PASSWORD': runtime['runtime_password'],
                    'MOP_RUN_RUNTIME_GUARD_PG_TESTS': 'true'})
        with (work / 'maven.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(['mvn.cmd', '-B', '-pl', 'server/persistence', '-am', 'test',
                                     '-Dtest=RuntimeDatabaseGuardPostgresReadTest', '-Dsurefire.failIfNoSpecifiedTests=false'],
                                    cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        suite = ET.parse(ROOT / 'server/persistence/target/surefire-reports/TEST-cn.mop.persistence.RuntimeDatabaseGuardPostgresReadTest.xml').getroot()
        counts = {key: int(suite.attrib[key]) for key in ('tests', 'errors', 'failures', 'skipped')}
        summary.update(counts)
        summary['schema_history_unchanged'] = history_before == server.schema()
        summary['source_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        summary['guard_source_sha256'] = hashlib.sha256((ROOT / 'server/persistence/src/main/java/cn/mop/persistence/RuntimeDatabaseGuard.java').read_bytes()).hexdigest()
        summary['factory_source_sha256'] = hashlib.sha256((ROOT / 'server/persistence/src/main/java/cn/mop/persistence/RuntimeDataSources.java').read_bytes()).hexdigest()
        summary['status'] = ('PASSED' if result.returncode == 0 and counts['tests'] > 0
                             and counts['errors'] == counts['failures'] == counts['skipped'] == 0
                             and summary['schema_history_unchanged'] else 'FAILED')
    except Exception as failure:
        summary.update(status='BLOCKED_OR_FAILED', failure_type=type(failure).__name__, details='withheld')
    finally:
        if tunnel is not None:
            tunnel.shutdown()
            tunnel.server_close()
        if server is not None:
            server.client.close()
        summary['recorded_at_utc'] = datetime.now(timezone.utc).isoformat()
        with output.open('x', encoding='utf-8') as destination:
            json.dump(summary, destination, indent=2)
            destination.write('\n')
    print(json.dumps(summary))
    return 0 if summary['status'] == 'PASSED' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    raise SystemExit(run(parser.parse_args().output))

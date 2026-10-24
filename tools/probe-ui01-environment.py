"""Read-only designated PostgreSQL preflight; never prints private connection data."""
from __future__ import annotations

import base64
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import threading

import paramiko
import psycopg

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = Path('E:/work/wxw9/zhonganzaixian/mop-platform/deploy/private')


def probe():
    evidence = {'environment': 'lan-test-20261008', 'status': 'NOT_RUN',
                'business_writes': 0, 'migrations': 0, 'components_started': 0}
    ssh = paramiko.SSHClient()
    forward = None
    try:
        connection = json.loads((PRIVATE / 'lan-test-connection.json').read_text(encoding='utf-8-sig'))
        runtime = json.loads((PRIVATE / 'lan-test-runtime.json').read_text(encoding='utf-8-sig'))
        if connection['environment'] != evidence['environment'] or connection['host'] != '192.168.1.215':
            raise ValueError('Environment mismatch')
        ssh.load_host_keys(str(PRIVATE / 'lan-test-known_hosts'))
        ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
        evidence['stage'] = 'SSH_CONNECT'
        ssh.connect(connection['host'], username=connection['username'], password=connection['password'],
                    look_for_keys=False, allow_agent=False, timeout=15, banner_timeout=15, auth_timeout=15)
        transport = ssh.get_transport()
        fingerprint = base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip('=')
        if fingerprint != connection['expected_host_key_sha256']:
            raise ValueError('Host trust mismatch')
        evidence['host_trust'] = 'PINNED_MATCH'
        evidence['stage'] = 'PROJECT_OWNERSHIP'
        _, stdout, stderr = ssh.exec_command('cat /opt/mop-lan-test/owner.json', timeout=15)
        owner = json.loads(stdout.read())
        stderr.read()
        if stdout.channel.recv_exit_status() or owner.get('project') != 'mop-lan-test' or owner.get('environment') != evidence['environment']:
            raise ValueError('Project mismatch')
        evidence['project_ownership'] = 'MATCH'
        spec = importlib.util.spec_from_file_location('ui01_forward', ROOT / 'tools/wecom-dev-tunnel.py')
        tunnel = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tunnel)
        forward = tunnel.Server(('127.0.0.1', 0), tunnel.handler(transport, False))
        threading.Thread(target=forward.serve_forever, daemon=True).start()
        port = forward.server_address[1]
        evidence['stage'] = 'POSTGRESQL_READ_ONLY'
        for role, key in [('mop_test_runtime', 'runtime_password'), ('postgres', 'postgres_admin_password')]:
            with psycopg.connect(host='127.0.0.1', port=port, dbname='mop_test', user=role,
                                 password=runtime[key], connect_timeout=10, options='-c default_transaction_read_only=on') as db:
                with db.cursor() as cursor:
                    cursor.execute('SELECT current_database(),current_user,current_setting(\'server_version\'),'
                                   '(SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolbypassrls FROM pg_roles WHERE rolname=current_user)')
                    database, principal, version, elevated = cursor.fetchone()
                    if database != 'mop_test' or principal != role or (role == 'mop_test_runtime' and elevated):
                        raise ValueError('Database or runtime privilege mismatch')
                    evidence['postgresql_version'] = version
                    if role == 'mop_test_runtime':
                        evidence['runtime_restricted'] = True
                        cursor.execute('SELECT count(*) FROM public.mop_ticket WHERE false')
                        evidence['ticket_read_available'] = cursor.fetchone()[0] == 0
                    else:
                        cursor.execute('SELECT version,checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank')
                        history = cursor.fetchall()
                        evidence['migration_latest'] = history[-1][0]
                        evidence['migration_count'] = len(history)
                        evidence['history_sha256'] = hashlib.sha256(json.dumps(history).encode()).hexdigest()
        evidence['status'] = 'PASSED'
        evidence['stage'] = 'COMPLETE'
    except Exception as failure:
        evidence.update(status='BLOCKED', failure_type=type(failure).__name__)
    finally:
        if forward:
            forward.shutdown()
            forward.server_close()
        ssh.close()
    evidence['finished_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    output = ROOT / 'docs/evidence/local-astra-ut-20261023/environment-preflight.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence))
    return 0 if evidence['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(probe())

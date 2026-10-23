"""Verify monitoring recurrence on designated PG using fresh isolated tenants; never initialize DEMO.

Requires the parallel environment lock. No DDL, migration, old identity restoration,
local database, login credential, external notification or service lifecycle change.
Only this run's test principal is deactivated; immutable test evidence is retained.
"""
from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import threading
import uuid

import paramiko
import psycopg

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = Path('E:/work/wxw9/zhonganzaixian/mop-platform/deploy/private')
LOCK = Path('E:/work/wxw9/zhonganzaixian/mop-platform/.local/parallel-launch-20261023/environment.lock')
ACTUAL_TENANT = '55000000-0000-4000-8000-000000000001'


def protected_fingerprints(connection):
    result = {}
    for table in ('mop_membership', 'mop_asset', 'mop_ticket', 'mop_ticket_workflow_event',
                  'mop_monitoring_event', 'mop_monitoring_event_member', 'mop_autoticket_attempt',
                  'mop_event_noise_record', 'mop_ticket_workflow_clock', 'mop_ticket_workflow_clock_receipt',
                  'mop_ticket_workflow_outbox', 'mop_notification_intent', 'mop_notification_message'):
        # Fixed internal identifiers only; actual customer data never leaves the digest query.
        with connection.cursor() as cursor:
            cursor.execute('SELECT count(*), md5(COALESCE(string_agg(row_digest, \'\' ORDER BY row_digest),\'\')) '
                           'FROM (SELECT md5(to_jsonb(t)::text) AS row_digest FROM public.'
                           + table + ' t WHERE tenant_id=%s) rows', (ACTUAL_TENANT,))
            count, digest = cursor.fetchone()
            result[table] = {'count': count, 'md5': digest}
    return result


def run(output: Path):
    output.mkdir(parents=True, exist_ok=False)
    owner = {'session': 'monitoring', 'branch': 'codex/launch-monitoring-20261023',
             'operation': 'isolated-monitoring-recurrence-pg', 'owner': str(uuid.uuid4()),
             'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        with LOCK.open('x', encoding='utf-8') as stream:
            json.dump(owner, stream)
    except FileExistsError:
        print(json.dumps({'status': 'LOCK_BUSY', 'business_writes': 0, 'lock_removed': False}))
        return 3
    ssh = paramiko.SSHClient()
    server = None
    admin = None
    seeded = False
    actor = str(uuid.uuid4())
    tenants = [str(uuid.uuid4()), str(uuid.uuid4())]
    assets = [str(uuid.uuid4()), str(uuid.uuid4())]
    summary = {'environment': 'lan-test-20261008', 'schema_migrations': 0,
               'actual_tenant_written': False, 'new_local_components': 0,
               'external_messages': 0, 'test_tenants': tenants, 'test_actor': actor,
               'formal_contract_acceptance': 'NOT_RUN', 'status': 'NOT_RUN'}
    summary['suite'] = 'monitoring-recurrence'
    try:
        cfg = json.loads((PRIVATE / 'lan-test-connection.json').read_text(encoding='utf-8-sig'))
        runtime = json.loads((PRIVATE / 'lan-test-runtime.json').read_text(encoding='utf-8-sig'))
        if cfg['environment'] != 'lan-test-20261008' or cfg['host'] != '192.168.1.215':
            raise ValueError('Unexpected designated environment')
        ssh.load_host_keys(str(PRIVATE / 'lan-test-known_hosts'))
        ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
        ssh.connect(cfg['host'], username=cfg['username'], password=cfg['password'],
                    look_for_keys=False, allow_agent=False, timeout=15)
        transport = ssh.get_transport()
        fingerprint = base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip('=')
        if fingerprint != cfg['expected_host_key_sha256']:
            raise ValueError('Pinned host key mismatch')
        _, stdout, stderr = ssh.exec_command('cat /opt/mop-lan-test/owner.json', timeout=15)
        remote_owner = json.loads(stdout.read())
        stderr.read()
        if stdout.channel.recv_exit_status() or remote_owner.get('project') != 'mop-lan-test' or remote_owner.get('environment') != cfg['environment']:
            raise ValueError('Project ownership mismatch')
        spec = importlib.util.spec_from_file_location('monitoring_tunnel', ROOT / 'tools/wecom-dev-tunnel.py')
        tunnel = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tunnel)
        server = tunnel.Server(('127.0.0.1', 0), tunnel.handler(transport, False))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        port = server.server_address[1]
        admin = psycopg.connect(host='127.0.0.1', port=port, dbname='mop_test', user='postgres',
                               password=runtime['postgres_admin_password'], connect_timeout=10, autocommit=True)
        with admin.cursor() as cursor:
            cursor.execute('SHOW server_version')
            summary['postgresql_version'] = cursor.fetchone()[0]
            cursor.execute('SELECT version,checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank')
            history = cursor.fetchall()
            summary['schema_latest'] = history[-1][0]
            if len(history) < 50:
                raise ValueError('Required report schema unavailable')
        before = protected_fingerprints(admin)
        marker = 'SYNTHETIC_MONITORING_' + actor
        with admin.transaction():
            with admin.cursor() as cursor:
                cursor.execute('INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) '
                               "VALUES(%s,'isolated_monitoring',%s,%s,'padmin')", (actor, marker, marker))
                for index, tenant in enumerate(tenants):
                    cursor.execute('INSERT INTO public.mop_tenant(id,name) VALUES(%s,%s)', (tenant, marker + '_' + str(index)))
                    cursor.execute("INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(%s,%s,'padmin')", (tenant, actor))
                    cursor.execute("INSERT INTO public.mop_site(tenant_id,id,name) VALUES(%s,'monitoring-test','SYNTHETIC_MONITORING_SITE')", (tenant,))
                    cursor.execute('INSERT INTO public.mop_asset(id,tenant_id,name,type,management_ip,site_id,source,created_by,updated_by) '
                                   "VALUES(%s,%s,'SYNTHETIC_MONITORING_ASSET','other','192.0.2.1','monitoring-test','manual',%s,%s)",
                                   (assets[index], tenant, actor, actor))
        seeded = True
        env = {key: value for key, value in os.environ.items() if not key.startswith(('MOP_', 'SPRING_'))}
        env.update({'SPRING_DATASOURCE_URL': f'jdbc:postgresql://127.0.0.1:{port}/mop_test',
                    'SPRING_DATASOURCE_USERNAME': 'mop_test_runtime', 'SPRING_DATASOURCE_PASSWORD': runtime['runtime_password'],
                    'MOP_RUN_MONITORING_RECURRENCE_PG_TESTS': 'true', 'MOP_MONITORING_TENANT': tenants[0],
                    'MOP_MONITORING_FOREIGN_TENANT': tenants[1], 'MOP_MONITORING_ACTOR': actor,
                    'MOP_MONITORING_ASSET': assets[0], 'MOP_MONITORING_FOREIGN_ASSET': assets[1]})
        selected_test = 'PostgresMonitoringRecurrenceIntegrationTest'
        with (output / 'maven.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(['mvn.cmd', '-pl', 'server/persistence', '-am',
                                     '-Dtest=' + selected_test,
                                     '-Dsurefire.failIfNoSpecifiedTests=false', 'test'], cwd=ROOT, env=env,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=600)
        summary['maven_exit_code'] = result.returncode
        after = protected_fingerprints(admin)
        summary['actual_tenant_fingerprints_unchanged'] = before == after
        with admin.cursor() as cursor:
            cursor.execute('SELECT version,checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank')
            summary['migration_history_unchanged'] = history == cursor.fetchall()
        summary['status'] = 'PASSED' if result.returncode == 0 and before == after and summary['migration_history_unchanged'] else 'FAILED'
    except Exception as failure:
        summary.update(status='FAILED', failure_type=type(failure).__name__, details='omitted')
    finally:
        if seeded and admin is not None:
            try:
                with admin.transaction():
                    with admin.cursor() as cursor:
                        cursor.execute("UPDATE public.mop_user SET active=false WHERE id=%s AND identity_source='isolated_monitoring' AND subject=%s", (actor, marker))
                        if cursor.rowcount != 1:
                            raise ValueError('Test actor cleanup scope mismatch')
                        cursor.execute('UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 '
                                       'WHERE user_id=%s AND tenant_id IN (%s,%s)', (actor, *tenants))
                summary['test_principal_deactivated'] = True
            except Exception as failure:
                summary.update(status='FAILED', cleanup_failure_type=type(failure).__name__)
        if admin is not None:
            admin.close()
        if server is not None:
            server.shutdown()
            server.server_close()
        ssh.close()
        summary['finished_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
        if json.loads(LOCK.read_text(encoding='utf-8')) == owner:
            LOCK.unlink()
    print(json.dumps(summary))
    return 0 if summary['status'] == 'PASSED' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.output.resolve()))

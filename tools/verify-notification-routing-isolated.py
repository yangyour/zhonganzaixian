"""Verify notification routing in a new database on the designated PostgreSQL service.

Copies schema and immutable migration/catalog metadata only, never customer rows.
The deployed mop_test is never migrated. Only this run's marked database may be dropped.
Secrets are read from the original ignored private directory and passed through environment variables.
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
import shlex
import subprocess
import threading
import uuid
import paramiko
import psycopg
from psycopg import sql

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = Path('E:/work/wxw9/zhonganzaixian/mop-platform/deploy/private')
COORDINATION = Path('E:/work/wxw9/zhonganzaixian/mop-platform/.local/parallel-launch-20261023')

def run(output: Path) -> int:
    output.mkdir(parents=True, exist_ok=False)
    if not (COORDINATION / 'migration.lock').read_text(encoding='utf-8').startswith('notifications|N01|'):
        raise ValueError('Notification migration lock required')
    owner = {'session': 'notifications', 'operation': 'isolated-routing-v51', 'id': str(uuid.uuid4())}
    lock = COORDINATION / 'environment.lock'
    with lock.open('x', encoding='utf-8') as file:
        json.dump(owner, file)
    name = 'mop_notification_test_' + uuid.uuid4().hex[:12]
    marker = 'MOP_NOTIFICATION_ISOLATION:' + owner['id']
    tenants = [str(uuid.uuid4()), str(uuid.uuid4())]
    actors = [str(uuid.uuid4()), str(uuid.uuid4())]
    summary = {'environment': 'lan-test-20261008', 'isolated_database': name, 'owner_marker': marker, 'schema_source': 'schema_only_clone_of_V50',
               'customer_data_copied': False, 'live_database_migrated': False, 'new_local_components': 0,
               'external_messages': 0, 'status': 'NOT_RUN', 'started_at': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    ssh = paramiko.SSHClient()
    tunnel = source = isolated = admin = None
    created = False
    try:
        cfg = json.loads((PRIVATE / 'lan-test-connection.json').read_text(encoding='utf-8-sig'))
        runtime = json.loads((PRIVATE / 'lan-test-runtime.json').read_text(encoding='utf-8-sig'))
        if cfg['environment'] != 'lan-test-20261008' or cfg['host'] != '192.168.1.215':
            raise ValueError('Unexpected designated destination')
        ssh.load_host_keys(str(PRIVATE / 'lan-test-known_hosts'))
        ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
        ssh.connect(cfg['host'], username=cfg['username'], password=cfg['password'],
                    look_for_keys=False, allow_agent=False, timeout=15)
        transport = ssh.get_transport()
        fingerprint = base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip('=')
        if fingerprint != cfg['expected_host_key_sha256']:
            raise ValueError('Pinned host key mismatch')

        def remote(args, privileged=False):
            command = shlex.join(args)
            if privileged:
                command = 'sudo -S -p '+shlex.quote('')+' -- '+command
            stdin, stdout, stderr = ssh.exec_command(command, timeout=45)
            if privileged:
                stdin.write(cfg['password']+'\n'); stdin.flush()
            stdin.channel.shutdown_write()
            value = stdout.read().decode('utf-8'); stderr.read()
            if stdout.channel.recv_exit_status():
                raise RuntimeError('Designated operation failed; details withheld')
            return value

        remote_owner = json.loads(remote(['cat', '/opt/mop-lan-test/owner.json']))
        if remote_owner.get('project') != 'mop-lan-test' or remote_owner.get('environment') != cfg['environment']:
            raise ValueError('Project ownership mismatch')
        module_spec = importlib.util.spec_from_file_location('notification_tunnel', ROOT / 'tools/wecom-dev-tunnel.py')
        module = importlib.util.module_from_spec(module_spec)
        module_spec.loader.exec_module(module)
        tunnel = module.Server(('127.0.0.1', 0), module.handler(transport, False))
        threading.Thread(target=tunnel.serve_forever, daemon=True).start()
        port = tunnel.server_address[1]
        settings = dict(host='127.0.0.1', port=port, user='postgres', password=runtime['postgres_admin_password'], connect_timeout=10, autocommit=True)
        source = psycopg.connect(dbname='mop_test', **settings)
        admin = psycopg.connect(dbname='postgres', **settings)
        history_columns = 'installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success'
        with source.cursor() as cursor:
            cursor.execute('SHOW server_version'); summary['postgresql_version'] = cursor.fetchone()[0]
            cursor.execute('SELECT '+history_columns+' FROM public.flyway_schema_history ORDER BY installed_rank')
            history = cursor.fetchall()
            if len(history) != 50 or history[-1][1] != '50' or not all(row[-1] for row in history):
                raise ValueError('Reinspect latest migration baseline before cloning')
            cursor.execute("SELECT to_regclass('public.mop_notification_control') IS NULL")
            if not cursor.fetchone()[0]:
                raise ValueError('Live notification schema differs from expected V50 baseline')
            cursor.execute('SELECT base_role,capability FROM public.mop_role_capability_limit ORDER BY base_role,capability')
            capabilities = cursor.fetchall()
        dump = remote(['docker', 'exec', 'mop-lan-postgres', 'pg_dump', '-U', 'postgres', '-d', 'mop_test', '--schema-only'], True)
        # These are psql protocol guards, not SQL or user-provided content. No customer rows are in this dump.
        schema = '\n'.join(line for line in dump.splitlines() if not line.startswith('\\restrict ') and not line.startswith('\\unrestrict '))
        with admin.cursor() as cursor:
            cursor.execute(sql.SQL('CREATE DATABASE {} OWNER mop_test_migrator TEMPLATE template0').format(sql.Identifier(name)))
            created = True
            cursor.execute(sql.SQL('COMMENT ON DATABASE {} IS {}').format(sql.Identifier(name), sql.Literal(marker)))
            cursor.execute(sql.SQL('REVOKE CONNECT ON DATABASE {} FROM PUBLIC').format(sql.Identifier(name)))
            cursor.execute(sql.SQL('GRANT CONNECT ON DATABASE {} TO mop_test_runtime,mop_test_migrator').format(sql.Identifier(name)))
        isolated = psycopg.connect(dbname=name, **settings)
        with isolated.cursor() as cursor:
            cursor.execute('SELECT current_database()')
            if cursor.fetchone()[0] != name:
                raise ValueError('Isolated connection mismatch')
            cursor.execute('DROP SCHEMA public CASCADE')
            if 'CREATE SCHEMA public;' not in schema:
                cursor.execute('CREATE SCHEMA public AUTHORIZATION mop_test_migrator')
            cursor.execute(schema, prepare=False)
            cursor.executemany('INSERT INTO public.flyway_schema_history('+history_columns+') VALUES('+','.join(['%s']*10)+')', history)
            cursor.executemany('INSERT INTO public.mop_role_capability_limit(base_role,capability) VALUES(%s,%s)', capabilities)
        with isolated.transaction():
            with isolated.cursor() as cursor:
                for actor, role in zip(actors, ('padmin', 'viewer')):
                    cursor.execute("INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) VALUES(%s,'isolated_notification_routing',%s,%s,%s)",
                                   (actor, 'SYNTHETIC_NOTIFICATION_'+actor, 'SYNTHETIC_NOTIFICATION_'+role, role))
                for tenant in tenants:
                    cursor.execute('INSERT INTO public.mop_tenant(id,name) VALUES(%s,%s)', (tenant, 'SYNTHETIC_NOTIFICATION_'+tenant))
                    for actor, role in zip(actors, ('padmin', 'viewer')):
                        cursor.execute('INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(%s,%s,%s)', (tenant, actor, role))
        env = os.environ.copy()
        env.update({'SPRING_DATASOURCE_URL': f'jdbc:postgresql://127.0.0.1:{port}/{name}',
                    'SPRING_DATASOURCE_USERNAME': 'mop_test_runtime', 'SPRING_DATASOURCE_PASSWORD': runtime['runtime_password'],
                    'MOP_MIGRATION_PASSWORD': runtime['migrator_password'], 'MOP_NOTIFICATION_ROUTING_PG_TEST': 'isolated-database',
                    'MOP_NOTIFICATION_TEST_TENANT': tenants[0], 'MOP_NOTIFICATION_TEST_FOREIGN_TENANT': tenants[1],
                    'MOP_NOTIFICATION_TEST_ACTOR': actors[0], 'MOP_NOTIFICATION_TEST_RECEIVER': actors[1]})
        with (output / 'maven.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(['mvn.cmd', '-B', '-pl', 'server/worker', '-am', 'test',
                                     '-Dtest=NotificationRoutingPostgresTest', '-Dsurefire.failIfNoSpecifiedTests=false'],
                                    cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=600)
        summary['maven_exit_code'] = result.returncode
        with isolated.cursor() as cursor:
            cursor.execute('SELECT version,checksum,installed_on FROM public.flyway_schema_history WHERE version=\'51\' AND success')
            applied = cursor.fetchone()
            summary['isolated_v51_applied'] = applied is not None
            summary['v51_installed_at'] = str(applied[2]) if applied else None
            summary['v51_flyway_checksum'] = applied[1] if applied else None
        summary['v51_sha256'] = hashlib.sha256((ROOT / 'server/persistence/src/main/resources/db/migration/V51__notification_routing_control.sql').read_bytes()).hexdigest()
        with source.cursor() as cursor:
            cursor.execute('SELECT '+history_columns+' FROM public.flyway_schema_history ORDER BY installed_rank')
            summary['live_history_unchanged'] = history == cursor.fetchall()
            cursor.execute("SELECT to_regclass('public.mop_notification_control') IS NULL")
            summary['live_v51_absent'] = cursor.fetchone()[0]
        summary['status'] = 'PASSED' if result.returncode == 0 and summary['isolated_v51_applied'] and summary['live_history_unchanged'] and summary['live_v51_absent'] else 'FAILED'
    except Exception as failure:
        summary.update(status='FAILED', failure_type=type(failure).__name__, details='private details withheld')
    finally:
        if isolated is not None:
            isolated.close()
        if created and admin is not None:
            try:
                with admin.cursor() as cursor:
                    cursor.execute("SELECT shobj_description(oid,'pg_database'),pg_get_userbyid(datdba) FROM pg_database WHERE datname=%s", (name,))
                    if cursor.fetchone() != (marker, 'mop_test_migrator') or not name.startswith('mop_notification_test_'):
                        raise ValueError('Owned namespace cleanup refused')
                    cursor.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(name)))
                    summary['owned_database_removed'] = True
            except Exception as failure:
                summary.update(status='FAILED', cleanup_failure_type=type(failure).__name__)
        if source is not None:
            source.close()
        if admin is not None:
            admin.close()
        if tunnel is not None:
            tunnel.shutdown(); tunnel.server_close()
        ssh.close()
        summary['finished_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (output / 'summary.json').write_text(json.dumps(summary, indent=2)+'\n', encoding='utf-8')
        if json.loads(lock.read_text(encoding='utf-8')) == owner:
            lock.unlink()
    print(json.dumps(summary))
    return 0 if summary['status'] == 'PASSED' else 1

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    raise SystemExit(run(args.output.resolve()))

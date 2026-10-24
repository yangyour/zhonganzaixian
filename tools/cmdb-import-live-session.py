"""Start a local candidate API against existing designated components and fresh isolated QA tenants.

No migration, DEMO reinitialization, deployment, device operation or external message. New test
identities are disabled on exit; immutable source/assets/audit remain explicitly SYNTHETIC.
Credentials stay in the original project's ignored private directory. A stop sentinel ends only
the candidate process owned by this invocation. Hold environment.lock throughout the session.
"""
from __future__ import annotations
import argparse
import base64
import csv
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import select
import shlex
import socket
import socketserver
import subprocess
import threading
import time
import uuid
import paramiko
import psycopg
import requests
from argon2 import PasswordHasher

ROOT = Path(__file__).resolve().parents[1]
PROTECTED = ['55000000-0000-4000-8000-000000000001', '55000000-0000-4000-8000-000000000002']


def run(private: Path, output: Path, api_port: int, suite: str = 'A01'):
    if suite not in ('A01', 'A05'):
        raise ValueError('Unsupported isolated suite')
    if private.name != 'private' or private.parent.name != 'deploy':
        raise ValueError('Original ignored deploy/private directory required')
    if subprocess.run(['git', 'check-ignore', '--quiet', str(private / 'lan-test-runtime.json')], cwd=private.parent.parent).returncode:
        raise ValueError('Private runtime file must be ignored')
    output.mkdir(parents=True, exist_ok=False)
    lock = private.parent.parent / '.local/parallel-launch-20261023/environment.lock'
    owner = {'session': 'assets', 'branch': 'codex/launch-assets-20261023', 'purpose': suite + ' live UI/API isolated verification',
             'at': datetime.now(timezone.utc).isoformat(), 'id': str(uuid.uuid4())}
    with lock.open('x', encoding='utf-8') as stream:
        json.dump(owner, stream)
    ssh = paramiko.SSHClient()
    tunnels, process, admin, seeded = [], None, None, False
    run_id = uuid.uuid4().hex[:12]
    tenants = sorted([str(uuid.uuid4()), str(uuid.uuid4())])
    users = {role: {'id': str(uuid.uuid4()), 'username': f'qa_assets_{run_id}_{role}', 'password': secrets.token_urlsafe(24)}
             for role in ('owner', 'reviewer', 'viewer')}
    state = {'environment': 'lan-test-20261008', 'suite': suite, 'run_id': run_id, 'tenants': tenants, 'api_port': api_port,
             'migrations': 0, 'deployment': False, 'actual_tenant_writes': False, 'external_messages': 0}

    def protected_snapshot():
        result = {}
        for table in ('mop_asset', 'mop_membership', 'mop_site', 'mop_project_role', 'mop_configuration_revision', 'mop_runtime_configuration_release'):
            with admin.cursor() as cursor:
                cursor.execute('SELECT count(*),md5(coalesce(string_agg(digest,\'\' ORDER BY digest),\'\')) FROM '
                               '(SELECT md5(to_jsonb(t)::text) digest FROM public.' + table + ' t WHERE tenant_id=ANY(%s::uuid[])) s', (PROTECTED,))
                result[table] = cursor.fetchone()
        return result

    try:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', api_port))
        cfg = json.loads((private / 'lan-test-connection.json').read_text(encoding='utf-8-sig'))
        runtime = json.loads((private / 'lan-test-runtime.json').read_text(encoding='utf-8-sig'))
        if cfg['environment'] != 'lan-test-20261008':
            raise ValueError('Unexpected designated environment')
        ssh.load_host_keys(str(private / 'lan-test-known_hosts'))
        ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
        ssh.connect(cfg['host'], username=cfg['username'], password=cfg['password'], look_for_keys=False, allow_agent=False, timeout=15)
        transport = ssh.get_transport()
        digest = base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip('=')
        if digest != cfg['expected_host_key_sha256']:
            raise ValueError('Pinned host mismatch')
        transport.set_keepalive(15)

        def remote(command):
            stdin, stdout, stderr = ssh.exec_command("sudo -S -p '' -- " + shlex.join(command), timeout=20)
            stdin.write(cfg['password'] + '\n'); stdin.flush(); stdin.channel.shutdown_write()
            result = stdout.read(); stderr.read()
            if stdout.channel.recv_exit_status():
                raise RuntimeError('Read-only component inspection failed')
            return result

        containers = {}
        for name in ('postgres', 'minio', 'api'):
            item = json.loads(remote(['docker', 'inspect', 'mop-lan-' + name]))[0]
            if item['Config']['Labels'].get('mop.project') != 'mop-lan-test' or not item['State']['Running']:
                raise ValueError('Existing component ownership/state mismatch')
            containers[name] = item

        def tunnel(name, port):
            networks = list(containers[name]['NetworkSettings']['Networks'].values())
            if len(networks) != 1 or not networks[0]['IPAddress']:
                raise ValueError('Ambiguous component route')
            target = (networks[0]['IPAddress'], port)

            class Handler(socketserver.BaseRequestHandler):
                def handle(self):
                    channel = transport.open_channel('direct-tcpip', target, self.request.getpeername(), timeout=15)
                    try:
                        while True:
                            for source in select.select([self.request, channel], [], [], 30)[0]:
                                data = source.recv(65536)
                                if not data:
                                    return
                                (channel if source is self.request else self.request).sendall(data)
                    except (OSError, EOFError):
                        pass
                    finally:
                        channel.close()

            class Server(socketserver.ThreadingTCPServer):
                daemon_threads = True
            server = Server(('127.0.0.1', 0), Handler)
            tunnels.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
            return server.server_address[1]

        pg_port, store_port = tunnel('postgres', 5432), tunnel('minio', 9000)
        admin = psycopg.connect(host='127.0.0.1', port=pg_port, dbname='mop_test', user='postgres',
                               password=runtime['postgres_admin_password'], autocommit=True)
        before = protected_snapshot()
        with admin.cursor() as cursor:
            cursor.execute('SHOW server_version'); state['postgresql_version'] = cursor.fetchone()[0]
            cursor.execute('SELECT version,checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank')
            history = cursor.fetchall()
            if len(history) < 50:
                raise ValueError('Required frozen schema unavailable')
            state['schema_latest'] = history[-1][0]
        hasher = PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1, hash_len=32, salt_len=16)
        with admin.transaction():
            with admin.cursor() as cursor:
                for role, user in users.items():
                    base_role = 'viewer' if role == 'viewer' else 'padmin'
                    cursor.execute("INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) VALUES(%s,'project_local',%s,%s,%s)",
                                   (user['id'], user['username'], 'SYNTHETIC_' + suite + '_' + run_id + '_' + role, base_role))
                    cursor.execute('INSERT INTO public.mop_local_account(user_id,username,password_hash) VALUES(%s,%s,%s)',
                                   (user['id'], user['username'], '{mop-argon2id-v1}' + hasher.hash(user['password'])))
                for index, tenant in enumerate(tenants):
                    cursor.execute('INSERT INTO public.mop_tenant(id,name) VALUES(%s,%s)', (tenant, f'SYNTHETIC_{suite}_{run_id}_{index}'))
                    cursor.execute("INSERT INTO public.mop_site(tenant_id,id,name) VALUES(%s,'a01-site','SYNTHETIC A01 isolated site')", (tenant,))
                    for role, user in users.items():
                        cursor.execute('INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(%s,%s,%s)',
                                       (tenant, user['id'], 'viewer' if role == 'viewer' else 'padmin'))
        seeded = True
        credentials = private / f'assets-a01-live-{run_id}.json'
        credentials.write_text(json.dumps({'users': users, 'tenants': tenants}), encoding='utf-8')
        state['credentials_reference'] = str(credentials)
        api_env = dict(item.split('=', 1) for item in containers['api']['Config']['Env'] if '=' in item)
        os_keys = {'SYSTEMROOT', 'WINDIR', 'PATH', 'PATHEXT', 'TEMP', 'TMP', 'USERPROFILE', 'APPDATA', 'LOCALAPPDATA', 'JAVA_HOME'}
        env = {key: value for key, value in os.environ.items() if key.upper() in os_keys}
        env.update({'SPRING_PROFILES_ACTIVE': 'remote_test', 'SPRING_DATASOURCE_URL': f'jdbc:postgresql://127.0.0.1:{pg_port}/mop_test',
                    'SPRING_DATASOURCE_USERNAME': 'mop_test_runtime', 'SPRING_DATASOURCE_PASSWORD': runtime['runtime_password'],
                    'MOP_AUTH_MODE': 'project_local', 'MOP_AUTH_COMMAND_KEY': runtime['auth_command_key'], 'MOP_OIDC_ENABLED': 'false',
                    'OBJECT_STORAGE_ENABLED': 'true', 'OBJECT_STORAGE_PROVIDER': 'minio',
                    'MOP_IMPORT_ALLOWED_FORMATS': 'CSV,XLSX', 'MOP_IMPORT_S3_ENDPOINT': f'http://127.0.0.1:{store_port}',
                    'MOP_IMPORT_S3_BUCKET': api_env.get('MOP_IMPORT_S3_BUCKET') or api_env.get('OBJECT_STORAGE_IMPORT_BUCKET'),
                    'MOP_IMPORT_S3_ACCESS_KEY': api_env.get('MOP_IMPORT_S3_ACCESS_KEY') or api_env.get('OBJECT_STORAGE_IMPORT_ACCESS_KEY'),
                    'MOP_IMPORT_S3_SECRET_KEY': api_env.get('MOP_IMPORT_S3_SECRET_KEY') or api_env.get('OBJECT_STORAGE_IMPORT_SECRET_KEY')})
        if any(value is None for value in env.values()):
            raise ValueError('Existing restricted import storage references unavailable')
        jar = ROOT / 'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'
        with (output / 'api.log').open('w', encoding='utf-8') as log:
            process = subprocess.Popen(['java', '-jar', str(jar), f'--server.port={api_port}', '--server.address=127.0.0.1'],
                                       cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        base = f'http://127.0.0.1:{api_port}/api/v1'
        for _ in range(90):
            if process.poll() is not None:
                raise RuntimeError('Candidate API exited')
            try:
                if requests.get(base + '/health/live', timeout=2).status_code == 200:
                    break
            except requests.RequestException:
                pass
            time.sleep(1)
        else:
            raise RuntimeError('Candidate API startup timeout')
        http = requests.Session()
        readiness_response = http.get(base + '/health/ready', timeout=20)
        readiness = readiness_response.json()
        state['candidate_global_readiness_status'] = readiness_response.status_code
        state['candidate_unready_components'] = [item.get('component') for item in readiness.get('error', {}).get('details', [])]
        state['import_storage_ready'] = readiness.get('data', {}).get('asset_imports', {}).get('storage_ready')
        csrf = http.get(base + '/auth/csrf', timeout=10).json()['data']['csrf_token']
        response = http.post(base + '/auth/local/login', json={k: users['owner'][k] for k in ('username', 'password')}, headers={'X-CSRF-Token': csrf}, timeout=20)
        response.raise_for_status(); current = response.json()['data']; csrf = current['csrf_token']
        if current['tenant']['id'] != tenants[0]:
            response = http.post(base + '/session/tenant', json={'tenant_id': tenants[0]},
                                 headers={'X-CSRF-Token': csrf, 'Idempotency-Key': str(uuid.uuid4())}, timeout=20)
            response.raise_for_status(); csrf = response.json()['data']['csrf_token']
        policy = {'schema_version': 1, 'type_mapping': {'sw_access': 'switch'}, 'site_mapping': {'a01-site': 'a01-site'},
                  'owner_mapping': {'qa-owner': users['reviewer']['id']} if suite == 'A05' else {}, 'default_site_id': None, 'date_policy': 'ISO_DATE_ONLY', 'business_description_mode': 'SOURCE_TEXT',
                  'preserve_source_columns': [15,19,20,21,22,23,24,25,26,27,28,29,30,31,32],
                  'address_policy': {'domain_mode': 'ASCII_ONLY', 'allow_single_label': False, 'root_dot_mode': 'REJECT',
                    'allow_bracketed_ipv6': False, 'leading_zero_mode': 'REJECT', 'mapped_ipv4_mode': 'CANONICAL_IPV4',
                    'whitespace_mode': 'REJECT', 'numeric_domain_mode': 'REJECT_LEGACY_ADDRESS_SHAPES'}}
        # Explicitly synthetic approved seed, in new namespaces only; not a customer approval claim.
        for key, value in {'asset.import_steps': policy, 'asset.ip_scope': 'TENANT', 'tenant.contract_device_quota': 20}.items():
            response = http.get(base + '/runtime-configurations/' + key, timeout=20); response.raise_for_status()
            definition_hash = response.json()['data']['definition_hash']
            revision = str(uuid.uuid4())
            with admin.transaction():
                with admin.cursor() as cursor:
                    cursor.execute("INSERT INTO public.mop_configuration_revision(id,tenant_id,configuration_key,proposal,workflow_status,created_by,reviewed_by,reviewed_at,review_comment) VALUES(%s,%s,%s,%s::jsonb,'APPROVED',%s,%s,clock_timestamp(),'SYNTHETIC A01 verification only')",
                                   (revision, tenants[0], key, json.dumps({'proposed_value': value}), users['owner']['id'], users['reviewer']['id']))
                    cursor.execute("INSERT INTO public.mop_runtime_configuration_release(id,tenant_id,configuration_key,version,action,revision_id,revision_version,value_json,definition_hash,effective_at,published_by,published_permission_version,published_at,reason) VALUES(%s,%s,%s,1,'PUBLISH',%s,1,%s::jsonb,%s,statement_timestamp(),%s,1,statement_timestamp(),'SYNTHETIC A01 isolated UI verification')",
                                   (str(uuid.uuid4()), tenants[0], key, revision, json.dumps(value), definition_hash, users['owner']['id']))
        schema = json.loads((ROOT / 'apps/web/src/generated/cmdbImportSchema.json').read_text(encoding='utf-8'))
        state['files'] = []
        for case in ('success', 'uncertain', 'reload'):
            rows = [[c['label'] for c in schema['columns']]]
            for index in (1, 2):
                row = [''] * 33
                row[0], row[1], row[2], row[6], row[14], row[16] = f'SYNTHETIC_{suite}_{run_id}_{case}_{index}', 'SW', f'192.0.2.{10 + len(state["files"]) * 2 + index}', 'a01-site', 'SYNTHETIC isolated API/browser verification', 'sw_access'
                if suite == 'A05':
                    row[8], row[9] = 'qa-owner', 'SYNTHETIC business declaration'
                row[20] = 'SYNTHETIC-RACK'
                rows.append(row)
            path = output / f'SYNTHETIC-{suite}-{case}.csv'
            with path.open('w', encoding='utf-8-sig', newline='') as stream:
                csv.writer(stream).writerows(rows)
            state['files'].append(str(path))
        state['status'] = 'READY'
        (output / 'state.json').write_text(json.dumps(state, indent=2), encoding='utf-8')
        print(json.dumps({'status': 'READY', 'state': str(output / 'state.json'), 'api_port': api_port}), flush=True)
        while not (output / 'stop').exists():
            if process.poll() is not None:
                raise RuntimeError('Candidate API exited during verification')
            if suite == 'A05' and (output / 'disable_reviewer').exists() and not state.get('reviewer_disabled'):
                with admin.transaction():
                    with admin.cursor() as cursor:
                        reviewer = users['reviewer']
                        cursor.execute("UPDATE public.mop_user SET active=false WHERE id=%s AND identity_source='project_local' AND subject=%s",
                                       (reviewer['id'], reviewer['username']))
                        if cursor.rowcount != 1:
                            raise RuntimeError('Owned reviewer transition mismatch')
                state['reviewer_disabled'] = True
                (output / 'state.json').write_text(json.dumps(state, indent=2), encoding='utf-8')
            time.sleep(1)
    except Exception as failure:
        state.update(status='FAILED', error_type=type(failure).__name__)
        print(json.dumps({'status': 'FAILED', 'error_type': type(failure).__name__}), flush=True)
        raise
    finally:
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill(); process.wait(timeout=10)
        if seeded and admin:
            with admin.transaction():
                with admin.cursor() as cursor:
                    for user in users.values():
                        cursor.execute("UPDATE public.mop_user SET active=false WHERE id=%s AND identity_source='project_local' AND subject=%s", (user['id'], user['username']))
                        if cursor.rowcount != 1:
                            raise RuntimeError('Owned test identity cleanup mismatch')
                    cursor.execute('UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id=ANY(%s::uuid[]) AND user_id=ANY(%s::uuid[])', (tenants, [u['id'] for u in users.values()]))
            state['new_test_identities_disabled'] = True
            state['protected_fingerprints_unchanged'] = before == protected_snapshot()
            with admin.cursor() as cursor:
                cursor.execute('SELECT version,checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank')
                state['migration_history_unchanged'] = history == cursor.fetchall()
            if state['status'] == 'READY':
                state['status'] = 'STOPPED'
        if admin:
            admin.close()
        for server in tunnels:
            server.shutdown(); server.server_close()
        ssh.close()
        (output / 'state.json').write_text(json.dumps(state, indent=2), encoding='utf-8')
        if lock.exists() and json.loads(lock.read_text(encoding='utf-8')) == owner:
            lock.unlink()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--private-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--api-port', type=int, default=18211)
    parser.add_argument('--suite', choices=['A01', 'A05'], default='A01')
    args = parser.parse_args()
    try:
        run(args.private_directory.resolve(), args.output.resolve(), args.api_port, args.suite)
    except Exception as failure:
        print(json.dumps({'status': 'FAILED', 'error_type': type(failure).__name__, 'details': 'omitted'}))
        raise SystemExit(1)

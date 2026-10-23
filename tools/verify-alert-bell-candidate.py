"""Run a short-lived loopback API candidate against the existing designated PG.

Only existing explicitly isolated DEMO accounts are used. Login/context selection
are the only POSTs; business checks are GET. No schema change, seed, deployment,
worker, provider dispatch or local infrastructure is started.
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
import socket
import subprocess
import threading
import time
import uuid

import paramiko
import requests

ROOT = Path(__file__).resolve().parents[1]
PRIVATE = Path('E:/work/wxw9/zhonganzaixian/mop-platform/deploy/private')
LOCK = Path('E:/work/wxw9/zhonganzaixian/mop-platform/.local/parallel-launch-20261023/environment.lock')
PORT = 18217


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify(output: Path, hold: bool = False):
    require(output.is_relative_to((ROOT / '.local').resolve()), 'Output must remain in ignored local staging')
    output.mkdir(parents=True, exist_ok=False)
    marker = {'session': 'integration', 'operation': 'UI03-alert-bell-candidate-http', 'token': str(uuid.uuid4())}
    with LOCK.open('x', encoding='utf-8') as stream:
        json.dump(marker, stream)
    ssh = paramiko.SSHClient()
    tunnel = process = log = None
    result = {'environment': 'lan-test-20261008', 'api_origin': f'http://127.0.0.1:{PORT}',
              'status': 'NOT_RUN', 'business_writes': 0, 'migrations': 0, 'external_messages': 0,
              'new_local_components': 0, 'local_application_candidate': True,
              'full_readiness_claimed': False, 'checks': [], 'formal_tc': 'NOT_RUN'}
    try:
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', PORT))
        connection = json.loads((PRIVATE / 'lan-test-connection.json').read_text(encoding='utf-8-sig'))
        runtime = json.loads((PRIVATE / 'lan-test-runtime.json').read_text(encoding='utf-8-sig'))
        demo = json.loads((PRIVATE / 'demo-full-20261023.json').read_text(encoding='utf-8-sig'))
        tenants = {row['tenant_id'] for row in demo['contexts'].values()}
        require(demo['bootstrap_complete'] and len(tenants) == 3 and not any(value.startswith('55000000-') for value in tenants),
                'Existing isolated DEMO namespace required')
        require(connection['environment'] == 'lan-test-20261008' and connection['host'] == '192.168.1.215',
                'Unexpected designated server')
        ssh.load_host_keys(str(PRIVATE / 'lan-test-known_hosts'))
        ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
        ssh.connect(connection['host'], username=connection['username'], password=connection['password'],
                    look_for_keys=False, allow_agent=False, timeout=15)
        transport = ssh.get_transport()
        fingerprint = base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip('=')
        require(fingerprint == connection['expected_host_key_sha256'], 'Pinned host key mismatch')
        _, stdout, stderr = ssh.exec_command('cat /opt/mop-lan-test/owner.json', timeout=15)
        owner = json.loads(stdout.read())
        stderr.read()
        require(stdout.channel.recv_exit_status() == 0 and owner.get('project') == 'mop-lan-test'
                and owner.get('environment') == connection['environment'], 'Project ownership mismatch')
        spec = importlib.util.spec_from_file_location('bell_candidate_tunnel', ROOT / 'tools/wecom-dev-tunnel.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        tunnel = module.Server(('127.0.0.1', 0), module.handler(transport, False))
        threading.Thread(target=tunnel.serve_forever, daemon=True).start()
        env = {key: value for key, value in os.environ.items() if not key.startswith(('MOP_', 'SPRING_', 'OBJECT_STORAGE_'))}
        env.update({'SERVER_ADDRESS': '127.0.0.1', 'SERVER_PORT': str(PORT), 'SPRING_PROFILES_ACTIVE': 'remote_test',
                    'SPRING_DATASOURCE_URL': f'jdbc:postgresql://127.0.0.1:{tunnel.server_address[1]}/mop_test',
                    'SPRING_DATASOURCE_USERNAME': 'mop_test_runtime', 'SPRING_DATASOURCE_PASSWORD': runtime['runtime_password'],
                    'MOP_AUTH_MODE': 'project_local', 'MOP_AUTH_COMMAND_KEY': runtime['auth_command_key'],
                    'MOP_TICKET_LIFECYCLE_ENABLED': 'false', 'MOP_WECOM_ENABLED': 'false', 'MOP_EMAIL_ENABLED': 'false',
                    'MOP_WECOM_DISPATCH_ENABLED': 'false', 'MOP_EMAIL_DISPATCH_ENABLED': 'false',
                    'MOP_OIDC_ENABLED': 'false', 'MOP_DEMO_CHANNEL_TENANT_IDS': ','.join(sorted(tenants))})
        jar = ROOT / 'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'
        require(jar.is_file(), 'Build the candidate API jar first')
        result['jar_sha256'] = hashlib.sha256(jar.read_bytes()).hexdigest()
        log = (output / 'api.log').open('w', encoding='utf-8')
        process = subprocess.Popen(['java', '-jar', str(jar)], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
        origin = result['api_origin']
        ready = False
        for _ in range(60):
            require(process.poll() is None, 'Candidate process exited; private log retained')
            try:
                ready = requests.get(origin + '/api/v1/health/live', timeout=2).status_code == 200
                if ready:
                    break
            except requests.RequestException:
                pass
            time.sleep(1)
        require(ready, 'Candidate did not start')
        denied = requests.get(origin + '/api/v1/dashboard/alert-bell', timeout=10)
        require(denied.status_code == 401 and 'data' not in denied.json(), 'Anonymous bell data leaked')
        result['anonymous_read_denied'] = True
        for role in ('padmin', 'msp', 'tadmin'):
            http = requests.Session()
            csrf = None

            def call(path, method='GET', body=None):
                nonlocal csrf
                require(method == 'GET' or path in ('/auth/local/login', '/session/tenant'), 'Business mutations forbidden')
                headers = {'Origin': origin, 'Accept': 'application/json'}
                if csrf:
                    headers['X-CSRF-Token'] = csrf
                if method != 'GET':
                    headers['Idempotency-Key'] = str(uuid.uuid4())
                response = http.request(method, origin + '/api/v1' + path, json=body, headers=headers, timeout=30)
                require(response.status_code == 200, 'Candidate API rejected ' + method + ' ' + path)
                return response.json()['data']

            csrf = call('/auth/csrf')['csrf_token']
            session = call('/auth/local/login', 'POST', {key: demo['users']['main'][key] for key in ('username', 'password')})
            csrf = session['csrf_token']
            require({row['id'] for row in session['allowed_tenants']} == tenants, 'Unexpected allowed tenants')
            tenant = demo['contexts'][role]['tenant_id']
            if session['tenant']['id'] != tenant:
                session = call('/session/tenant', 'POST', {'tenant_id': tenant})
                csrf = session['csrf_token']
            require(session['tenant']['id'] == tenant and session['user']['role'] == role, 'Unexpected selected scope')
            if role == 'tadmin':
                denied = http.get(origin + '/api/v1/dashboard/alert-bell', timeout=15)
                require(denied.status_code == 403 and 'data' not in denied.json(), 'Customer bell data leaked')
                result['checks'].append({'role': role, 'status': 'PASSED', 'http_status': 403})
            else:
                bell = call('/dashboard/alert-bell')
                require(bell['tenant_id'] == tenant and bell['actor_id'] == session['user']['id']
                        and bell['permission_version'] == session['permission_version'], 'Bell scope mismatch')
                require(bell['filter'] == {'monitor_state': 'PROBLEM', 'handling_state': 'NEW'}
                        and bell['scope'] == 'CURRENT_TENANT_INTERNAL' and bell['source'] == 'postgresql'
                        and bell['metric_version'] == 'MOP_UNACKNOWLEDGED_PROBLEM_EVENTS_V1'
                        and bell['writes_performed'] is False, 'Incorrect bell source or filter')
                require(len(bell['items']) == min(5, bell['total']), 'Bell count/list mismatch')
                for row in bell['items']:
                    detail = call('/events/' + row['event_id'])
                    event = detail.get('event', detail)
                    require(event['id'] == row['event_id'] and event['monitor_state'] == 'PROBLEM'
                            and event['handling_state'] == 'NEW', 'Bell row/detail mismatch')
                invalid = http.get(origin + '/api/v1/dashboard/alert-bell?tenant_id=' + tenant, timeout=15)
                require(invalid.status_code == 400, 'Bell scope override accepted')
                result['checks'].append({'role': role, 'status': 'PASSED', 'total': bell['total'],
                                         'visible_items': len(bell['items']), 'scope_override_rejected': True})
            http.close()
        result['status'] = 'PASSED'
        if hold:
            print(json.dumps({'candidate_ready': True, 'origin': origin, 'environment_lock_held': True}), flush=True)
            while process.poll() is None and not (output / 'stop').exists():
                time.sleep(1)
    except Exception as failure:
        result.update(status='FAILED', failure_type=type(failure).__name__, details='private details withheld')
        (output / 'failure.txt').write_text(str(failure), encoding='utf-8')
    finally:
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
            result['candidate_stopped'] = True
        if log is not None:
            log.close()
        if tunnel is not None:
            tunnel.shutdown()
            tunnel.server_close()
        ssh.close()
        result['finished_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
        (output / 'summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        if json.loads(LOCK.read_text(encoding='utf-8')) == marker:
            LOCK.unlink()
    print(json.dumps(result))
    return 0 if result['status'] == 'PASSED' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--hold', action='store_true', help='Keep this local candidate and its environment lock until Ctrl+C')
    args = parser.parse_args()
    raise SystemExit(verify(args.output.resolve(), args.hold))

"""Provision explicitly authorized, tenant-isolated DEMO data on the existing LAN project.

Private state is outside Git. No real tenant writes, schema migrations, service
changes, credential rotation, device commands, or outbound messages are allowed.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import secrets
import subprocess
import uuid

import requests
from argon2 import PasswordHasher

ROOT = Path(__file__).resolve().parents[1]
PROTECTED = {'55000000-0000-4000-8000-000000000001', '55000000-0000-4000-8000-000000000002'}
ORIGIN = 'http://mop.all-online.com.cn'


def require(value, message):
    if not value:
        raise RuntimeError(message)


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def json_text(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), sort_keys=True)


class Environment:
    def __init__(self, private_root):
        self.private_root = Path(private_root).resolve()
        require(self.private_root.name == 'private' and self.private_root.parent.name == 'deploy', 'Expected private directory')
        self.owner_root = self.private_root.parent.parent
        self.state_path = self.private_root / 'demo-full-20261023.json'
        require(subprocess.run(['git', 'check-ignore', '--quiet', str(self.state_path)], cwd=self.owner_root).returncode == 0, 'Private file must be ignored')
        if self.state_path.exists():
            self.state = json.loads(self.state_path.read_text(encoding='utf-8'))
        else:
            namespace = uuid.uuid4()
            uid = lambda name: str(uuid.uuid5(namespace, name))
            self.state = {
                'version': 1, 'namespace': str(namespace), 'origin': ORIGIN,
                'created_at': dt.datetime.now(dt.timezone.utc).isoformat(),
                'contexts': {role: {'tenant_id': uid('tenant:' + role), 'site_id': uid('site:' + role),
                    'name': '[DEMO] ' + label} for role, label in [('padmin', '全功能测试·管理'), ('msp', '全功能测试·工程师'), ('tadmin', '全功能测试·客户')]},
                'users': {key: {'id': uid('user:' + key), 'username': name, 'password': secrets.token_urlsafe(24),
                    'display_name': '[DEMO] ' + label} for key, name, label in [
                    ('main', 'demo_full', '全功能测试用户'), ('admin', 'demo_full_admin', '审核管理员'),
                    ('engineer', 'demo_full_engineer', '测试工程师'), ('customer', 'demo_full_customer', '测试客户')]},
                'resources': {}, 'requests': [], 'bootstrap_complete': False,
            }
            self.save(exclusive=True)
        require(self.state['origin'] == ORIGIN, 'Unexpected API origin')
        self.tenants = {c['tenant_id'] for c in self.state['contexts'].values()}
        require(not self.tenants & PROTECTED and len(self.tenants) == 3, 'Invalid demo namespaces')
        self.server = None

    def save(self, exclusive=False):
        with self.state_path.open('x' if exclusive else 'w', encoding='utf-8') as target:
            json.dump(self.state, target, ensure_ascii=False, indent=2)

    def connect(self):
        if self.server:
            return self.server
        spec = importlib.util.spec_from_file_location('demo_lan_server', self.owner_root / 'tools/update-lan-test-server.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.server = module.Server()
        return self.server

    def sql(self, statement):
        return self.connect().run(['docker', 'exec', '-i', 'mop-lan-postgres', 'psql', '-X', '-qAt',
            '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'mop_test'], True, data=statement, timeout=60)

    def query(self, statement):
        return json.loads(self.sql("BEGIN READ ONLY; SELECT coalesce(json_agg(row_to_json(q)),'[]') FROM (" + statement + ') q; COMMIT;'))

    def protected_fingerprint(self):
        result = {}
        for table in ('mop_asset', 'mop_membership', 'mop_site', 'mop_project_role', 'mop_member_role_assignment', 'mop_email_binding', 'mop_wecom_binding'):
            result[table] = self.query(f"SELECT count(*) AS count,md5(coalesce(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),'')) AS digest FROM {table} t WHERE tenant_id IN ({','.join(literal(t) + '::uuid' for t in sorted(PROTECTED))})")[0]
        return result

    def bootstrap(self):
        if self.state['bootstrap_complete']:
            return
        baseline = self.protected_fingerprint()
        users = self.state['users']
        statements = ["BEGIN; SET LOCAL lock_timeout='5s'; SELECT pg_advisory_xact_lock(hashtext('mop-demo-full-20261023')); DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Wrong database'; END IF; END $$;"]
        hasher = PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1, hash_len=32, salt_len=16)
        for key, user in users.items():
            role = {'main': 'padmin', 'admin': 'padmin', 'engineer': 'msp', 'customer': 'tadmin'}[key]
            password_hash = '{mop-argon2id-v1}' + hasher.hash(user['password'])
            statements.append(f"INSERT INTO mop_user(id,identity_source,subject,display_name,role_code) VALUES ({literal(user['id'])}::uuid,'project_local',{literal(user['username'])},{literal(user['display_name'])},{literal(role)});")
            statements.append(f"INSERT INTO mop_local_account(user_id,username,password_hash) VALUES ({literal(user['id'])}::uuid,{literal(user['username'])},{literal(password_hash)});")
        for role, context in self.state['contexts'].items():
            tenant = context['tenant_id']
            statements.append(f"INSERT INTO mop_tenant(id,name) VALUES ({literal(tenant)}::uuid,{literal(context['name'])});")
            statements.append(f"INSERT INTO mop_site(tenant_id,id,name) VALUES ({literal(tenant)}::uuid,{literal(context['site_id'])},'[DEMO] 隔离测试站点');")
            for key, user in users.items():
                assigned = role if key == 'main' else {'admin': 'padmin', 'engineer': 'msp', 'customer': 'tadmin'}[key]
                statements.append(f"INSERT INTO mop_membership(tenant_id,user_id,role_code) VALUES ({literal(tenant)}::uuid,{literal(user['id'])}::uuid,{literal(assigned)});")
            statements.append(self.audit_sql(tenant, 'demo.environment.bootstrap'))
        statements.append('SET CONSTRAINTS ALL IMMEDIATE; COMMIT;')
        self.sql('\n'.join(statements))
        require(self.protected_fingerprint() == baseline, 'Protected rows changed during bootstrap')
        self.state['protected_before'] = baseline
        self.state['bootstrap_complete'] = True
        self.state['schema_history'] = self.connect().schema()
        self.save()

    def audit_sql(self, tenant, action):
        require(tenant in self.tenants, 'Demo audit namespace required')
        return """DO $$ DECLARE seq bigint; prev text; body json; canonical text; entry text; uid uuid:=gen_random_uuid(); BEGIN
          SELECT sequence,entry_hash INTO seq,prev FROM mop_audit WHERE namespace=""" + literal(tenant) + """ ORDER BY sequence DESC LIMIT 1;
          seq:=coalesce(seq,0)+1; prev:=coalesce(prev,repeat('0',64));
          body:=json_build_object('id',uid::text,'occurred_at',to_char(statement_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
            'actor_id',null,'actor_name','用户授权的演示数据准备','tenant_id',""" + literal(tenant) + """,
            'action',""" + literal(action) + """,'resource_type','demo_namespace','resource_id',""" + literal(self.state['namespace']) + """,
            'result','SUCCEEDED','reason','USER_AUTHORIZED_DEMO_ONLY;NO_REAL_TENANT_OR_OUTBOUND',
            'trace_id',uid::text,'source','postgresql','sequence',seq,'previous_hash',prev);
          SELECT '{'||string_agg(to_json(key)::text||':'||value::text,',' ORDER BY key)||'}' INTO canonical FROM json_each(body);
          entry:=encode(sha256(convert_to(prev||canonical,'UTF8')),'hex');
          INSERT INTO mop_audit(id,namespace,tenant_id,sequence,previous_hash,entry_hash,payload)
            VALUES(uid,""" + literal(tenant) + ',' + literal(tenant) + """,seq,prev,entry,body::jsonb||jsonb_build_object('entry_hash',entry));
        END $$;"""

    def ensure_reviewer(self):
        if self.state.get('reviewer_created'):
            return
        users = self.state['users']
        if 'reviewer' not in users:
            users['reviewer'] = {'id': str(uuid.uuid5(uuid.UUID(self.state['namespace']), 'user:reviewer')),
                'username': 'demo_full_reviewer', 'password': secrets.token_urlsafe(24), 'display_name': '[DEMO] 配置审核员'}
            self.save()
        user = users['reviewer']
        encoded = '{mop-argon2id-v1}' + PasswordHasher(time_cost=2, memory_cost=19456, parallelism=1, hash_len=32, salt_len=16).hash(user['password'])
        parts = ['BEGIN;', f"INSERT INTO mop_user(id,identity_source,subject,display_name,role_code) VALUES({literal(user['id'])}::uuid,'project_local',{literal(user['username'])},{literal(user['display_name'])},'padmin');",
            f"INSERT INTO mop_local_account(user_id,username,password_hash) VALUES({literal(user['id'])}::uuid,{literal(user['username'])},{literal(encoded)});"]
        for tenant in sorted(self.tenants):
            parts.append(f"INSERT INTO mop_membership(tenant_id,user_id,role_code) VALUES({literal(tenant)}::uuid,{literal(user['id'])}::uuid,'padmin');")
            parts.append(self.audit_sql(tenant, 'demo.configuration.reviewer.create'))
        self.sql('\n'.join(parts + ['SET CONSTRAINTS ALL IMMEDIATE; COMMIT;']))
        self.state['reviewer_created'] = True
        self.save()

    @contextmanager
    def lock(self):
        path = self.owner_root / '.local/parallel-launch-20261023/environment.lock'
        marker = json_text({'owner': 'demo-account-20261023', 'token': str(uuid.uuid4()), 'started_at': dt.datetime.now(dt.timezone.utc).isoformat()})
        with path.open('x', encoding='utf-8') as target:
            target.write(marker)
        try:
            yield
        finally:
            if path.read_text(encoding='utf-8') == marker:
                path.unlink()

    def client(self, context='padmin', user='main'):
        return Client(self, context, user)

    def once(self, client, key, path, body, method='POST'):
        qualified = client.context + ':' + key
        if qualified in self.state['resources']:
            return self.state['resources'][qualified]
        result = client.call(path, method, body, key=str(uuid.uuid5(uuid.UUID(self.state['namespace']), qualified)))
        self.state['resources'][qualified] = result
        self.save()
        return result

    def close(self):
        if self.server:
            self.server.client.close()


class Client:
    def __init__(self, environment, context, user):
        self.env = environment
        self.context = context
        self.tenant = environment.state['contexts'][context]['tenant_id']
        self.http = requests.Session()
        self.csrf = None
        self.session = None
        self.csrf = self.call('/auth/csrf')['csrf_token']
        self.session = self.call('/auth/local/login', 'POST', {k: environment.state['users'][user][k] for k in ('username', 'password')})
        self.csrf = self.session['csrf_token']
        require({t['id'] for t in self.session['allowed_tenants']} == environment.tenants, 'Unexpected tenant access')
        if self.session['tenant']['id'] != self.tenant:
            self.session = self.call('/session/tenant', 'POST', {'tenant_id': self.tenant})
            self.csrf = self.session['csrf_token']
        require(self.session['tenant']['id'] == self.tenant, 'Wrong session tenant')

    def call(self, path, method='GET', body=None, key=None, expected=(200, 201, 202)):
        require(path.startswith('/') and not path.startswith('//') and '://' not in path, 'Relative API required')
        if method != 'GET' and path not in ('/auth/local/login', '/session/tenant'):
            require(self.session and self.session['tenant']['id'] == self.tenant and self.tenant in self.env.tenants, 'Demo write guard')
            require(not any(value in path or value in json_text(body) for value in PROTECTED), 'Protected reference')
            require(not any(forbidden in path for forbidden in ('test-messages', '/wecom', '/email', '/identity/policy', '/monitoring-onboarding/registered-targets')), 'Outbound/global operation prohibited')
        headers = {'Origin': ORIGIN, 'Accept': 'application/json'}
        if self.csrf:
            headers['X-CSRF-Token'] = self.csrf
        if method != 'GET':
            headers['Idempotency-Key'] = key or str(uuid.uuid4())
        response = self.http.request(method, ORIGIN + '/api/v1' + path, json=body, headers=headers, timeout=30, allow_redirects=False)
        payload = response.json()
        self.env.state['requests'].append({'context': self.context, 'method': method, 'path': path, 'status': response.status_code, 'code': payload.get('error', {}).get('code')})
        self.env.save()
        if response.status_code not in expected:
            error = payload.get('error', {})
            # Error payload stays private; public output contains only code/path/status.
            self.env.state['last_error'] = payload
            self.env.save()
            raise RuntimeError(f"HTTP {response.status_code} {path}: {error.get('code')}")
        return payload.get('data', payload.get('error'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-root', required=True)
    parser.add_argument('command', choices=['bootstrap', 'verify'])
    args = parser.parse_args()
    env = Environment(args.private_root)
    try:
        if args.command == 'bootstrap':
            with env.lock():
                env.bootstrap()
        results = []
        for role in env.state['contexts']:
            client = env.client(role)
            page = client.call('/assets')
            results.append({'context': role, 'login': True, 'asset_count': len(page['items'])})
        print(json_text({'username': env.state['users']['main']['username'], 'contexts': results}))
    finally:
        env.close()


if __name__ == '__main__':
    main()

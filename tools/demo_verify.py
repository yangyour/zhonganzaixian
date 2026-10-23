"""Finish disabled demo bindings and verify live data and two-way real-tenant isolation."""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import uuid
import requests

from demo_environment import Environment, ORIGIN, literal, require, json_text


def seed_inactive_bindings(env):
    """Existing normal APIs require verified active creation; seed only dormant DEMO rows.

    No provider is called. The synthetic WeCom timestamp is sample metadata, not
    provider verification. The disabled channel status remains not-ready.
    """
    if env.state.get('inactive_bindings_seeded'):
        return
    user = env.state['users']['main']['id']
    actor = env.state['users']['admin']['id']
    parts = ['BEGIN;']
    for role, context in env.state['contexts'].items():
        tenant = context['tenant_id']
        require(tenant in env.tenants, 'Only demo tenants may receive sample bindings')
        sender = 'demo-' + tenant + '@example.invalid'
        corp = 'wwDEMO' + tenant.replace('-', '')
        email_id = str(uuid.uuid5(uuid.UUID(env.state['namespace']), role + ':disabled-email'))
        wecom_id = str(uuid.uuid5(uuid.UUID(env.state['namespace']), role + ':disabled-wecom'))
        # Frozen schema requires version 1 creation followed by version 2 disable.
        # Both happen in one transaction: no other session ever sees an active row.
        parts.append(f"INSERT INTO mop_email_binding(id,tenant_id,user_id,sender,email,updated_by) VALUES({literal(email_id)}::uuid,{literal(tenant)}::uuid,{literal(user)}::uuid,{literal(sender)},'demo-user@example.invalid',{literal(actor)}::uuid);")
        parts.append(f"UPDATE mop_email_binding SET active=false,version=2,updated_at=date_trunc('milliseconds',clock_timestamp()) WHERE id={literal(email_id)}::uuid AND tenant_id={literal(tenant)}::uuid AND version=1;")
        parts.append(f"INSERT INTO mop_wecom_binding(id,tenant_id,user_id,corp_id,wecom_user_id,verified_at,updated_by) VALUES({literal(wecom_id)}::uuid,{literal(tenant)}::uuid,{literal(user)}::uuid,{literal(corp)},'DEMO_full_test',date_trunc('milliseconds',clock_timestamp()),{literal(actor)}::uuid);")
        parts.append(f"UPDATE mop_wecom_binding SET active=false,version=2,updated_at=date_trunc('milliseconds',clock_timestamp()) WHERE id={literal(wecom_id)}::uuid AND tenant_id={literal(tenant)}::uuid AND version=1;")
        parts.append(env.audit_sql(tenant, 'demo.disabled_channel.sample_seed'))
    env.sql('\n'.join(parts + ['SET CONSTRAINTS ALL IMMEDIATE; COMMIT;']))
    env.state['inactive_bindings_seeded'] = True
    env.save()


def verify(env):
    seed_inactive_bindings(env)
    report = {'verified_at': dt.datetime.now(dt.timezone.utc).isoformat(), 'contexts': [], 'cross_tenant': [], 'credentials_recorded': False}
    main_id = env.state['users']['main']['id']
    protected_tenant = '55000000-0000-4000-8000-000000000001'
    real_asset = '9b9f03bd-0f3c-4a2f-b9e1-5dd003f2ab45'
    for role, context in env.state['contexts'].items():
        main = env.client(role)
        # Binding administration checks every membership of the target account.
        admin = env.client(role, 'admin')
        for provider, fields in [('email', {'email': 'demo-user@example.invalid'}), ('wecom', {'wecom_user_id': 'DEMO_full_test'})]:
            env.once(admin, 'disabled-binding:' + provider, '/' + provider + '/bindings/' + main_id,
                {'expected_version': 2, 'active': False, **fields}, method='PUT')
            status = admin.call('/' + provider + '/status')
            require(status['tenant_id'] == context['tenant_id'] and status['enabled'] is False and status['dispatch_enabled'] is False, 'Demo provider enabled')
            bindings = admin.call('/' + provider + '/bindings')['items']
            require(len(bindings) == 1 and bindings[0]['active'] is False and bindings[0]['user_id'] == main_id, 'Disabled binding missing')
            require(not admin.call('/' + provider + '/deliveries')['items'], 'Unexpected external delivery')
        assets = main.call('/assets')['items']
        require(len(assets) == 7 and all(asset['tenant_id'] == context['tenant_id'] and asset['name'].startswith('[DEMO]') for asset in assets), 'Unexpected demo asset data')
        network = main.call('/network-monitoring')['devices']
        require(len(network) == 1, 'Demo monitoring unavailable')
        workflow = admin.call('/ticket-workflows')['items']
        notices = main.call('/my/notifications')['items']
        require(len(workflow) >= 3 and len(notices) >= 1, 'Workflow or own notifications empty')
        main.call('/assets/' + real_asset, expected=(404,))
        main.call('/session/tenant', 'POST', {'tenant_id': protected_tenant}, expected=(404,))
        report['cross_tenant'].append({'role': role, 'real_asset': 404, 'real_tenant_switch': 404})
        count = env.query('SELECT max(sequence) AS last FROM mop_audit WHERE tenant_id=' + literal(context['tenant_id']) + '::uuid')[0]['last']
        proof = admin.call('/audit/integrity?from_sequence=1&to_sequence=' + str(count))
        require(proof['valid'] is True, 'Demo audit chain invalid')
        report['contexts'].append({'role': role, 'tenant_id': context['tenant_id'], 'assets': len(assets), 'network_devices': len(network),
            'workflows': len(workflow), 'own_notifications': len(notices), 'audit_records_verified': count, 'disabled_channel_bindings': 2})
    # Existing owner's credentials are read locally; only statuses/counts enter evidence.
    saved = (env.private_root / 'jiangfaming-test-login.txt').read_text(encoding='utf-8-sig')
    user = re.search(r'^账号[：:]\s*(.+)$', saved, re.M)[1].strip()
    password = re.search(r'^密码[：:]\s*(.+)$', saved, re.M)[1].strip()
    http = requests.Session()
    csrf = http.get(ORIGIN + '/api/v1/auth/csrf', timeout=20).json()['data']['csrf_token']
    response = http.post(ORIGIN + '/api/v1/auth/local/login', json={'username': user, 'password': password}, headers={'Origin': ORIGIN, 'X-CSRF-Token': csrf}, timeout=20)
    require(response.status_code == 200, 'Real account login failed')
    session = response.json()['data']
    require(not session.get('demo_examples_enabled') and not ({item['id'] for item in session['allowed_tenants']} & env.tenants), 'Real account gained demo scope')
    real_assets = http.get(ORIGIN + '/api/v1/assets', timeout=20).json()['data']['items']
    require(len(real_assets) == 4 and all(row['tenant_id'] == protected_tenant for row in real_assets), 'Real assets changed')
    for role in env.state['contexts']:
        asset = env.state['resources'][role + ':asset:router']['id']
        status = http.get(ORIGIN + '/api/v1/assets/' + asset, timeout=20).status_code
        require(status == 404, 'Real account could read demo asset')
        report['cross_tenant'].append({'real_account_to_demo_role': role, 'asset': status})
    example = http.get(ORIGIN + '/api/v1/demo/examples/capacity-estimates', timeout=20).json()['data']
    require(example['available'] is False and example['input'] is None, 'Real account received synthetic inputs')
    report['real_account'] = {'login': 200, 'assets': len(real_assets), 'demo_memberships': 0, 'demo_examples_enabled': False}
    report['protected_rows_unchanged'] = env.protected_fingerprint() == env.state['protected_before']
    require(report['protected_rows_unchanged'], 'Real data fingerprint changed')
    report['schema_unchanged'] = env.connect().schema() == env.state['schema_history']
    require(report['schema_unchanged'], 'Schema changed')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-root', required=True)
    args = parser.parse_args()
    env = Environment(args.private_root)
    try:
        with env.lock():
            report = verify(env)
        output = Path(__file__).resolve().parents[1] / 'docs/evidence/demo-account-20261023/live-http.json'
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json_text(report))
    finally:
        env.close()

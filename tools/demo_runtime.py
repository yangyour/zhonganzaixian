"""Attach demo-only in-app notifications and test-server network samples.

Uses the existing API image/JAR and fixed test server. Existing bindings, source
labels, secret files, and all other containers remain unchanged. Never enables
email/WeCom dispatch or probes a customer device.
"""
import argparse
import datetime as dt
import importlib.util
import json
import uuid

from demo_environment import Environment, require, json_text


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def network_bindings(env):
    module = load(env.owner_root / 'tools/lan_monitoring_zabbix.py', 'demo_zabbix')
    api = module.LanZabbix()
    result = []
    try:
        api.login_admin()
        require(api.rpc('apiinfo.version', {}).startswith('7.0.'), 'Expected Zabbix 7.0')
        groups = api.rpc('hostgroup.get', {'filter': {'name': ['MOP LAN basic network test 20261012']}, 'output': ['groupid', 'name']})
        require(len(groups) == 1, 'Existing project group must be unambiguous')
        group = groups[0]['groupid']
        for role, context in env.state['contexts'].items():
            client = env.client(role, 'admin')
            asset = env.once(client, 'network-test-server', '/assets', {'name': '[DEMO] 指定测试服务器网络采样', 'type': 'other',
                'management_ip': '192.168.1.215', 'site_id': context['site_id']})
            host = 'mop-demo-' + context['tenant_id']
            description = 'MOP DEMO 20261023; owned namespace ' + context['tenant_id'] + '; samples ONLY the designated test server; no customer device or alert action.'
            existing = api.rpc('host.get', {'filter': {'host': [host]}, 'output': ['hostid', 'host', 'description', 'status'], 'selectHostGroups': ['groupid']})
            require(len(existing) <= 1, 'Ambiguous demo host')
            if existing:
                require(existing[0]['description'] == description and existing[0]['status'] == '0'
                    and {g['groupid'] for g in existing[0]['hostgroups']} == {group}, 'Demo host ownership mismatch')
                host_id = existing[0]['hostid']
            else:
                host_id = api.rpc('host.create', {'host': host, 'name': context['name'] + '·测试服务器', 'description': description,
                    'groups': [{'groupid': group}], 'tags': [{'tag': 'mop_demo_tenant', 'value': context['tenant_id']}], 'status': 0})['hostids'][0]
            items = {}
            target = '192.168.1.215'
            metrics = [('icmp', f'icmpping[{target},3,200,56,1000]', '3', ''), ('loss', f'icmppingloss[{target},3,200,56,1000]', '0', '%'),
                ('latency', f'icmppingsec[{target},3,200,56,1000,avg]', '0', 's')]
            metrics += [(f'tcp_{port}', f'net.tcp.service[tcp,{target},{port}]', '3', '') for port in (22, 80, 443)]
            for metric, key, value_type, units in metrics:
                expected = {'type': '3', 'value_type': value_type, 'delay': '60s', 'history': '7d', 'status': '0', 'units': units}
                old = api.rpc('item.get', {'hostids': [host_id], 'filter': {'key_': [key]}, 'output': ['itemid', *expected]})
                require(len(old) <= 1 and (not old or all(old[0][k] == v for k, v in expected.items())), 'Demo item drift')
                items[metric] = old[0]['itemid'] if old else api.rpc('item.create', {'hostid': host_id, 'name': '[DEMO] test server ' + metric,
                    'key_': key, **expected, 'trends': '0', 'description': description})['itemids'][0]
            result.append({'tenant_id': context['tenant_id'], 'asset_id': asset['id'], 'ip': target, 'brand': 'DEMO',
                'model': '指定测试服务器（真实基础网络采样）', 'host_id': host_id, 'items': items})
        env.state['demo_network_bindings'] = result
        env.save()
        return result
    finally:
        api.close()


def attach_runtime(env, bindings):
    if env.state.get('runtime_attached'):
        return
    module = load(env.owner_root / 'tools/update-lan-test-server.py', 'demo_runtime_update')
    server = env.connect()
    remote = '/opt/mop-lan-test'
    update_lock = remote + '/.update-lock'
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-demo-' + uuid.uuid4().hex[:8]
    folder = remote + '/private/update-' + run_id
    candidate = 'mop-lan-update-candidate-' + run_id
    candidate_created = False
    server.run(['mkdir', update_lock])
    try:
        server.write(update_lock + '/owner.json', json_text({'run_id': run_id, 'purpose': 'demo-only bindings'}))
        before = server.containers()
        items = module.choose(before)
        protected = module.metadata(before, ['mop-lan-api'])
        old = module.overlay(items)
        desired = json.loads(json.dumps(old))
        api_env = desired['services']['api']['environment']
        original_bindings = json.loads(api_env['MOP_NETWORK_MONITORING_BINDINGS'])
        require(not any(b['tenant_id'] in env.tenants for b in original_bindings), 'Demo binding already present; review runtime before retry')
        api_env['MOP_NETWORK_MONITORING_BINDINGS'] = json_text(original_bindings + bindings)
        was_enabled = api_env.get('MOP_NOTIFICATION_RELAY_ENABLED', 'false') == 'true'
        previous_tenants = api_env.get('MOP_NOTIFICATION_RELAY_TENANT_IDS', '').split(',') if was_enabled else []
        api_env['MOP_NOTIFICATION_RELAY_ENABLED'] = 'true'
        api_env['MOP_NOTIFICATION_RELAY_TENANT_IDS'] = ','.join(sorted(set(t for t in previous_tenants if t) | env.tenants))
        changed = {key for key in api_env if api_env[key] != old['services']['api']['environment'].get(key)}
        require(changed <= {'MOP_NETWORK_MONITORING_BINDINGS', 'MOP_NOTIFICATION_RELAY_ENABLED', 'MOP_NOTIFICATION_RELAY_TENANT_IDS'}, 'Unexpected runtime mutation')
        history = server.schema()
        server.ready()
        active = json.loads(server.read(remote + '/active-update.json'))
        base = [remote + '/compose.yaml', active['compose_file']]
        snapshots = {path: server.read(path) for path in [remote + '/.env', *base]}
        server.run(['mkdir', '-m', '700', folder])
        old_text = module.compose_json(old)
        server.write(folder + '/rollback.json', old_text)
        server.write(folder + '/deploy.json', module.compose_json(desired))
        recovery = {'environment': 'lan-test-20261008', 'source_commit': active['source_commit'], 'run_id': run_id, 'services': ['api'],
            'base_files': base, 'compose_file': folder + '/deploy.json', 'rollback_file': folder + '/rollback.json', 'previous_active': active,
            'release': active['release'], 'schema': history,
            'recovery_config_sha256': {path: module.digest(raw.encode()) for path, raw in {**snapshots, folder + '/rollback.json': old_text}.items()}}
        server.write(folder + '/transaction.json', json_text(recovery))
        require(not server.run(['ss', '-lntH', 'sport = :18039']).strip(), 'Candidate port occupied')
        candidate_env = module.candidate_environment(api_env)
        # A dry candidate does not consume in-app outbox records before activation.
        candidate_env['MOP_NOTIFICATION_RELAY_ENABLED'] = 'false'
        require(all('\n' not in v and '\r' not in v for v in candidate_env.values()), 'Invalid env value')
        server.write(folder + '/candidate.env', ''.join(k + '=' + v + '\n' for k, v in candidate_env.items()))
        command = ['docker', 'create', '--name', candidate, '--network', 'host', '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--memory', '1536m', '--tmpfs', '/tmp:rw,nosuid,size=384m',
            '--label', 'mop.project=mop-lan-test', '--env-file', folder + '/candidate.env']
        for mount in desired['services']['api']['volumes']:
            command += ['--mount', 'type=bind,source=' + mount['source'] + ',target=' + mount['target'] + (',readonly' if mount['read_only'] else '')]
        command += [desired['services']['api']['image'], 'java', '-XX:MaxRAMPercentage=60.0', '-jar', '/app/api.jar']
        server.run(command, True)
        candidate_created = True
        server.run(['docker', 'start', candidate], True)
        server.ready(18039)
        server.run(['docker', 'rm', '-f', candidate], True)
        candidate_created = False
        server.selected = ['api']
        def verify():
            ready = server.ready()
            require(module.metadata(server.containers(), ['mop-lan-api']) == protected, 'Unrelated component changed')
            require(server.schema() == history, 'Schema changed')
            require(all(server.read(path) == raw for path, raw in snapshots.items()), 'Concurrent runtime change')
            server.atomic(remote + '/active-update.json', json_text(recovery))
            return ready
        module.guarded_switch(server, base + [folder + '/deploy.json'], base + [folder + '/rollback.json'], verify,
            lambda: module.validate_recovery_snapshot(server, recovery))
        env.state['runtime_attached'] = {'transaction': folder + '/transaction.json', 'previous_source_preserved': True,
            'existing_bindings_preserved': len(original_bindings), 'demo_bindings_added': len(bindings), 'external_dispatch_changes': 0,
            'schema_unchanged': True, 'other_13_containers_unchanged': True}
        env.save()
    finally:
        if candidate_created:
            server.run(['docker', 'rm', '-f', candidate], True, check=False)
        server.run(['rm', '--', update_lock + '/owner.json'], check=False)
        server.run(['rmdir', '--', update_lock], check=False)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-root', required=True)
    args = parser.parse_args()
    env = Environment(args.private_root)
    try:
        with env.lock():
            bindings = network_bindings(env)
            attach_runtime(env, bindings)
            require(env.protected_fingerprint() == env.state['protected_before'], 'Protected rows changed')
        print(json_text(env.state['runtime_attached']))
    finally:
        env.close()

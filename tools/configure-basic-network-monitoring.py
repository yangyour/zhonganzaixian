"""Explicitly provision the three user-approved basic network test targets.

Default is read-only planning. --apply creates only this dedicated group's
hosts/items. It does not install services, touch devices, or create alert rules.
"""
import argparse
import datetime
import json
from pathlib import Path

from device_network_probe import validate_manifest
from lan_zabbix import LanZabbix, ROOT

SCOPE = 'mop-lan-basic-network-20261012'
GROUP = 'MOP LAN basic network test 20261012'
KEYS = ('sangfor-hci', 'huawei-switch', 'sundray-switch')
OWNER = 'MOP explicit IP-only network test; vendor identity unverified; no notification rules.'


def metric_plan(ip):
    return [
        {'metric': 'icmp', 'key_': f'icmpping[{ip},3,200,56,1000]', 'value_type': 3, 'units': ''},
        {'metric': 'loss', 'key_': f'icmppingloss[{ip},3,200,56,1000]', 'value_type': 0, 'units': '%'},
        {'metric': 'latency', 'key_': f'icmppingsec[{ip},3,200,56,1000,avg]', 'value_type': 0, 'units': 's'},
        *[{'metric': f'tcp_{port}', 'key_': f'net.tcp.service[tcp,{ip},{port}]', 'value_type': 3, 'units': ''}
          for port in (22, 80, 443)],
    ]


def provision(api, devices, apply=False):
    version = api.rpc('apiinfo.version', {})
    if not version.startswith('7.0.'):
        raise ValueError('Unverified Zabbix version')
    groups = api.rpc('hostgroup.get', {'filter': {'name': [GROUP]}, 'output': ['groupid', 'name']})
    if len(groups) > 1:
        raise ValueError('Ambiguous project group')
    if not groups and apply:
        groups = [{'groupid': api.rpc('hostgroup.create', {'name': GROUP})['groupids'][0]}]
    gid = groups[0]['groupid'] if groups else None
    report = {'environment': 'lan-test-20261008', 'scope': SCOPE, 'provider_version': version,
              'apply': apply, 'sample_interval_seconds': 60, 'test_history_days': 7,
              'notification_rules_created': False, 'device_login_performed': False, 'devices': []}
    for device in devices:
        name = 'mop-lan-basic-' + device['key']
        hosts = api.rpc('host.get', {'filter': {'host': [name]}, 'output': ['hostid', 'host', 'description', 'status'],
                                     'selectHostGroups': ['groupid'], 'selectTags': 'extend'})
        if len(hosts) > 1:
            raise ValueError('Ambiguous owned host')
        if hosts and (hosts[0]['description'] != OWNER or hosts[0]['status'] != '0'
                      or {g['groupid'] for g in hosts[0]['hostgroups']} != {gid}
                      or not any(t.get('tag') == 'mop_scope' and t.get('value') == SCOPE
                                 and t.get('automatic', '0') == '0' for t in hosts[0]['tags'])):
            raise ValueError('Existing host does not match ownership/configuration')
        if not hosts and apply:
            hid = api.rpc('host.create', {'host': name, 'name': device['brand'] + ' ' + device['model'] + '（IP基础测试）',
                                          'description': OWNER, 'groups': [{'groupid': gid}],
                                          'tags': [{'tag': 'mop_scope', 'value': SCOPE}], 'status': 0})['hostids'][0]
        else:
            hid = hosts[0]['hostid'] if hosts else None
        row = {**device, 'provider_host_id': hid, 'items': []}
        for metric in metric_plan(device['ip']):
            existing = api.rpc('item.get', {'hostids': [hid], 'filter': {'key_': [metric['key_']]},
                                            'output': ['itemid', 'key_', 'type', 'value_type', 'delay', 'history', 'status', 'units']}) if hid else []
            expected = {'type': '3', 'value_type': str(metric['value_type']), 'delay': '60s',
                        'history': '7d', 'status': '0', 'units': metric['units']}
            if len(existing) > 1 or existing and any(existing[0][k] != v for k, v in expected.items()):
                raise ValueError('Existing item drift; no automatic overwrite')
            iid = existing[0]['itemid'] if existing else None
            if iid is None and apply:
                iid = api.rpc('item.create', {'hostid': hid, 'name': 'MOP basic ' + metric['metric'],
                                              'key_': metric['key_'], **expected, 'trends': '0',
                                              'description': OWNER})['itemids'][0]
            row['items'].append({**metric, 'provider_item_id': iid})
        report['devices'].append(row)
    report['verified_at'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    api = None
    try:
        manifest = validate_manifest(json.loads((ROOT / 'deploy/lan-test/devices.json').read_text(encoding='utf-8')))
        devices = [d for d in manifest['devices'] if d['key'] in KEYS]
        if tuple(d['key'] for d in devices) != KEYS:
            raise ValueError('Expected exactly three selected targets')
        api = LanZabbix()
        api.login_admin()
        report = provision(api, devices, args.apply)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'apply': args.apply, 'targets': len(devices), 'items': sum(len(d['items']) for d in report['devices']),
                          'notification_rules_created': False, 'credentials_output': False}))
    except Exception as failure:
        print(json.dumps({'status': 'FAILED', 'class': type(failure).__name__, 'details': 'omitted; query state before retry'}))
        return 1
    finally:
        if api:
            if api.token:
                try:
                    api.rpc('user.logout', [])
                except Exception:
                    pass
            api.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

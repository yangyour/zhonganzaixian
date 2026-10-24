"""Read existing project-scoped Zabbix samples without changing collection.

Uses the existing limited reader tokens through pinned SSH. Never logs in to a
device, provisions targets, installs components, changes rules, or sends alerts.
The ten-minute history is an observation window, not an SLA or health verdict.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import json
from pathlib import Path
import re
import time

from device_network_probe import validate_manifest
from lan_monitoring_zabbix import LanZabbix, ROOT

WINDOW_SECONDS = 600
MAX_AGE_SECONDS = 180
EXPECTED_INTERVAL_SECONDS = 60
MAX_OBSERVED_GAP_SECONDS = 90
HISTORY_LIMIT = 32


def load_private(name):
    value = json.loads((ROOT / 'deploy/private' / name).read_text(encoding='utf-8-sig'))
    if not isinstance(value, dict):
        raise ValueError('Invalid private configuration')
    return value


def canonical_id(value):
    if not isinstance(value, str) or not re.fullmatch(r'[1-9][0-9]{0,19}', value):
        raise ValueError('Invalid provider identifier')
    return value


def numeric(value, metric):
    if not isinstance(value, str) or not re.fullmatch(r'-?[0-9]{1,40}(?:\.[0-9]{1,40})?', value):
        raise ValueError('Invalid metric value')
    try:
        number = Decimal(value)
    except InvalidOperation:
        raise ValueError('Invalid metric value') from None
    if number < 0 or metric in ('loss', 'cpu', 'memory') and number > 100:
        raise ValueError('Metric outside verified range')
    if (metric == 'icmp' or metric.startswith('tcp_')) and number not in (0, 1):
        raise ValueError('Invalid reachability value')
    return value


def sample_value(value, metric):
    if metric == 'state':
        # Only the known success marker is released; error text may contain secrets.
        if not isinstance(value, str) or not value:
            raise ValueError('Invalid collector state')
        return 'OK' if value == 'OK' else 'COLLECTOR_NOT_OK'
    return numeric(value, metric)


def assess(item, samples, metric, now):
    """Evaluate actual samples; stale/disabled/invalid current values stay null."""
    report = {'status': 'INVALID_RESPONSE', 'current_value': None,
              'sample_clock': None, 'age_seconds': None, 'history_points': 0,
              'history_first_clock': None, 'history_last_clock': None,
              'max_observed_gap_seconds': None, 'window_coverage_observed': False}
    try:
        if item.get('status') != '0':
            return {**report, 'status': 'DISABLED'}
        if item.get('state') != '0':
            return {**report, 'status': 'UNSUPPORTED'}
        if not isinstance(samples, list) or len(samples) >= HISTORY_LIMIT:
            raise ValueError('Ambiguous or truncated history')
        clock = int(item['lastclock'])
        age = now - clock
        if clock < 0 or age < 0:
            raise ValueError('Invalid sample time')
        rows = []
        for row in samples:
            if row.get('itemid') != item['itemid']:
                raise ValueError('Wrong history item')
            at, ns = int(row['clock']), int(row.get('ns', '0'))
            if not now - WINDOW_SECONDS <= at <= now or not 0 <= ns < 1_000_000_000:
                raise ValueError('Invalid history time')
            sample_value(row['value'], metric)
            rows.append((at, ns))
        if rows != sorted(set(rows)):
            raise ValueError('Duplicate or unordered history')
        gaps = [right[0] - left[0] for left, right in zip(rows, rows[1:])]
        report.update(sample_clock=clock or None, age_seconds=age if clock else None,
                      history_points=len(rows), history_first_clock=rows[0][0] if rows else None,
                      history_last_clock=rows[-1][0] if rows else None,
                      max_observed_gap_seconds=max(gaps) if gaps else None,
                      window_coverage_observed=bool(len(rows) >= 2
                          and rows[0][0] - (now - WINDOW_SECONDS) <= MAX_OBSERVED_GAP_SECONDS
                          and now - rows[-1][0] <= MAX_OBSERVED_GAP_SECONDS
                          and max(gaps) <= MAX_OBSERVED_GAP_SECONDS))
        if not clock:
            return {**report, 'status': 'NO_DATA'}
        if age > MAX_AGE_SECONDS:
            return {**report, 'status': 'STALE'}
        value = sample_value(item['lastvalue'], metric)
        return {**report, 'status': 'FRESH', 'current_value': value}
    except (KeyError, ValueError, TypeError, InvalidOperation, OverflowError):
        return {**report, 'status': 'INVALID_RESPONSE', 'current_value': None,
                'window_coverage_observed': False}


def expected_network(ip):
    return {
        'icmp': (f'icmpping[{ip},3,200,56,1000]', '3', ''),
        'loss': (f'icmppingloss[{ip},3,200,56,1000]', '0', '%'),
        'latency': (f'icmppingsec[{ip},3,200,56,1000,avg]', '0', 's'),
        **{f'tcp_{port}': (f'net.tcp.service[tcp,{ip},{port}]', '3', '')
           for port in (22, 80, 443)},
    }


def read_metrics(api, host_id, specs, collector_type):
    """Resolve exact IDs and metadata before querying bounded numeric histories."""
    host_id = canonical_id(host_id)
    hosts = api.rpc('host.get', {'hostids': [host_id], 'output': ['hostid', 'status']})
    if len(hosts) != 1 or hosts[0].get('hostid') != host_id or hosts[0].get('status') != '0':
        raise ValueError('Expected enabled project host')
    ids = [canonical_id(spec['id']) for spec in specs.values()]
    if len(set(ids)) != len(ids):
        raise ValueError('Duplicate metric identifiers')
    rows = api.rpc('item.get', {'hostids': [host_id], 'itemids': ids, 'output': [
        'itemid', 'hostid', 'key_', 'type', 'value_type', 'units', 'status', 'state',
        'delay', 'lastclock', 'lastvalue']})
    by_id = {row['itemid']: row for row in rows}
    if len(rows) != len(ids) or len(by_id) != len(ids) or set(by_id) != set(ids):
        raise ValueError('Missing or ambiguous project metric')
    result = {}
    for metric, spec in specs.items():
        row = by_id[spec['id']]
        if (row['hostid'] != host_id or row['key_'] != spec['key']
                or row['type'] != collector_type or row['value_type'] != spec['value_type']
                or row['units'] != spec['units']
                or collector_type == '3' and row['delay'] != '60s'):
            raise ValueError('Existing metric configuration drift')
        now = int(time.time())
        history = api.rpc('history.get', {'itemids': [spec['id']],
            'history': int(spec['value_type']), 'time_from': now - WINDOW_SECONDS,
            'time_till': now, 'output': ['itemid', 'clock', 'ns', 'value'],
            'sortfield': ['clock', 'ns'], 'sortorder': 'ASC', 'limit': HISTORY_LIMIT})
        result[metric] = {**assess(row, history, metric, now), 'unit': spec['units'],
                          'provider_item_id': spec['id']}
    return result


def inspect():
    manifest = validate_manifest(json.loads((ROOT / 'deploy/lan-test/devices.json').read_text(encoding='utf-8')))
    bindings = load_private('basic-monitoring-bindings.json')
    if bindings.get('environment') != manifest['environment']:
        raise ValueError('Binding environment mismatch')
    mapped = {row['device_key']: row for row in bindings['bindings']}
    if len(mapped) != len(bindings['bindings']) or set(mapped) != {d['key'] for d in manifest['devices']}:
        raise ValueError('Expected exactly the four approved devices')
    api = LanZabbix()
    try:
        report = {'schema_version': 1, 'started_at': datetime.now(timezone.utc).isoformat(),
                  'environment': manifest['environment'], 'read_only': True,
                  'credentials_output': False, 'device_authentication_attempted': False,
                  'provider_writes': 0, 'history_window_seconds': WINDOW_SECONDS,
                  'max_age_seconds': MAX_AGE_SECONDS,
                  'expected_interval_seconds': EXPECTED_INTERVAL_SECONDS,
                  'max_observed_gap_seconds': MAX_OBSERVED_GAP_SECONDS,
                  'provider_version': api.rpc('apiinfo.version', {}), 'devices': []}
        api.token = load_private('basic-monitoring-reader.json')['api_token']
        for device in manifest['devices']:
            bound = mapped[device['key']]
            if bound['ip'] != device['ip']:
                raise ValueError('Approved address changed')
            items = {i['metric']: i for i in bound['items']}
            expected = expected_network(device['ip'])
            if len(items) != len(bound['items']) or set(items) != set(expected):
                raise ValueError('Network metric mapping mismatch')
            specs = {}
            for metric, (key, value_type, units) in expected.items():
                if items[metric]['key_'] != key or str(items[metric]['value_type']) != value_type:
                    raise ValueError('Network metric mapping drift')
                specs[metric] = {'id': items[metric]['provider_item_id'], 'key': key,
                                 'value_type': value_type, 'units': units}
            report['devices'].append({**device, 'asset_id': bound['asset_id'],
                'metrics': read_metrics(api, bound['provider_host_id'], specs, '3'),
                'vendor_identity_verified': False, 'overall_device_health': None})
        af = load_private('af-resource-access.json')
        firewall = mapped['sangfor-firewall']
        if any(af['binding'][k] != firewall[k] for k in ('tenant_id', 'asset_id', 'ip')):
            raise ValueError('AF resource binding mismatch')
        api.token = af['reader']['api_token']
        specs = {metric: {'id': canonical_id(af['items'][metric]),
            'key': f'mop.af.{metric}[{firewall["ip"]}]',
            'value_type': '1' if metric == 'state' else '0',
            'units': '' if metric == 'state' else '%'} for metric in ('cpu', 'memory', 'state')}
        resources = read_metrics(api, af['host_id'], specs, '2')
        verified = (all(r['status'] == 'FRESH' for r in resources.values())
                    and len({r['sample_clock'] for r in resources.values()}) == 1
                    and resources['state']['current_value'] == 'OK')
        if not verified:
            for metric in ('cpu', 'memory'):
                resources[metric]['current_value'] = None
        report['firewall_resources'] = {'metrics': resources, 'current_receipt_verified': verified,
            'time_semantics': 'COLLECTOR_RECEIPT_TIME', 'source_sampled_at': None,
            'tls_mode': af['binding']['tls_mode'], 'tls_trust_verified': False}
        report['all_network_samples_fresh'] = all(m['status'] == 'FRESH'
            for device in report['devices'] for m in device['metrics'].values())
        report['completed_at'] = datetime.now(timezone.utc).isoformat()
        return report
    finally:
        api.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to((ROOT / 'docs/evidence').resolve()) or output.exists():
        raise ValueError('A new evidence report is required')
    code = 1
    try:
        report = inspect()
        code = 0 if report['all_network_samples_fresh'] and report['firewall_resources']['current_receipt_verified'] else 2
    except Exception as failure:
        report = {'status': 'CHECK_FAILED', 'error_class': type(failure).__name__,
                  'details': 'omitted', 'read_only': True, 'credentials_output': False,
                  'all_network_samples_fresh': False, 'firewall_resources_verified': False}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'exit_status': code, 'all_network_samples_fresh': report['all_network_samples_fresh'],
                      'firewall_resources_verified': report.get('firewall_resources', {}).get('current_receipt_verified', False)}))
    return code


if __name__ == '__main__':
    raise SystemExit(main())

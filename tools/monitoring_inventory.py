"""Compile a bounded, explicit asset inventory into disabled monitoring targets.

Offline only: never scan, resolve DNS, test, install, apply or approve destinations.
The same inventory and logical service keys produce stable target identifiers.
"""
from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import re
import shlex
import sys
import uuid
from pathlib import Path

import monitoring_presets as presets

NAMESPACE = uuid.UUID('f2358881-b5ce-58e5-a986-af76126c9ecb')
MAX_TARGETS = 64


def fields(value, required, optional=()):
    if not isinstance(value, dict) or not set(required) <= set(value) or set(value) - set(required) - set(optional):
        presets.fail('INVENTORY_SHAPE')


def ipv4(value):
    address = presets.ip_literal(value)
    if not isinstance(address, ipaddress.IPv4Address):
        presets.fail('SERVER_REQUIRES_IPV4')
    return str(address)


def integer(value, low, high, code):
    if type(value) is not int or not low <= value <= high:
        presets.fail(code)
    return value


def symbol(value, code):
    if not isinstance(value, str) or not presets.SYMBOL.fullmatch(value):
        presets.fail(code)
    return value


def check_defaults(value):
    fields(value, (), ('interval_seconds', 'timeout_seconds', 'exporter_scheme'))
    interval = value.get('interval_seconds')
    if interval is not None:
        integer(interval, 5, 3600, 'INVALID_INTERVAL')
    timeout = integer(value.get('timeout_seconds', 5), 1, 10, 'INVALID_TIMEOUT')
    if interval is not None and timeout >= interval:
        presets.fail('TIMEOUT_MUST_BE_SHORTER')
    scheme = value.get('exporter_scheme', 'http')
    if scheme not in ('http', 'https'):
        presets.fail('INVALID_EXPORTER_SCHEME')
    return {'interval_seconds': interval, 'timeout_seconds': timeout, 'exporter_scheme': scheme}


def check_collectors(value):
    if not isinstance(value, dict) or len(value) > 16:
        presets.fail('COLLECTOR_COUNT')
    result = {}
    for name, collector in value.items():
        symbol(name, 'INVALID_COLLECTOR_NAME')
        fields(collector, ('kind', 'endpoint'), ('auth_profile',))
        kind = collector['kind']
        if kind not in ('blackbox', 'snmp'):
            presets.fail('UNKNOWN_COLLECTOR_KIND')
        preset = presets.PRESETS['icmp-device' if kind == 'blackbox' else 'network-snmp']
        endpoint = presets.endpoint(collector['endpoint'], kind, preset['path'])
        ipv4(endpoint.hostname)
        if 'auth_profile' in collector:
            if kind != 'snmp':
                presets.fail('UNEXPECTED_AUTH_PROFILE')
            symbol(collector['auth_profile'], 'SNMP_AUTH_PROFILE_REQUIRED')
        result[name] = dict(collector)
    return result


def make_target(tenant, asset, raw, defaults, collectors):
    profile = {'preset_id': raw} if isinstance(raw, str) else raw
    fields(profile, ('preset_id',), ('key', 'port', 'scheme', 'path', 'collector', 'auth_profile'))
    preset_id = profile['preset_id']
    if not isinstance(preset_id, str) or preset_id not in presets.PRESETS:
        presets.fail('UNKNOWN_PRESET')
    preset = presets.PRESETS[preset_id]
    kind = preset['test_kind']
    key = symbol(profile.get('key', preset_id), 'INVALID_SERVICE_KEY')
    allowed = {'preset_id', 'key'}
    if kind == 'metrics':
        allowed |= {'port', 'scheme'}
    elif kind in ('tcp', 'configuration_only'):
        allowed |= {'port'}
    elif preset_id == 'http-service':
        allowed |= {'port', 'scheme', 'path', 'collector'}
    elif preset_id == 'tcp-service':
        allowed |= {'port', 'collector'}
    elif kind == 'snmp':
        allowed |= {'collector', 'auth_profile'}
    else:
        allowed |= {'collector'}
    if set(profile) - allowed:
        presets.fail('PROFILE_FIELD_NOT_APPLICABLE')

    address = asset['address']
    target = auth = None
    if kind in ('blackbox', 'snmp'):
        name = profile.get('collector', kind)
        if not isinstance(name, str) or name not in collectors:
            presets.fail('COLLECTOR_REQUIRED')
        collector = collectors[name]
        if collector['kind'] != kind:
            presets.fail('COLLECTOR_KIND_MISMATCH')
        endpoint = collector['endpoint']
        if preset_id == 'http-service':
            scheme = profile.get('scheme')
            if scheme not in ('http', 'https'):
                presets.fail('HTTP_SCHEME_REQUIRED')
            port = integer(profile.get('port', 443 if scheme == 'https' else 80), 1, 65535, 'INVALID_SERVICE_PORT')
            path = profile.get('path', '/')
            if not isinstance(path, str) or not path.startswith('/') or path.startswith('//') or not re.fullmatch(r"[A-Za-z0-9._~!$&'()*+,;=:@/-]*", path):
                presets.fail('INVALID_HTTP_PATH')
            target = f'{scheme}://{address}:{port}{path}'
        elif preset_id == 'tcp-service':
            port = integer(profile.get('port'), 1, 65535, 'SERVICE_PORT_REQUIRED')
            target = f'{address}:{port}'
        else:
            target = address
        if kind == 'snmp':
            auth = symbol(profile.get('auth_profile', collector.get('auth_profile')), 'SNMP_AUTH_PROFILE_REQUIRED')
    else:
        port = integer(profile.get('port', preset['suggested_port']), 1, 65535, 'INVALID_EXPORTER_PORT')
        scheme = 'tcp' if kind == 'tcp' else 'udp' if kind == 'configuration_only' else profile.get('scheme', defaults['exporter_scheme'])
        if kind == 'metrics' and scheme not in ('http', 'https'):
            presets.fail('INVALID_EXPORTER_SCHEME')
        endpoint = f"{scheme}://{address}:{port}{preset['path'] or ''}"

    identity = json.dumps([tenant, asset['asset_id'], key], separators=(',', ':'))
    row = {'id': str(uuid.uuid5(NAMESPACE, identity)), 'tenant_id': tenant, 'asset_id': asset['asset_id'],
           'preset_id': preset_id, 'enabled': False, 'endpoint': endpoint, 'target': target,
           'auth_profile': auth, 'interval_seconds': defaults['interval_seconds'], 'timeout_seconds': defaults['timeout_seconds']}
    presets.validate({'version': 1, 'targets': [row]})
    return key, row


def compile_inventory(inventory):
    """All-or-nothing validation with positional, value-free diagnostics."""
    diagnostics, targets = [], []
    try:
        fields(inventory, ('version', 'tenant_id', 'assets'), ('defaults', 'collectors'))
        if type(inventory['version']) is not int or inventory['version'] != 1:
            presets.fail('INVENTORY_VERSION')
        tenant = inventory['tenant_id']
        if not isinstance(tenant, str) or not presets.UUID.fullmatch(tenant):
            presets.fail('INVALID_TENANT_ID')
        if not isinstance(inventory['assets'], list) or not 1 <= len(inventory['assets']) <= MAX_TARGETS:
            presets.fail('ASSET_COUNT')
        defaults = check_defaults(inventory.get('defaults', {}))
        collectors = check_collectors(inventory.get('collectors', {}))
    except presets.Invalid as error:
        return {'valid': False, 'diagnostics': [{'code': str(error)}], 'configuration': None}

    asset_ids = set()
    for asset_index, original in enumerate(inventory['assets'], 1):
        try:
            fields(original, ('asset_id', 'address', 'profiles'))
            asset = dict(original)
            asset_id = asset['asset_id']
            if not isinstance(asset_id, str) or not presets.UUID.fullmatch(asset_id):
                presets.fail('INVALID_ASSET_ID')
            if asset_id in asset_ids:
                presets.fail('DUPLICATE_ASSET_ID')
            asset_ids.add(asset_id)
            asset['address'] = ipv4(asset['address'])
            if not isinstance(asset['profiles'], list) or not 1 <= len(asset['profiles']) <= 16:
                presets.fail('PROFILE_COUNT')
        except presets.Invalid as error:
            diagnostics.append({'asset_index': asset_index, 'code': str(error)})
            continue
        keys = set()
        for profile_index, profile in enumerate(asset['profiles'], 1):
            try:
                key, target = make_target(tenant, asset, profile, defaults, collectors)
                if key in keys:
                    presets.fail('DUPLICATE_LOGICAL_SERVICE_KEY')
                keys.add(key)
                targets.append(target)
            except presets.Invalid as error:
                diagnostics.append({'asset_index': asset_index, 'profile_index': profile_index, 'code': str(error)})
    if diagnostics:
        return {'valid': False, 'diagnostics': diagnostics, 'configuration': None}
    try:
        # Deterministic order prevents inventory reorder from changing the plan hash.
        configuration = presets.validate({'version': 1, 'targets': sorted(targets, key=lambda row: row['id'])})
    except presets.Invalid as error:
        return {'valid': False, 'diagnostics': [{'code': str(error)}], 'configuration': None}
    used = sorted({row['preset_id'] for row in targets})
    return {'valid': True, 'diagnostics': [], 'configuration': configuration,
            'configuration_sha256': presets.fingerprint(configuration), 'catalog_sha256': hashlib.sha256((presets.ROOT / 'config/monitoring/presets.v1.json').read_bytes()).hexdigest(), 'target_count': len(targets),
            'asset_count': len(asset_ids), 'network_accessed': False, 'applied': False,
            'continuous_collection_verified': False, 'asset_identity_verified': False,
            'requirements': [{'preset_id': name, 'documentation_url': presets.PRESETS[name]['source_url'],
                              'state': 'NOT_CHECKED', 'test_kind': presets.PRESETS[name]['test_kind']} for name in used]}


def compare_configurations(previous, planned):
    """Preview changes only; disappearance is not authorization to remove a target."""
    old = presets.validate(previous)
    new = presets.validate(planned)
    tenants = {row['tenant_id'] for row in new['targets']}
    if len(tenants) != 1 or any(row['tenant_id'] not in tenants for row in old['targets']):
        presets.fail('DIFF_TENANT_SCOPE_MISMATCH')
    before = {row['id']: row for row in old['targets']}
    after = {row['id']: row for row in new['targets']}
    common = before.keys() & after.keys()
    changes = [{'target_id': key, 'changed_fields': sorted(name for name in before[key] if before[key][name] != after[key][name])}
               for key in sorted(common) if before[key] != after[key]]
    return {'previous_configuration_sha256': presets.fingerprint(old),
            'next_configuration_sha256': presets.fingerprint(new),
            'added_target_ids': sorted(after.keys() - before.keys()),
            'removed_target_ids': sorted(before.keys() - after.keys()), 'changed_targets': changes,
            'unchanged_count': len(common) - len(changes), 'removal_requires_review': True, 'applied': False}


def public_summary(plan):
    return {key: value for key, value in plan.items() if key != 'configuration'}


def write_bundle(plan, directory):
    """Exclusive creation: never overwrite an existing operator bundle or symlink."""
    if not plan['valid']:
        presets.fail('INVALID_PLAN_NOT_WRITTEN')
    config = presets.validate(plan['configuration'])
    if any(row['enabled'] for row in config['targets']) or presets.fingerprint(config) != plan['configuration_sha256']:
        presets.fail('PLAN_CHANGED_OR_ENABLED')
    review = {'approved': False, 'endpoints': sorted({row['endpoint'] for row in config['targets']}),
              'downstream_targets': sorted({row['target'] for row in config['targets'] if row['target'] is not None})}
    settings = {'MOP_MONITORING_PRESET_TEST_ENABLED': 'false',
                'MOP_MONITORING_PRESET_TARGETS': json.dumps(config, separators=(',', ':')),
                'MOP_MONITORING_PRESET_ALLOWED_ENDPOINTS': '[]', 'MOP_MONITORING_PRESET_ALLOWED_TARGETS': '[]'}
    files = {'targets.disabled.json': json.dumps(config, ensure_ascii=False, indent=2) + '\n',
             'allowlist.review.json': json.dumps(review, indent=2) + '\n',
             'server.env.sh.example': '# POSIX shell example; tests and collection remain disabled. Review before use.\n' + ''.join(f'export {key}={shlex.quote(value)}\n' for key, value in settings.items()),
             'requirements.json': json.dumps(plan['requirements'], ensure_ascii=False, indent=2) + '\n'}
    destination = Path(directory)
    try:
        destination.mkdir()  # Fails for all existing paths, including symlinks.
    except OSError:
        presets.fail('OUTPUT_DIRECTORY_MUST_BE_NEW')
    try:
        for name, content in files.items():
            with (destination / name).open('x', encoding='utf-8') as handle:
                handle.write(content)
        # Completion marker last. An interrupted write must not look complete.
        with (destination / 'plan.json').open('x', encoding='utf-8') as handle:
            handle.write(json.dumps(public_summary(plan), ensure_ascii=False, indent=2) + '\n')
    except OSError:
        presets.fail('OUTPUT_INCOMPLETE_USE_NEW_DIRECTORY')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inventory', required=True)
    parser.add_argument('--output-dir')
    parser.add_argument('--previous-config', help='Optional previous v1 configuration for a read-only change preview')
    args = parser.parse_args(argv)
    try:
        plan = compile_inventory(presets.load_json(args.inventory))
        if args.previous_config and plan['valid']:
            plan['diff'] = compare_configurations(presets.load_json(args.previous_config), plan['configuration'])
        if args.output_dir and plan['valid']:
            write_bundle(plan, args.output_dir)
        print(json.dumps(public_summary(plan), ensure_ascii=False, indent=2))
        return 0 if plan['valid'] else 2
    except (presets.Invalid, OSError, RecursionError) as error:
        print(json.dumps({'valid': False, 'diagnostics': [{'code': str(error) if isinstance(error, presets.Invalid) else 'INVENTORY_READ_FAILED'}], 'network_accessed': False}))
        return 2


if __name__ == '__main__':
    sys.exit(main())

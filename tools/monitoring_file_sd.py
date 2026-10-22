"""Render reviewed monitoring targets as Prometheus file discovery artifacts.

Rendering never starts a collector or publishes into a running directory. Official
configuration validation is optional and uses only an explicitly selected local tool.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile

import monitoring_presets as presets


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def render(config, refresh_seconds):
    config = presets.validate(config)
    if type(refresh_seconds) is not int or not 1 <= refresh_seconds <= 300:
        presets.fail('FILE_SD_REFRESH_REQUIRED')
    static = presets.render(config)
    groups = {}
    for job in static['prometheus']['scrape_configs']:
        entry = job['static_configs'][0]
        template = {key: value for key, value in job.items() if key not in ('job_name', 'static_configs')}
        # Keep each tenant separate even when transport settings match. Prometheus
        # labels still do not provide an authorization boundary for its query API.
        group_key = {'tenant_id': entry['labels']['mop_tenant_id'], 'preset_id': entry['labels']['mop_preset'], 'template': template}
        name = 'mop_sd_' + digest(group_key)
        group = groups.setdefault(name, {'key': group_key, 'entries': []})
        if group['key'] != group_key:
            presets.fail('GROUP_HASH_COLLISION')
        group['entries'].append(copy.deepcopy(entry))

    jobs, target_files = [], {}
    for name in sorted(groups):
        group = groups[name]
        relative = f'targets/{name}.json'
        entries = sorted(group['entries'], key=lambda entry: entry['labels']['mop_target_id'])
        target_files[relative] = entries
        job = copy.deepcopy(group['key']['template'])
        job.update(job_name=name, file_sd_configs=[{'files': [relative], 'refresh_interval': f'{refresh_seconds}s'}])
        jobs.append(job)
    prometheus = {'scrape_configs': jobs}
    return {'version': 1, 'configuration_sha256': presets.fingerprint(config),
            'prometheus': prometheus, 'prometheus_config_sha256': digest(prometheus),
            'file_sd_files': target_files,
            'file_sd_sha256': {name: digest(value) for name, value in target_files.items()},
            'blackbox': static['blackbox'], 'zabbix_handoffs': sorted(static['zabbix_handoffs'], key=lambda row: row['id']),
            'enabled_prometheus_target_count': sum(len(value) for value in target_files.values()),
            'job_count': len(jobs), 'refresh_seconds': refresh_seconds,
            'network_accessed': False, 'applied': False, 'continuous_collection_verified': False}


def summary(bundle):
    return {key: value for key, value in bundle.items() if key not in ('prometheus', 'file_sd_files', 'blackbox', 'zabbix_handoffs')}


def changes(previous, current):
    """Describe a candidate update; never publish or reload it."""
    before, after = previous['file_sd_sha256'], current['file_sd_sha256']
    return {'configuration_reload_required': previous['prometheus_config_sha256'] != current['prometheus_config_sha256'],
            'added_files': sorted(after.keys() - before.keys()),
            'removed_files': sorted(before.keys() - after.keys()),
            'updated_files': sorted(name for name in before.keys() & after.keys() if before[name] != after[name]),
            'applied': False}


def write_bundle(bundle, directory):
    # Reject an accidentally modified bundle before creating any files.
    if digest(bundle['prometheus']) != bundle['prometheus_config_sha256'] or set(bundle['file_sd_files']) != set(bundle['file_sd_sha256']):
        presets.fail('FILE_SD_BUNDLE_CHANGED')
    for name, value in bundle['file_sd_files'].items():
        if not isinstance(name, str) or not re.fullmatch(r'targets/mop_sd_[0-9a-f]{64}\.json', name) or digest(value) != bundle['file_sd_sha256'][name]:
            presets.fail('FILE_SD_BUNDLE_CHANGED')
    destination = Path(directory)
    try:
        destination.mkdir()
    except OSError:
        presets.fail('OUTPUT_DIRECTORY_MUST_BE_NEW')
    try:
        (destination / 'targets').mkdir()
        files = {'prometheus.json': bundle['prometheus'], 'blackbox.json': bundle['blackbox'],
                 'zabbix-handoffs.json': bundle['zabbix_handoffs'], **bundle['file_sd_files']}
        for name, value in files.items():
            with (destination / name).open('x', encoding='utf-8') as handle:
                handle.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
        with (destination / 'manifest.json').open('x', encoding='utf-8') as handle:
            handle.write(json.dumps(summary(bundle), ensure_ascii=False, indent=2) + '\n')
    except OSError:
        presets.fail('OUTPUT_INCOMPLETE_USE_NEW_DIRECTORY')


def official_check(config, refresh_seconds, executable):
    bundle = render(config, refresh_seconds)
    result = {'configuration': 'PASSED', 'file_sd_layout': 'PASSED', 'official_config_check': 'NOT_RUN',
              'job_count': bundle['job_count'], 'target_count': bundle['enabled_prometheus_target_count'],
              'target_network_tested': False, 'applied': False, 'continuous_collection_verified': False}
    if not executable:
        return {**result, 'reason': 'PROMTOOL_NOT_CONFIGURED'}
    path = Path(executable)
    if not path.is_absolute() or not path.is_file() or not os.access(path, os.X_OK):
        return {**result, 'reason': 'PROMTOOL_NOT_AVAILABLE'}
    with tempfile.TemporaryDirectory(prefix='mop-file-sd-check-') as directory:
        destination = Path(directory) / 'candidate'
        write_bundle(bundle, destination)
        try:
            # No shell, raw tool output, inherited stdin, or uncontrolled SD source.
            completed = subprocess.run([str(path), 'check', 'config', '--lint-fatal', str(destination / 'prometheus.json')],
                                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       timeout=15, check=False)
            if completed.returncode:
                return {**result, 'official_config_check': 'FAILED', 'reason': 'PROMTOOL_REJECTED_CONFIG'}
            return {**result, 'official_config_check': 'PASSED'}
        except subprocess.TimeoutExpired:
            return {**result, 'official_config_check': 'FAILED', 'reason': 'PROMTOOL_TIMEOUT'}
        except OSError:
            return {**result, 'reason': 'PROMTOOL_START_FAILED'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['render', 'check'])
    parser.add_argument('--config', required=True)
    parser.add_argument('--refresh-seconds', type=int, required=True)
    parser.add_argument('--output-dir')
    parser.add_argument('--previous-config')
    parser.add_argument('--previous-refresh-seconds', type=int)
    parser.add_argument('--promtool')
    args = parser.parse_args(argv)
    try:
        config = presets.load_json(args.config)
        if args.action == 'check':
            if args.output_dir or args.previous_config or args.previous_refresh_seconds is not None:
                presets.fail('CHECK_DOES_NOT_WRITE_BUNDLE')
            result = official_check(config, args.refresh_seconds, args.promtool)
            code = 0 if result['official_config_check'] == 'PASSED' else 2
        else:
            if args.promtool:
                presets.fail('RENDER_DOES_NOT_RUN_EXTERNAL_TOOL')
            bundle = render(config, args.refresh_seconds)
            result = summary(bundle)
            if (args.previous_config is None) != (args.previous_refresh_seconds is None):
                presets.fail('PREVIOUS_CONFIG_AND_REFRESH_REQUIRED_TOGETHER')
            if args.previous_config:
                previous = render(presets.load_json(args.previous_config), args.previous_refresh_seconds)
                result['changes'] = changes(previous, bundle)
            if args.output_dir:
                write_bundle(bundle, args.output_dir)
            code = 0
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return code
    except (presets.Invalid, OSError, RecursionError) as error:
        print(json.dumps({'status': 'FAILED', 'reason': str(error) if isinstance(error, presets.Invalid) else 'CONFIG_READ_FAILED', 'applied': False}))
        return 2


if __name__ == '__main__':
    sys.exit(main())

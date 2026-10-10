"""Run explicit device probes from the designated host and Zabbix network namespace."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import shlex
import sys

from device_network_probe import timestamp, validate_manifest

ROOT = Path(__file__).resolve().parents[1]
CONTAINER = 'mop-lan-zabbix-server'


def validate_collector(value):
    if (not isinstance(value, dict) or not isinstance(value.get('labels'), dict)
            or value['labels'].get('mop.project') != 'mop-lan-test'
            or value.get('name') != '/' + CONTAINER
            or value.get('running') is not True
            or type(value.get('pid')) is not int or value['pid'] <= 1):
        raise ValueError('Owned running collector required')
    return value


def exit_status(report):
    if any(item['status'] != 'COMPLETED' for item in report['vantage_points'].values()):
        return 1
    if any(device['network_observation'] != 'RESPONSE_OBSERVED'
           for item in report['vantage_points'].values() for device in item['probe']['devices']):
        return 2
    return 0


def inspect(manifest):
    import paramiko
    manifest = validate_manifest(manifest)
    cfg = json.loads((ROOT / 'deploy/private/lan-test-connection.json').read_text(encoding='utf-8-sig'))
    if cfg['environment'] != manifest['environment'] or cfg['host'] != '192.168.1.215':
        raise ValueError('Unexpected deployment destination')
    client = paramiko.SSHClient()
    client.load_host_keys(str(ROOT / 'deploy/private/lan-test-known_hosts'))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    trusted = client.get_host_keys().lookup(cfg['host'])
    if not trusted or any(base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')
                          != cfg['expected_host_key_sha256'] for key in trusted.values()):
        raise ValueError('Pinned SSH host key mismatch')

    def run(args, *, privileged=False):
        command = shlex.join(args)
        if privileged:
            command = "sudo -S -p '' -- " + command
        # Four workers; bound the channel timeout for the validated inventory size.
        budget = max(45, 16 * ((len(manifest['devices']) + 3) // 4) + 15)
        stdin, stdout, stderr = client.exec_command(command, timeout=budget)
        if privileged:
            stdin.write(cfg['password'] + '\n')
            stdin.flush()
        stdin.channel.shutdown_write()
        output = stdout.read(262145)
        # Never include stderr or server error text in logs/reports.
        stderr.read(8192)
        if len(output) > 262144 or stdout.channel.recv_exit_status() != 0:
            raise RuntimeError('Read-only probe operation failed')
        return output.decode('utf-8')

    def collector():
        template = ('{"name":{{json .Name}},"id":{{json .Id}},"image":{{json .Image}},'
                    '"labels":{{json .Config.Labels}},"running":{{json .State.Running}},'
                    '"pid":{{json .State.Pid}},"started_at":{{json .State.StartedAt}}}')
        value = json.loads(run(['docker', 'inspect', '--format', template, CONTAINER], privileged=True))
        return validate_collector(value)

    report = {'schema_version': 1, 'started_at': timestamp(), 'environment': manifest['environment'],
              'host': cfg['host'], 'read_only': True, 'credentials_output': False,
              'manifest_sha256': hashlib.sha256(json.dumps(manifest, ensure_ascii=True,
                                                          sort_keys=True).encode()).hexdigest(),
              'vantage_points': {}, 'monitoring_ready': False,
              'mop_business_monitoring': 'CONFIG_PENDING', 'formal_contract_acceptance': 'NOT_RUN'}
    source = (ROOT / 'tools/device_network_probe.py').read_text(encoding='utf-8')
    report['probe_source_sha256'] = hashlib.sha256(source.encode()).hexdigest()
    payload = json.dumps(manifest, ensure_ascii=True)
    try:
        client.connect(cfg['host'], username=cfg['username'], password=cfg['password'],
                       look_for_keys=False, allow_agent=False, timeout=15,
                       auth_timeout=15, banner_timeout=15)
        owner = json.loads(run(['cat', '/opt/mop-lan-test/owner.json']))
        if owner.get('project') != 'mop-lan-test' or owner.get('environment') != manifest['environment']:
            raise ValueError('Project ownership mismatch')
        for vantage in ('test_server', 'zabbix_network_namespace'):
            try:
                before = collector() if vantage == 'zabbix_network_namespace' else None
                command = ['python3', '-c', source, payload]
                if before:
                    command = ['nsenter', '--target', str(before['pid']), '--net', '--', *command]
                probe = json.loads(run(command, privileged=before is not None))
                if before and before != collector():
                    raise RuntimeError('Collector changed during probe')
                if ([row['key'] for row in probe['devices']] != [row['key'] for row in manifest['devices']]
                        or any(row['ip'] != expected['ip'] for row, expected in
                               zip(probe['devices'], manifest['devices']))):
                    raise ValueError('Probe target mismatch')
                report['vantage_points'][vantage] = {'status': 'COMPLETED', 'probe': probe}
                if before:
                    report['vantage_points'][vantage]['collector'] = {
                        k: before[k] for k in ('name', 'id', 'image', 'pid', 'started_at')}
                    report['vantage_points'][vantage]['execution_context'] = (
                        'host Python and ping in collector network namespace; not Zabbix item execution')
            except Exception as failure:
                report['vantage_points'][vantage] = {'status': 'FAILED',
                                                    'error_class': type(failure).__name__,
                                                    'details': 'omitted', 'probe': None}
        report['completed_at'] = timestamp()
        report['network_exit_status'] = exit_status(report)
        return report
    finally:
        client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, default=ROOT / 'deploy/lan-test/devices.json')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    # Every invocation writes a new report; old successful evidence must not mask failure.
    report = {'schema_version': 1, 'started_at': timestamp(), 'status': 'FAILED',
              'monitoring_ready': False, 'read_only': True, 'credentials_output': False}
    code = 1
    try:
        data = args.manifest.read_bytes()
        if len(data) > 32768:
            raise ValueError('Manifest too large')
        report = inspect(json.loads(data))
        code = exit_status(report)
    except Exception as failure:
        report.update(error_class=type(failure).__name__, details='omitted', completed_at=timestamp())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'network_exit_status': code, 'monitoring_ready': False,
                      'report': str(args.output)}, ensure_ascii=True))
    return code


if __name__ == '__main__':
    sys.exit(main())

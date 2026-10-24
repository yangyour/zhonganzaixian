"""Read-only Huawei SNMP diagnostics through the designated pinned SSH host.

Credentials must be explicitly supplied in a Git-ignored private JSON file.
The MIB documentation's example is never an automatic credential fallback.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex

from huawei_snmp_probe import TARGET
from lan_monitoring_zabbix import LanZabbix, ROOT

CONTAINER = 'mop-lan-zabbix-server'
COLUMNS = {
    'cpu': '1.3.6.1.4.1.2011.5.25.31.1.1.1.1.5',
    'memory': '1.3.6.1.4.1.2011.5.25.31.1.1.1.1.7',
    'temperature': '1.3.6.1.4.1.2011.5.25.31.1.1.1.1.11',
    'entity_name': '1.3.6.1.2.1.47.1.1.1.1.7',
    'entity_class': '1.3.6.1.2.1.47.1.1.1.1.5',
    'model': '1.3.6.1.2.1.47.1.1.1.1.13',
    'if_name': '1.3.6.1.2.1.31.1.1.1.1',
    'if_oper_status': '1.3.6.1.2.1.2.2.1.8',
    'if_high_speed': '1.3.6.1.2.1.31.1.1.1.15',
    'if_hc_in_octets': '1.3.6.1.2.1.31.1.1.1.6',
    'if_hc_out_octets': '1.3.6.1.2.1.31.1.1.1.10',
    'if_counter_discontinuity': '1.3.6.1.2.1.31.1.1.1.19',
    'optical_rx_power': '1.3.6.1.4.1.2011.5.25.31.1.1.3.1.8',
    'optical_tx_power': '1.3.6.1.4.1.2011.5.25.31.1.1.3.1.9',
    'poe_used_power': '1.3.6.1.4.1.2011.5.25.195.5.1.6',
}


def private_config(path):
    path = path.resolve()
    if not path.is_relative_to((ROOT / 'deploy/private').resolve()):
        raise ValueError('Credential file must be in deploy/private')
    raw = path.read_bytes()
    if len(raw) > 4096:
        raise ValueError('Credential configuration too large')
    value = json.loads(raw.decode('utf-8-sig'))
    if (not isinstance(value, dict) or set(value) != {'target', 'version', 'community'}
            or value['target'] != TARGET or value['version'] != '2c'
            or not isinstance(value['community'], str) or not 1 <= len(value['community'].encode()) <= 255
            or any(ord(c) < 32 or ord(c) == 127 for c in value['community'])):
        raise ValueError('Invalid fixed-target SNMP credentials')
    return {**value, 'columns': list(COLUMNS.values())}


def inspect(config):
    client = LanZabbix()
    try:
        def run(args, payload=None, privileged=False):
            command = shlex.join(args)
            if privileged:
                command = "sudo -S -p '' -- " + command
            stdin, stdout, stderr = client.ssh.exec_command(command, timeout=55)
            if privileged:
                stdin.write(json.loads((ROOT / 'deploy/private/lan-test-connection.json').read_text(
                    encoding='utf-8-sig'))['password'] + '\n')
            if payload is not None:
                stdin.write(json.dumps(payload))
            stdin.flush()
            stdin.channel.shutdown_write()
            raw = stdout.read(262145)
            stderr.read(8192)
            if len(raw) > 262144 or stdout.channel.recv_exit_status():
                raise RuntimeError('Read-only diagnostic operation failed')
            return json.loads(raw)

        owner = run(['cat', '/opt/mop-lan-test/owner.json'])
        if owner.get('project') != 'mop-lan-test' or owner.get('environment') != 'lan-test-20261008':
            raise ValueError('Project ownership mismatch')

        def collector():
            template = ('{"name":{{json .Name}},"id":{{json .Id}},"image":{{json .Image}},'
                        '"labels":{{json .Config.Labels}},"running":{{json .State.Running}},'
                        '"pid":{{json .State.Pid}},"started_at":{{json .State.StartedAt}}}')
            value = run(['docker', 'inspect', '--format', template, CONTAINER], privileged=True)
            if (value.get('name') != '/' + CONTAINER or value.get('running') is not True
                    or value.get('labels', {}).get('mop.project') != 'mop-lan-test'
                    or type(value.get('pid')) is not int or value['pid'] <= 1):
                raise ValueError('Owned running collector required')
            return value

        source = (ROOT / 'tools/huawei_snmp_probe.py').read_text(encoding='utf-8')
        report = {'environment': 'lan-test-20261008', 'target': TARGET,
                  'source_sha256': hashlib.sha256(source.encode()).hexdigest(),
                  'protocol': 'SNMP_V2C', 'credential_reference': 'deploy/private/huawei-snmp.json',
                  'read_only': True, 'credentials_output': False, 'resource_writes': 0,
                  'monitoring_ready': False, 'persistent_collection': 'NOT_CONFIGURED',
                  'formal_contract_acceptance': 'NOT_RUN', 'vantage_points': {}}
        for vantage in ('test_server', 'zabbix_network_namespace'):
            try:
                before = collector() if vantage == 'zabbix_network_namespace' else None
                args = ['python3', '-c', source]
                if before:
                    args = ['nsenter', '--target', str(before['pid']), '--net', '--', *args]
                result = run(args, config, privileged=before is not None)
                if before and collector() != before:
                    raise RuntimeError('Collector changed during diagnostic')
                if result.get('target') != TARGET or result.get('version') != '2c':
                    raise ValueError('Unexpected diagnostic response')
                report['vantage_points'][vantage] = result
            except Exception as failure:
                report['vantage_points'][vantage] = {'assessment': 'PROBE_FAILED',
                                                   'error_class': type(failure).__name__,
                                                   'details': 'omitted', 'monitoring_ready': False}
        report['execution_context'] = 'host Python UDP diagnostic; second probe in existing Zabbix network namespace; not Zabbix item execution'
        return report
    finally:
        client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--credentials', type=Path, default=ROOT / 'deploy/private/huawei-snmp.json')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    # Credentials and sessions must never become the output destination.
    output = args.output.resolve()
    if not output.is_relative_to((ROOT / 'docs/evidence').resolve()) or output.exists():
        raise ValueError('A new report in docs/evidence is required')
    report = {'environment': 'lan-test-20261008', 'target': TARGET, 'assessment': 'CONFIG_PENDING',
              'monitoring_ready': False, 'read_only': True, 'credentials_output': False}
    code = 2
    try:
        config = private_config(args.credentials)
        report = inspect(config)
        # The ignored private directory may be a junction to the primary checkout.
        # Validation uses the resolved private root; evidence records its logical reference.
        relative = args.credentials.resolve().relative_to((ROOT / 'deploy/private').resolve())
        report['credential_reference'] = (Path('deploy/private') / relative).as_posix()
        code = 0 if all(v['assessment'] == 'HUAWEI_IDENTITY_READ'
                        for v in report['vantage_points'].values()) else 2
    except FileNotFoundError:
        pass
    except Exception as failure:
        report.update(assessment='PROBE_FAILED', error_class=type(failure).__name__, details='omitted')
        code = 1
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'exit_status': code, 'monitoring_ready': False,
                      'assessments': {k: v['assessment'] for k, v in report.get('vantage_points', {}).items()}}))
    return code


if __name__ == '__main__':
    raise SystemExit(main())

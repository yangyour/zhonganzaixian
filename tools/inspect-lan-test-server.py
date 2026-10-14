"""Read-only status of the separately owned LAN deployment; never print credentials."""
from __future__ import annotations
import argparse
import base64
import datetime
import hashlib
import json
from pathlib import Path
import shlex
import paramiko

ROOT = Path(__file__).resolve().parents[1]
NAMES = ('postgres', 'minio', 'redis', 'rabbitmq', 'api', 'photo-worker',
         'import-worker', 'contact-worker', 'web', 'zabbix-server', 'zabbix-web', 'zabbix-agent')


def inspect(output: Path | None = None, include_export_queue: bool = False) -> dict:
    cfg = json.loads((ROOT / 'deploy/private/lan-test-connection.json').read_text(encoding='utf-8-sig'))
    if cfg['environment'] != 'lan-test-20261008' or cfg['host'] != '192.168.1.215':
        raise ValueError('Unexpected deployment destination')
    client = paramiko.SSHClient()
    client.load_host_keys(str(ROOT / 'deploy/private/lan-test-known_hosts'))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())

    def run(args: list[str], privileged: bool = False) -> str:
        command = shlex.join(args)
        if privileged:
            command = 'sudo -S -p '+shlex.quote('')+' -- '+command
        stdin, stdout, stderr = client.exec_command(command, timeout=30)
        if privileged:
            stdin.write(cfg['password']+'\n')
            stdin.flush()
        stdin.channel.shutdown_write()
        raw = stdout.read().decode('utf-8', 'replace')
        stderr.read()
        if stdout.channel.recv_exit_status():
            raise RuntimeError('Read-only server status operation failed; details omitted')
        return raw

    try:
        client.connect(cfg['host'], username=cfg['username'], password=cfg['password'],
                       look_for_keys=False, allow_agent=False, timeout=15)
        key = client.get_transport().get_remote_server_key()
        fingerprint = base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')
        if fingerprint != cfg['expected_host_key_sha256']:
            raise ValueError('Pinned SSH host key mismatch')
        owner = json.loads(run(['cat', '/opt/mop-lan-test/owner.json']))
        if owner.get('project') != 'mop-lan-test' or owner.get('environment') != cfg['environment']:
            raise ValueError('Project ownership mismatch')
        containers = {}
        storage_providers = {}
        selected_names = NAMES + (('export-preparation-worker',) if include_export_queue else ())
        for suffix in selected_names:
            name = 'mop-lan-'+suffix
            item = json.loads(run(['docker', 'inspect', name], True))[0]
            if item['Config']['Labels'].get('mop.project') != 'mop-lan-test':
                raise ValueError('Container ownership mismatch')
            state = item['State']
            containers[name] = {k: state.get(k) for k in ('Running', 'Status', 'StartedAt', 'ExitCode')}
            containers[name]['image_id'] = item['Image']
            containers[name]['restart_policy'] = item['HostConfig']['RestartPolicy']['Name']
            containers[name]['restart_count'] = item['RestartCount']
            if 'Health' in state:
                containers[name]['health'] = state['Health']['Status']
            if suffix in ('api', 'photo-worker', 'import-worker'):
                storage_providers[name] = run(['docker', 'exec', name, 'sh', '-c',
                                              'printf "%s" "${OBJECT_STORAGE_PROVIDER:-legacy_minio}"'], True).strip()
        readiness = json.loads(run(['curl', '--fail', '--silent', '--max-time', '15',
                                    'http://127.0.0.1:10010/api/v1/health/ready']))['data']
        versions = {
            'docker': run(['docker', 'version', '--format', '{{.Server.Version}}'], True).strip(),
            'postgresql': run(['docker', 'exec', 'mop-lan-postgres', 'psql', '-U', 'postgres', '-At', '-c', 'SHOW server_version;'], True).strip(),
            'redis': run(['docker', 'exec', 'mop-lan-redis', 'redis-server', '--version'], True).strip(),
            'minio': run(['docker', 'exec', 'mop-lan-minio', 'minio', '--version'], True).splitlines()[0],
            'zabbix': run(['docker', 'exec', 'mop-lan-zabbix-server', 'zabbix_server', '--version'], True).splitlines()[0],
            'java': run(['docker', 'exec', 'mop-lan-api', 'java', '--version'], True).splitlines()[0],
            'nginx': run(['docker', 'exec', 'mop-lan-web', 'sh', '-c', 'nginx -v 2>&1'], True).strip(),
            'rabbitmq': run(['docker', 'exec', 'mop-lan-rabbitmq', 'rabbitmq-diagnostics', '-q', 'server_version'], True).strip(),
        }
        history = run(['docker', 'exec', 'mop-lan-postgres', 'psql', '-U', 'postgres', '-d', 'mop_test', '-At', '-c',
                       'BEGIN READ ONLY; SELECT count(*) FROM flyway_schema_history WHERE success; COMMIT;'], True).strip().splitlines()
        schema_count = next(int(line) for line in history if line.isdigit())
        report = {
            'verified_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'environment': cfg['environment'], 'host': cfg['host'], 'ssh_authenticated': True,
            'host_key_sha256': fingerprint, 'project': owner['project'],
            'public_origin': 'http://'+cfg['host']+':10010',
            'zabbix_origin': 'http://'+cfg['host']+':10020',
            'containers': containers, 'versions': versions, 'schema_version': schema_count,
            'storage_providers': storage_providers,
            'all_12_containers_running': all(containers['mop-lan-'+name]['Running'] for name in NAMES),
            'selected_container_count': len(containers),
            'all_selected_containers_running': all(x['Running'] for x in containers.values()),
            'export_preparation_queue_included': include_export_queue,
            'ready': readiness.get('ready') is True, 'mode': readiness.get('mode'),
            'durable': readiness.get('durable'), 'photos_ready': readiness['photos']['ready'],
            'asset_source_storage_ready': readiness['asset_imports']['ready'],
            'production_ready': readiness.get('production_ready'),
            'formal_contract_acceptance': 'NOT_RUN', 'read_only': True, 'credentials_output': False,
        }
        if output is not None:
            output.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
        return report
    finally:
        client.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    parser.add_argument('--include-export-queue', action='store_true')
    args = parser.parse_args()
    try:
        report = inspect(args.output, args.include_export_queue)
        print(json.dumps({k: report[k] for k in ('environment', 'ssh_authenticated', 'all_12_containers_running', 'selected_container_count', 'all_selected_containers_running', 'ready', 'schema_version', 'production_ready')}))
        raise SystemExit(0 if report['all_selected_containers_running'] and report['ready'] else 1)
    except Exception as failure:
        print(json.dumps({'status': 'FAILED', 'exception': type(failure).__name__, 'details': 'omitted'}))
        raise SystemExit(1)

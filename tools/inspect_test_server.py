"""Read-only inventory. Credentials stay in private configuration, never output."""
import argparse
import hashlib
import ipaddress
import json
from pathlib import Path
import paramiko

ROOT = Path(__file__).resolve().parents[1]

def connection_values():
    values = [line.strip() for line in (ROOT / 'deploy/private/develop.txt').read_text(encoding='utf-8-sig').splitlines() if line.strip()]
    if len(values) != 4:
        raise ValueError('Private develop.txt must contain description, host, user, password; content is never executed')
    ipaddress.ip_address(values[1])
    return values[1], values[2], values[3]

def connect():
    host, user, password = connection_values()
    client = paramiko.SSHClient()
    known_hosts = ROOT / 'deploy/private/known_hosts'
    try:
        if not known_hosts.is_file():
            raise ValueError('Pinned SSH host keys are required; automatic enrollment is disabled')
        client.load_host_keys(str(known_hosts))
        client.set_missing_host_key_policy(paramiko.RejectPolicy())
        if not client.get_host_keys().lookup(host):
            raise ValueError('The designated SSH host has no pinned key; connection refused')
        client.connect(host, username=user, password=password, timeout=15, auth_timeout=15, banner_timeout=15, look_for_keys=False, allow_agent=False)
    except Exception:
        client.close()
        raise
    return client

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default=str(ROOT / '.local/server-inventory-raw.json'))
    args = parser.parse_args()
    commands = {
        'os': 'cat /etc/os-release',
        'docker_server_version': "docker version --format '{{.Server.Version}}'",
        'containers': "docker ps -a --format '{{json .Names}}\t{{json .Image}}\t{{json .Status}}\t{{json .Ports}}'",
        'listening_ports': 'ss -lntp',
        'running_services': 'systemctl list-units --type=service --state=running --no-pager --no-legend',
        'process_names': 'ps -eo comm --no-headers',
        'component_files': "find /opt /srv /data -maxdepth 3 -type f \\( -name 'docker-compose.yml' -o -name 'compose.yml' -o -name 'compose.yaml' \\) 2>/dev/null",
        'capacity': 'df -h /',
    }
    results = {}
    client = connect()
    try:
        public_key = client.get_transport().get_remote_server_key()
        results['host_key'] = {'type': public_key.get_name(), 'sha256_hex': hashlib.sha256(public_key.asbytes()).hexdigest(), 'enrollment': 'first_use_pinned_private_known_hosts'}
        for name, command in commands.items():
            _, stdout, stderr = client.exec_command(command, timeout=20)
            output = stdout.read().decode('utf-8', errors='replace')
            error = stderr.read().decode('utf-8', errors='replace')
            results[name] = {'exit_code': stdout.channel.recv_exit_status(), 'stdout': output[:24000], 'stderr': error[:2000]}
    finally:
        client.close()
    target = Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    selected = {'shared-postgres', 'shared-redis', 'zhsx-rabbitmq', 'hltk-keycloak', 'shared-minio', 'zabbix-msp-prototype'}
    containers = []
    for line in results['containers']['stdout'].splitlines():
        fields = [json.loads(value) for value in line.split('\t')]
        if fields[0] in selected:
            containers.append(dict(zip(['name', 'image', 'status', 'ports'], fields)))
    summary = {'inventory_date': '2026-09-30', 'host_key': results['host_key'], 'os': results['os']['stdout'], 'docker_version': results['docker_server_version']['stdout'].strip(), 'selected_containers': containers, 'raw_inventory': 'ignored_local_file', 'read_only': True}
    (ROOT / 'docs/environment/server-inventory.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()

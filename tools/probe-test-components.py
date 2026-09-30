"""Probe selected existing components without printing their environment secrets."""
import json
import shlex
from pathlib import Path
from inspect_test_server import connect, ROOT

def run(client, command):
    _, stdout, stderr = client.exec_command(command, timeout=20)
    out = stdout.read().decode('utf-8', errors='replace')
    err = stderr.read().decode('utf-8', errors='replace')
    return stdout.channel.recv_exit_status(), out, err

def main():
    client = connect()
    private = {}
    summary = {}
    try:
        for name in ['shared-postgres', 'shared-redis', 'zhsx-rabbitmq', 'hltk-keycloak', 'shared-minio']:
            code, out, _ = run(client, 'docker inspect --format ' + shlex.quote('{{json .Config.Env}}') + ' ' + shlex.quote(name))
            if code:
                summary[name] = {'status': 'inspect_failed'}
                continue
            values = dict(item.split('=', 1) for item in json.loads(out) if '=' in item)
            private[name] = {'environment': values}
            summary[name] = {'status': 'found', 'environment_keys': sorted(values)}
        probes = {
            'postgres_version': "docker exec shared-postgres psql -U postgres -d postgres -At -c 'select version()'",
            'postgres_databases': "docker exec shared-postgres psql -U postgres -d postgres -At -c 'select datname from pg_database where datistemplate=false'",
            'redis_version': 'docker exec shared-redis redis-server --version',
            'rabbitmq_version': 'docker exec zhsx-rabbitmq rabbitmqctl version',
            'keycloak_discovery': 'curl -sS --max-time 10 http://127.0.0.1:12110/realms/master/.well-known/openid-configuration',
            'minio_version': 'docker exec shared-minio minio --version',
        }
        for name, command in probes.items():
            code, out, err = run(client, command)
            summary[name] = {'exit_code': code, 'stdout': out[:5000], 'stderr': err[:500]}
        code, out, _ = run(client, 'docker inspect --format ' + shlex.quote('{{json .Config.Cmd}}') + ' shared-redis')
        if not code:
            private['shared-redis']['command'] = json.loads(out)
        private_path = ROOT / 'deploy/private/existing-components.json'
        private_path.write_text(json.dumps(private, indent=2) + '\n', encoding='utf-8')
        (ROOT / 'docs/environment/component-probe.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    finally:
        client.close()

if __name__ == '__main__':
    main()

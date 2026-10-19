"""Run the local API against the designated LAN PostgreSQL and SSH WeCom egress.

Requires tools/wecom-dev-tunnel.py. Does not migrate, seed or start infrastructure.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dispatch', action='store_true')
    parser.add_argument('--login', action='store_true')
    parser.add_argument('--port', type=int, default=18038)
    args = parser.parse_args()
    cfg = json.loads((ROOT / 'deploy/private/lan-test-runtime.json').read_text(encoding='utf-8-sig'))
    wecom = json.loads((ROOT / 'deploy/private/wecom-app.json').read_text(encoding='utf-8'))
    if cfg['environment'] != 'lan-test-20261008' or cfg['project'] != 'mop-lan-test':
        raise ValueError('Unexpected designated development environment')
    env = {k: v for k, v in os.environ.items() if not k.startswith(('MOP_', 'SPRING_', 'OBJECT_STORAGE_'))}
    env.update({
        'SPRING_PROFILES_ACTIVE': 'remote_test',
        'SPRING_DATASOURCE_URL': 'jdbc:postgresql://127.0.0.1:15438/mop_test',
        'SPRING_DATASOURCE_USERNAME': 'mop_test_runtime',
        'SPRING_DATASOURCE_PASSWORD': cfg['runtime_password'],
        'MOP_AUTH_MODE': 'project_local', 'MOP_AUTH_COMMAND_KEY': cfg['auth_command_key'],
        'MOP_OIDC_ENABLED': 'false',
        'MOP_WECOM_ENABLED': 'true',
        'MOP_WECOM_CONFIG_FILE': str(ROOT / 'deploy/private/wecom-app.json'),
        'MOP_WECOM_TENANT_ID': '55000000-0000-4000-8000-000000000001',
        'MOP_WECOM_PROXY_URL': 'http://127.0.0.1:18889',
        'MOP_WECOM_TEST_USER_ID': wecom['user_id'],
        'MOP_WECOM_LOGIN_ENABLED': str(args.login).lower(),
        'MOP_WECOM_DISPATCH_ENABLED': str(args.dispatch).lower(),
        'MOP_WECOM_CALLBACK_URL': 'http://' + wecom['domain'] + '/api/v1/auth/wecom/callback',
        'MOP_WECOM_ALLOW_HTTP_CALLBACK': 'true',
        'SERVER_PORT': str(args.port), 'SERVER_ADDRESS': '127.0.0.1',
    })
    dependencies = (ROOT / '.local/api-classpath.txt').read_text(encoding='utf-8-sig').strip()
    cp = os.pathsep.join([str(ROOT / 'server' / module / 'target/classes') for module in ('api', 'adapters', 'persistence', 'domain')]) + os.pathsep + dependencies
    print(json.dumps({'environment': cfg['environment'], 'local_port': args.port, 'login_enabled': args.login, 'dispatch_enabled': args.dispatch, 'secrets_output': False}), flush=True)
    raise SystemExit(subprocess.call(['java', '-cp', cp, 'cn.mop.api.MopApplication'], cwd=ROOT, env=env))


if __name__ == '__main__':
    main()

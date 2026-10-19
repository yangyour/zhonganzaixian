"""Local email API using the designated existing LAN PostgreSQL through SSH.

Requires an existing loopback forward to the designated project PG. No migration,
infrastructure startup or mailbox send is performed by this launcher itself.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
from email_private import windows_password

ROOT=Path(__file__).resolve().parents[1]


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--port',type=int,default=18039)
    parser.add_argument('--pg-port',type=int,default=15439)
    parser.add_argument('--dispatch',action='store_true')
    parser.add_argument('--probe',action='store_true')
    args=parser.parse_args()
    cfg=json.loads((ROOT/'deploy/private/lan-test-runtime.json').read_text(encoding='utf-8-sig'))
    if cfg['environment']!='lan-test-20261008' or cfg['project']!='mop-lan-test' or not 1024<=args.port<=65535 or not 1024<=args.pg_port<=65535:
        raise ValueError('Unexpected designated development environment or loopback port')
    env={k:v for k,v in os.environ.items() if not k.startswith(('MOP_','SPRING_','OBJECT_STORAGE_'))}
    env.update({'SPRING_PROFILES_ACTIVE':'remote_test','SPRING_DATASOURCE_URL':f'jdbc:postgresql://127.0.0.1:{args.pg_port}/mop_test',
                'SPRING_DATASOURCE_USERNAME':'mop_test_runtime','SPRING_DATASOURCE_PASSWORD':cfg['runtime_password'],
                'MOP_AUTH_MODE':'project_local','MOP_AUTH_COMMAND_KEY':cfg['auth_command_key'],'MOP_OIDC_ENABLED':'false',
                'MOP_EMAIL_ENABLED':'true','MOP_EMAIL_CONFIG_FILE':str(ROOT/'deploy/private/email-runtime.json'),
                'MOP_EMAIL_PASSWORD':windows_password(ROOT/'deploy/private/mail-credential.dpapi'),
                'MOP_EMAIL_TENANT_ID':'55000000-0000-4000-8000-000000000001','MOP_EMAIL_DISPATCH_ENABLED':str(args.dispatch).lower(),
                'SERVER_ADDRESS':'127.0.0.1','SERVER_PORT':str(args.port)})
    dependency_file=ROOT/'server/api/target/email-classpath.txt'
    dependencies=dependency_file.read_text(encoding='utf-8-sig').strip()
    cp=os.pathsep.join(str(ROOT/'server'/module/'target/classes') for module in ('api','adapters','persistence','domain'))+os.pathsep+dependencies
    entry='cn.mop.adapters.email.EmailProbeMain' if args.probe else 'cn.mop.api.MopApplication'
    print(json.dumps({'environment':cfg['environment'],'local_port':args.port,'dispatch_enabled':args.dispatch,'probe_only':args.probe,'secrets_output':False}),flush=True)
    raise SystemExit(subprocess.call(['java','-cp',cp,entry],cwd=ROOT,env=env))


if __name__=='__main__':
    main()

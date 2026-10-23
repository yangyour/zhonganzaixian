"""Release the verified demo affordances without changing other applications or components."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tarfile
import uuid

from demo_environment import Environment, json_text, require
from demo_runtime import load


def release(env):
    root = Path(__file__).resolve().parents[1]
    module = load(env.owner_root / 'tools/update-lan-test-server.py', 'demo_release_update')
    server = env.connect()
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    require(subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', 'server', 'apps/web', 'contracts', 'config'], cwd=root).returncode == 0,
        'Application source must be committed before release')
    require(subprocess.check_output(['git', 'remote', 'get-url', 'origin'], cwd=root, text=True).strip() == 'https://github.com/yangyour/zhonganzaixian.git', 'Repository mismatch')
    jar = root / 'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'
    web = root / 'apps/web/dist'
    manifest = json.loads((web / 'release-manifest.json').read_text(encoding='utf-8'))
    files = {'api.jar': jar, 'web/release-manifest.json': web / 'release-manifest.json'}
    for item in manifest['files']:
        path = web / item['path']
        require(path.is_file() and not path.is_symlink() and hashlib.sha256(path.read_bytes()).hexdigest() == item['sha256'], 'Web artifact mismatch')
        files['web/' + item['path']] = path
    hashes = {name: hashlib.sha256(path.read_bytes()).hexdigest() for name, path in files.items()}
    archive = env.private_root / 'demo-release.tar.gz'
    with tarfile.open(archive, 'w:gz') as tar:
        for name, path in files.items():
            tar.add(path, arcname=name, recursive=False)
    remote = '/opt/mop-lan-test'
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-demo-' + revision[:10]
    release_path = remote + '/releases/' + run_id
    folder = remote + '/private/update-' + run_id
    lock = remote + '/.update-lock'
    candidate = 'mop-lan-update-candidate-' + run_id
    candidate_created = False
    server.run(['mkdir', lock])
    try:
        server.write(lock + '/owner.json', json_text({'run_id': run_id, 'scope': 'demo API/Web only'}))
        before = server.containers()
        items = module.choose(before)
        old = module.overlay(items)
        desired = json.loads(json.dumps(old))
        protected = module.metadata(before, ['mop-lan-api', 'mop-lan-web'])
        history = server.schema()
        require(history == env.state['schema_history'], 'Unexpected migration history')
        server.ready()
        active = json.loads(server.read(remote + '/active-update.json'))
        base = [remote + '/compose.yaml', active['compose_file']]
        snapshots = {path: server.read(path) for path in [remote + '/.env', *base]}
        current_api = next(item for item in items if item['Name'] == '/mop-lan-api')
        previous_release = next(m['Source'] for m in current_api['Mounts'] if m['Destination'] == '/app')
        require(previous_release.startswith(remote + '/releases/'), 'Unexpected previous artifact path')
        server.run(['mkdir', release_path])
        # Copy supporting artifacts only; the new Web tree has no obsolete hashed files.
        command = 'tar --exclude=./web --exclude=./api.jar --exclude=./release.tar.gz -C ' + shlex.quote(previous_release) + ' -cf - . | tar -C ' + shlex.quote(release_path) + ' -xf -'
        server.run(['sh', '-c', command], True)
        server.run(['mkdir', '-m', '700', folder])
        with server.client.open_sftp() as sftp:
            sftp.put(str(archive), folder + '/artifacts.tar.gz')
        server.run(['sha256sum', '--check', '--status'], data=hashlib.sha256(archive.read_bytes()).hexdigest() + '  ' + folder + '/artifacts.tar.gz\n')
        server.run(['tar', '-xzf', folder + '/artifacts.tar.gz', '-C', release_path])
        server.run(['sha256sum', '--check', '--status'], data=''.join(sha + '  ' + release_path + '/' + name + '\n' for name, sha in hashes.items()))
        for name, target in [('api', '/app'), ('web', '/usr/share/nginx/html')]:
            for mount in desired['services'][name]['volumes']:
                if mount['target'] == target:
                    mount['source'] = release_path if name == 'api' else release_path + '/web'
            desired['services'][name]['labels']['mop.source.commit'] = revision
        api_env = desired['services']['api']['environment']
        api_env['MOP_DEMO_CHANNEL_TENANT_IDS'] = ','.join(sorted(env.tenants))
        old_text = module.compose_json(old)
        server.write(folder + '/rollback.json', old_text)
        server.write(folder + '/deploy.json', module.compose_json(desired))
        recovery = {'environment': 'lan-test-20261008', 'source_commit': revision, 'run_id': run_id, 'services': ['api', 'web'],
            'base_files': base, 'compose_file': folder + '/deploy.json', 'rollback_file': folder + '/rollback.json', 'previous_active': active,
            'release': release_path, 'schema': history,
            'recovery_config_sha256': {path: module.digest(raw.encode()) for path, raw in {**snapshots, folder + '/rollback.json': old_text}.items()}}
        server.write(folder + '/transaction.json', json_text(recovery))
        require(not server.run(['ss', '-lntH', 'sport = :18039']).strip(), 'Candidate port occupied')
        candidate_env = module.candidate_environment(api_env)
        candidate_env['MOP_NOTIFICATION_RELAY_ENABLED'] = 'false'
        server.write(folder + '/candidate.env', ''.join(k + '=' + v + '\n' for k, v in candidate_env.items()))
        command = ['docker', 'create', '--name', candidate, '--network', 'host', '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--memory', '1536m', '--tmpfs', '/tmp:rw,nosuid,size=384m',
            '--label', 'mop.project=mop-lan-test', '--env-file', folder + '/candidate.env']
        for mount in desired['services']['api']['volumes']:
            command += ['--mount', 'type=bind,source=' + mount['source'] + ',target=' + mount['target'] + (',readonly' if mount['read_only'] else '')]
        command += [desired['services']['api']['image'], 'java', '-XX:MaxRAMPercentage=60.0', '-jar', '/app/api.jar']
        server.run(command, True)
        candidate_created = True
        server.run(['docker', 'start', candidate], True)
        server.ready(18039)
        probe = """import urllib.request,http.cookiejar,json,sys
cfg=json.load(sys.stdin);base='http://127.0.0.1:18039/api/v1';op=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
csrf=None
def call(path,body=None,method=None):
 global csrf
 h={'Content-Type':'application/json','Origin':'http://127.0.0.1:18039'}
 if csrf:h['X-CSRF-Token']=csrf
 req=urllib.request.Request(base+path,None if body is None else json.dumps(body).encode(),h,method=method)
 return json.load(op.open(req,timeout=20))['data']
csrf=call('/auth/csrf')['csrf_token'];s=call('/auth/local/login',cfg['login']);csrf=s['csrf_token']
s=call('/session/tenant',{'tenant_id':cfg['tenant']});csrf=s['csrf_token'];assert s['demo_examples_enabled'] is True
for page in ['sla-scenarios','sla-reports','operational-comparison','capacity-estimates','observation-window-diagnostics','threshold-diagnostics','contact-import-preview']:
 x=call('/demo/examples/'+page);assert x['available'] and x['tenant_id']==cfg['tenant'] and not x['business_data_written']
for provider in ['email','wecom']:
 x=call('/'+provider+'/status');assert x['tenant_id']==cfg['tenant'] and x['dispatch_enabled'] is False and x['secret_configured'] is False
print('CANDIDATE_DEMO_READS_PASSED')
"""
        login = {k: env.state['users']['main'][k] for k in ('username', 'password')}
        output = server.run(['python3', '-c', probe], data=json_text({'login': login, 'tenant': env.state['contexts']['padmin']['tenant_id']}))
        require('CANDIDATE_DEMO_READS_PASSED' in output, 'Candidate demo behavior failed')
        server.run(['docker', 'rm', '-f', candidate], True)
        candidate_created = False
        server.selected = ['api', 'web']
        def verify():
            ready = server.ready()
            require(module.metadata(server.containers(), ['mop-lan-api', 'mop-lan-web']) == protected, 'Unrelated service changed')
            require(server.schema() == history, 'Schema changed')
            require(all(server.read(path) == raw for path, raw in snapshots.items()), 'Concurrent runtime change')
            served = json.loads(server.run(['curl', '--fail', '--silent', remote_origin() + '/release-manifest.json']))
            require(served['build_id'] == manifest['build_id'], 'Web build mismatch')
            server.atomic(remote + '/active-update.json', json_text(recovery))
            return ready
        module.guarded_switch(server, base + [folder + '/deploy.json'], base + [folder + '/rollback.json'], verify,
            lambda: module.validate_recovery_snapshot(server, recovery))
        env.state['demo_release'] = {'source_commit': revision, 'transaction': folder + '/transaction.json', 'build_id': manifest['build_id'],
            'files_verified': len(hashes), 'other_12_containers_unchanged': True, 'schema_unchanged': True, 'candidate_demo_reads': True}
        env.save()
        return env.state['demo_release']
    finally:
        if candidate_created:
            server.run(['docker', 'rm', '-f', candidate], True, check=False)
        server.run(['rm', '--', lock + '/owner.json'], check=False)
        server.run(['rmdir', '--', lock], check=False)


def remote_origin():
    return 'http://127.0.0.1:10010'


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-root', required=True)
    args = parser.parse_args()
    env = Environment(args.private_root)
    try:
        with env.lock():
            result = release(env)
            require(env.protected_fingerprint() == env.state['protected_before'], 'Protected rows changed')
        print(json_text(result))
    finally:
        env.close()

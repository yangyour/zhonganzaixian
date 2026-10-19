"""Pull, verify and release existing LAN applications; never migrate or install services.

Secrets stay in ignored local files and private remote deployment snapshots. See
docs/test-environment-update.md for recovery after an interrupted invocation.
"""
from __future__ import annotations
import argparse
import base64
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tarfile
import time
import urllib.parse
import uuid
import zipfile
import paramiko

ROOT = Path(__file__).resolve().parents[1]
REMOTE = '/opt/mop-lan-test'
PROJECT = 'mop-lan-test'
ENVIRONMENT = 'lan-test-20261008'
SERVICES = ('api', 'web', 'photo-worker', 'import-worker', 'contact-worker',
            'export-preparation-worker', 'af-collector')
ARTIFACTS = {
    'api.jar': 'server/api/target/mop-api-0.1.0-SNAPSHOT.jar',
    'photo-worker.jar': 'server/worker/target/mop-worker-0.1.0-SNAPSHOT.jar',
    'import-worker.jar': 'server/worker/target/mop-worker-0.1.0-SNAPSHOT-imports.jar',
    'contact-worker.jar': 'server/worker/target/mop-worker-0.1.0-SNAPSHOT-contact-imports.jar',
    'export-worker.jar': 'server/export-preparation-worker/target/mop-export-preparation-worker-0.1.0-SNAPSHOT.jar',
    'server/import-parser/target/parser.jar': 'server/import-parser/target/mop-import-parser-0.1.0-SNAPSHOT.jar',
}


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def emit(stage, **fields):
    print(json.dumps({'stage': stage, **fields}, ensure_ascii=True), flush=True)


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT, text=True).strip()


def synchronize(branch=None):
    require(not git('status', '--porcelain'), 'Working tree must be committed before update')
    current = git('symbolic-ref', '--short', 'HEAD')
    require(not branch or branch == current, 'Switch to the requested branch before update')
    require(git('rev-parse', '--abbrev-ref', '@{upstream}') == 'origin/' + current,
            'Current branch must track the same branch on origin')
    subprocess.run(['git', 'fetch', '--prune', 'origin'], cwd=ROOT, check=True)
    subprocess.run(['git', 'merge', '--ff-only', 'refs/remotes/origin/' + current], cwd=ROOT, check=True)
    revision = git('rev-parse', 'HEAD')
    require(revision == git('rev-parse', 'refs/remotes/origin/' + current),
            'Local-only commits must be pushed before deployment')
    return current, revision


def build(folder):
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(('MOP_', 'SPRING_', 'OBJECT_STORAGE_'))}
    npm = 'npm.cmd' if os.name == 'nt' else 'npm'
    maven = 'mvn.cmd' if os.name == 'nt' else 'mvn'
    commands = [
        ('deployment-tests', [os.sys.executable, '-m', 'unittest', 'discover', '-s', 'tools', '-p', 'test_lan_update.py'], ROOT),
        ('contracts', [npm, 'test'], ROOT),
        ('web-tests', [npm, 'test'], ROOT / 'apps/web'),
        ('java-package', [maven, '-B', 'package'], ROOT),
        ('web-build', [npm, 'run', 'build'], ROOT / 'apps/web'),
        ('web-manifest', ['node', 'tools/verify-web-release.mjs', '--dist', 'apps/web/dist', '--check'], ROOT),
    ]
    for name, args, cwd in commands:
        emit('verify', check=name)
        with (folder / (name + '.log')).open('wb') as log:
            result = subprocess.run(args, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        require(result.returncode == 0, 'Local check failed: ' + name + '; inspect ignored local log')


def artifacts():
    files = dict(ARTIFACTS)
    dist = ROOT / 'apps/web/dist'
    manifest = json.loads((dist / 'release-manifest.json').read_text(encoding='utf-8'))
    for path in dist.rglob('*'):
        require(not path.is_symlink(), 'Symbolic link in Web artifacts')
        if path.is_file():
            name = path.relative_to(dist).as_posix()
            files['web/' + name] = str(path.relative_to(ROOT))
    records = {r['path']: r['sha256'] for r in manifest['files']}
    require(set(files) - set(ARTIFACTS) == {'web/' + n for n in records} | {'web/release-manifest.json'},
            'Unexpected Web artifact set')
    for name, sha in records.items():
        require(digest((dist / name).read_bytes()) == sha, 'Web artifact digest mismatch')
    # Compare every packaged frozen SQL with this exact source tree; never execute it.
    with zipfile.ZipFile(ROOT / ARTIFACTS['api.jar']) as api:
        libraries = [n for n in api.namelist() if re.fullmatch(r'BOOT-INF/lib/mop-persistence-[^/]+\.jar', n)]
        require(len(libraries) == 1, 'Ambiguous packaged persistence module')
        with zipfile.ZipFile(io.BytesIO(api.read(libraries[0]))) as persistence:
            sql = {p.name: p.read_bytes() for p in (ROOT / 'server/persistence/src/main/resources/db/migration').glob('V*__*.sql')}
            packed = {n.rsplit('/', 1)[1]: persistence.read(n) for n in persistence.namelist()
                      if n.startswith('db/migration/') and n.endswith('.sql')}
            require(sql == packed, 'Packaged migrations differ from source')
    hashes = {name: digest((ROOT / path).read_bytes()) for name, path in files.items()}
    return files, hashes, manifest, sorted(int(re.match(r'V(\d+)__', n)[1]) for n in sql)


def callback_parts(url, allow_http):
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == 'https' or allow_http and parsed.scheme == 'http', 'HTTP callback needs explicit test opt-in')
    require(parsed.hostname and re.fullmatch(r'[a-zA-Z0-9.-]+', parsed.hostname)
            and parsed.path == '/api/v1/auth/wecom/callback' and not parsed.query
            and not parsed.fragment and not parsed.username and not parsed.password,
            'Invalid fixed callback URL')
    port = parsed.port or (443 if parsed.scheme == 'https' else 80)
    # This existing listener terminates HTTP only. TLS belongs to the existing external proxy.
    require(parsed.scheme == 'http' and port == 80, 'Existing LAN ingress supports the explicit HTTP:80 test callback only')
    return parsed.hostname, port


def domain_nginx(original, callback, allow_http):
    host, port = callback_parts(callback, allow_http)
    require('$mop_upstream_host' not in original, 'Existing domain mapping must be reviewed before enabling again')
    mapping = ('\n map $http_host $mop_upstream_host { default "192.168.1.215:10010"; '
               f'"{host}" "{host}"; "{host}:80" "{host}"; }}\n'
               ' map $http_host $mop_upstream_port { default 10010; '
               f'"{host}" {port}; "{host}:80" {port}; }}\n')
    updated, count = re.subn(r'http\s*\{', lambda _: 'http {' + mapping, original, count=1)
    require(count == 1, 'Expected one Nginx HTTP block')
    for key, old, new in [('Host', '"192.168.1.215:10010"', '$mop_upstream_host'),
                          ('X-Forwarded-Host', '"192.168.1.215:10010"', '$mop_upstream_host'),
                          ('X-Forwarded-Port', '10010', '$mop_upstream_port')]:
        updated, count = re.subn(r'proxy_set_header\s+' + key + r'\s+' + re.escape(old) + r';',
                                 lambda _: 'proxy_set_header ' + key + ' ' + new + ';', updated)
        require(count == 1, 'Expected fixed Nginx ingress header: ' + key)
    return updated


def environment(item):
    return dict(entry.split('=', 1) for entry in item['Config']['Env'])


def compose_json(value):
    # Compose interpolates even JSON string values. Preserve literal dollars in
    # existing runtime passwords/tokens instead of interpreting them as variables.
    return json.dumps(value).replace('$', '$$')


def pending_deliveries(server, tenant, corp, agent):
    require(str(uuid.UUID(tenant)) == tenant and re.fullmatch(r'ww[A-Za-z0-9]{8,64}', corp)
            and type(agent) is int and agent > 0, 'Invalid WeCom delivery scope')
    # Match exactly the scope used by PostgresWeComRepository.claim. Other
    # synthetic apps/tenants must neither block this app nor be dispatched by it.
    sql = ("BEGIN READ ONLY; SELECT count(*) FROM mop_wecom_delivery WHERE tenant_id='" + tenant
           + "'::uuid AND corp_id='" + corp + "' AND agent_id=" + str(agent)
           + " AND status IN ('QUEUED','PROCESSING'); COMMIT;")
    return int(server.run(['docker', 'exec', 'mop-lan-postgres', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
                           '-U', 'postgres', '-d', 'mop_test', '-c', sql], True).strip())


def overlay(items, release=None, revision=None):
    result = {'services': {}}
    for item in items:
        name = item['Config']['Labels']['com.docker.compose.service']
        require(name in SERVICES, 'Refuse unapproved service')
        mounts = []
        for mount in item['Mounts']:
            if mount['Type'] == 'tmpfs':
                continue
            require(mount['Type'] == 'bind' and mount['Source'].startswith(REMOTE + '/'), 'Unexpected application mount')
            source = mount['Source']
            if release and mount['Destination'] == '/app': source = release
            if release and mount['Destination'] == '/usr/share/nginx/html': source = release + '/web'
            mounts.append({'type': 'bind', 'source': source, 'target': mount['Destination'], 'read_only': not mount['RW']})
        labels = {'mop.project': PROJECT}
        labels['mop.source.commit'] = revision or item['Config']['Labels'].get('mop.source.commit', '')
        env = environment(item)
        # Rollback to pre-integration applications also clears newly introduced switches.
        if not release:
            for key in ('MOP_WECOM_ENABLED', 'MOP_WECOM_LOGIN_ENABLED', 'MOP_WECOM_DISPATCH_ENABLED'):
                env.setdefault(key, 'false')
        result['services'][name] = {'image': item['Image'], 'pull_policy': 'never',
                                    'volumes': mounts, 'environment': env, 'labels': labels}
    return result


class Server:
    def __init__(self):
        self.cfg = json.loads((ROOT / 'deploy/private/lan-test-connection.json').read_text(encoding='utf-8-sig'))
        require(self.cfg['environment'] == ENVIRONMENT and self.cfg['host'] == '192.168.1.215', 'Unexpected destination')
        self.client = paramiko.SSHClient()
        self.client.load_host_keys(str(ROOT / 'deploy/private/lan-test-known_hosts'))
        self.client.set_missing_host_key_policy(paramiko.RejectPolicy())
        self.client.connect(self.cfg['host'], username=self.cfg['username'], password=self.cfg['password'],
                            look_for_keys=False, allow_agent=False, timeout=15)
        key = self.client.get_transport().get_remote_server_key()
        fingerprint = base64.b64encode(hashlib.sha256(key.asbytes()).digest()).decode().rstrip('=')
        require(fingerprint == self.cfg['expected_host_key_sha256'], 'Pinned SSH host key mismatch')
        owner = json.loads(self.run(['cat', REMOTE + '/owner.json']))
        require(owner.get('project') == PROJECT and owner.get('environment') == ENVIRONMENT, 'Project ownership mismatch')

    def run(self, args, privileged=False, data=None, check=True, timeout=45):
        command = shlex.join(args)
        if privileged: command = 'sudo -S -p '+shlex.quote('')+' -- '+command
        stdin, stdout, stderr = self.client.exec_command(command, timeout=timeout)
        if privileged: stdin.write(self.cfg['password'] + '\n'); stdin.flush()
        if data is not None:
            stdin.write(data); stdin.flush()
        stdin.channel.shutdown_write()
        output = stdout.read().decode('utf-8', 'strict')
        stderr.read()  # Never expose raw Docker/configuration diagnostics, which may contain secrets.
        code = stdout.channel.recv_exit_status()
        require(not check or code == 0, 'Remote operation failed; exit ' + str(code) + '; sensitive diagnostics omitted')
        return output if check else (code, output)

    def read(self, path):
        return self.run(['cat', path], True)

    def write(self, path, data, mode=0o600):
        with self.client.open_sftp() as sftp:
            with sftp.file(path, 'wx') as target: target.write(data)
            sftp.chmod(path, mode)

    def atomic(self, path, data):
        temporary = path + '.' + uuid.uuid4().hex
        self.write(temporary, data)
        with self.client.open_sftp() as sftp: sftp.posix_rename(temporary, path)

    def containers(self):
        names = self.run(['docker', 'ps', '-a', '--format', '{{.Names}}'], True).splitlines()
        return json.loads(self.run(['docker', 'inspect', *names], True)) if names else []

    def schema(self):
        text = self.run(['docker', 'exec', 'mop-lan-postgres', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
                         '-U', 'postgres', '-d', 'mop_test', '-c',
                         "BEGIN READ ONLY; SELECT coalesce(json_agg(row_to_json(t) ORDER BY installed_rank),'[]') "
                         "FROM (SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history) t; COMMIT;"], True)
        return json.loads(text)

    def compose(self, files, *args):
        command = ['docker', 'compose', '--project-directory', REMOTE, '--env-file', REMOTE + '/.env', '-p', PROJECT]
        for file in files: command += ['-f', file]
        command += ['--profile', 'af-monitoring', *args]
        return self.run(command, True, timeout=180)

    def ready(self, port=10010, timeout=120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            code, text = self.run(['curl', '--fail', '--silent', '--max-time', '8',
                                   f'http://127.0.0.1:{port}/api/v1/health/ready'], check=False)
            if code == 0:
                try:
                    status = json.loads(text)['data']
                    if status.get('ready') is True and status.get('mode') == 'remote_test' and status.get('durable') is True:
                        return status
                except (ValueError, KeyError): pass
            time.sleep(2)
        raise RuntimeError('Real dependency readiness not confirmed before deadline')


def metadata(items, excluded=()):
    return {x['Name'].lstrip('/'): {'id': x['Id'], 'image': x['Image'], 'started_at': x['State']['StartedAt'],
                                   'restart_count': x['RestartCount'], 'running': x['State']['Running']}
            for x in items if x['Name'].lstrip('/') not in excluded}


def choose(items):
    selected = []
    for name in SERVICES:
        found = [x for x in items if x['Name'] == '/mop-lan-' + name]
        if name in ('af-collector', 'export-preparation-worker') and not found: continue
        require(len(found) == 1, 'Required application missing: ' + name)
        item = found[0]
        require(item['Config']['Labels'].get('mop.project') == PROJECT
                and item['Config']['Labels'].get('com.docker.compose.service') == name
                and item['State']['Running'], 'Application ownership/state mismatch: ' + name)
        selected.append(item)
    return selected


def guarded_switch(server, new_files, old_files, verify):
    """Configuration validation precedes mutation; every partial up is recovered."""
    server.compose(new_files, 'config', '--quiet')
    try:
        server.compose(new_files, 'up', '-d', '--no-deps', '--pull', 'never', *server.selected)
        return verify()
    except Exception:
        emit('rollback', status='STARTED')
        try:
            server.compose(old_files, 'config', '--quiet')
            server.compose(old_files, 'up', '-d', '--no-deps', '--pull', 'never', *server.selected)
            server.ready()
        except Exception:
            emit('rollback', status='FAILED', recovery='Use remote private transaction and restore command')
            raise RuntimeError('Deployment failed and rollback readiness is unconfirmed') from None
        emit('rollback', status='READY')
        raise


def deploy(args, branch, revision, folder):
    files, hashes, web_manifest, versions = artifacts()
    server = Server()
    run_id = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + revision[:12] + '-' + uuid.uuid4().hex[:6]
    release = REMOTE + '/releases/' + run_id
    private = REMOTE + '/private/update-' + run_id
    lock = REMOTE + '/.update-lock'
    locked = False
    candidate = 'mop-lan-update-candidate-' + run_id
    candidate_created = False
    try:
        server.run(['mkdir', lock]); locked = True
        server.write(lock + '/owner.json', json.dumps({'run_id': run_id, 'revision': revision}))
        all_before = server.containers(); items = choose(all_before)
        server.selected = [x['Config']['Labels']['com.docker.compose.service'] for x in items]
        names = [x['Name'].lstrip('/') for x in items]
        protected = metadata(all_before, names)
        require(all(x['running'] for n, x in protected.items() if n.startswith('mop-lan-')), 'Existing component unavailable')
        history = server.schema()
        require(all(x['success'] for x in history) and sorted(int(x['version']) for x in history) == versions,
                'Schema does not match packaged migration versions; use a separate reviewed migration procedure')
        server.ready()
        emit('inventory', applications=len(items), protected_containers=len(protected), schema_version=max(versions), ready=True)
        base = [REMOTE + '/compose.yaml']
        if 'export-preparation-worker' in server.selected: base += [REMOTE + '/export-queue.compose.yaml']
        # Respect the active recipe from previous runs, including its private environment snapshots.
        code, active = server.run(['cat', REMOTE + '/active-update.json'], check=False)
        if code == 0:
            prior = json.loads(active)
            prior_file = prior['compose_file']
            require(re.fullmatch(re.escape(REMOTE) + r'/private/update-[A-Za-z0-9-]+/(deploy|rollback)\.json', prior_file), 'Invalid active recipe')
            base += [prior_file]
        tracked_paths = [REMOTE + '/.env', *base]
        tracked_paths += [REMOTE + '/private/' + name + '.env' for name in ('api', 'photo', 'import', 'contact')]
        if 'export-preparation-worker' in server.selected: tracked_paths += [REMOTE + '/private/export-queue.env']
        if 'af-collector' in server.selected: tracked_paths += [REMOTE + '/private/af-collector.env']
        snapshots = {path: server.read(path) for path in tracked_paths}
        server.run(['mkdir', release]); server.run(['mkdir', '-m', '700', private])
        rollback = overlay(items)
        desired = overlay(items, release, revision)
        api_env = desired['services']['api']['environment']
        nginx_item = next(x for x in items if x['Name'] == '/mop-lan-web')
        nginx_mount = next(x for x in nginx_item['Mounts'] if x['Destination'] == '/etc/nginx/nginx.conf')
        nginx = server.read(nginx_mount['Source'])
        wecom = None
        if args.enable_wecom_manual:
            require(args.wecom_tenant_id and str(uuid.UUID(args.wecom_tenant_id)) == args.wecom_tenant_id, 'Explicit canonical WeCom tenant required')
            callback_parts(args.wecom_callback, args.allow_http_wecom_callback)
            wecom = json.loads((ROOT / 'deploy/private/wecom-app.json').read_text(encoding='utf-8-sig'))
            # Refuse to activate an existing send backlog. No deployment step enqueues or sends a message.
            require(pending_deliveries(server, args.wecom_tenant_id, wecom['corp_id'], wecom['agent_id']) == 0,
                    'Pending WeCom deliveries require operator review before enabling manual dispatch')
            require(api_env.get('MOP_WECOM_ENABLED', 'false') != 'true', 'WeCom is already enabled; preserve its existing configuration without this flag')
            nginx = domain_nginx(nginx, args.wecom_callback, args.allow_http_wecom_callback)
            secret_path = private + '/wecom.json'
            server.write(secret_path, json.dumps({k: wecom[k] for k in ('corp_id', 'agent_id', 'secret')}))
            server.run(['chown', '10001:10001', secret_path], True)
            server.run(['chmod', '400', secret_path], True)
            desired['services']['api']['volumes'].append({'type': 'bind', 'source': secret_path, 'target': '/run/secrets/mop-wecom.json', 'read_only': True})
            api_env.update({'MOP_WECOM_ENABLED': 'true', 'MOP_WECOM_LOGIN_ENABLED': 'true',
                            'MOP_WECOM_DISPATCH_ENABLED': 'true', 'MOP_WECOM_CONFIG_FILE': '/run/secrets/mop-wecom.json',
                            'MOP_WECOM_TENANT_ID': args.wecom_tenant_id, 'MOP_WECOM_TEST_USER_ID': wecom.get('user_id', ''),
                            'MOP_WECOM_CALLBACK_URL': args.wecom_callback, 'MOP_WECOM_ALLOW_HTTP_CALLBACK': str(args.allow_http_wecom_callback).lower(),
                            'MOP_WECOM_PROXY_URL': '', 'SERVER_FORWARD_HEADERS_STRATEGY': 'framework'})
        # Preserve current complete Nginx policy, with only explicit fixed domain routing added when requested.
        files['nginx.conf'] = str((folder / 'nginx.conf').relative_to(ROOT))
        (folder / 'nginx.conf').write_bytes(nginx.encode())
        hashes['nginx.conf'] = digest(nginx.encode())
        for mount in desired['services']['web']['volumes']:
            if mount['target'] == '/etc/nginx/nginx.conf': mount['source'] = release + '/nginx.conf'
        archive = folder / 'release.tar.gz'
        with tarfile.open(archive, 'w:gz', compresslevel=1) as tar:
            for name, path in files.items(): tar.add(ROOT / path, arcname=name, recursive=False)
        with server.client.open_sftp() as sftp: sftp.put(str(archive), release + '/release.tar.gz')
        server.run(['sha256sum', '--check', '--status'], data=digest(archive.read_bytes()) + '  ' + release + '/release.tar.gz\n')
        server.run(['tar', '-xzf', release + '/release.tar.gz', '-C', release])
        server.run(['mkdir', release + '/.local'])
        server.run(['sha256sum', '--check', '--status'], data=''.join(sha+'  '+release+'/'+name+'\n' for name, sha in hashes.items()))
        server.write(release + '/manifest.json', json.dumps({'source_commit': revision, 'files': hashes}), 0o644)
        server.write(private + '/rollback.json', compose_json(rollback))
        server.write(private + '/deploy.json', compose_json(desired))
        recovery = {'environment': ENVIRONMENT, 'source_commit': revision, 'run_id': run_id, 'services': server.selected,
                    'base_files': base, 'compose_file': private + '/deploy.json', 'rollback_file': private + '/rollback.json',
                    'previous_active': json.loads(active) if code == 0 else None, 'release': release, 'schema': history}
        server.write(private + '/transaction.json', json.dumps(recovery))
        emit('staged', source_commit=revision, files_verified=len(hashes), transaction=private+'/transaction.json')
        # Validate Nginx against the exact immutable candidate, using the already installed image.
        server.run(['docker', 'run', '--rm', '--network', 'none', '--read-only', '--user', '101:101', '--cap-drop', 'ALL',
                    '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,nosuid,size=128m',
                    '-v', release+'/nginx.conf:/etc/nginx/nginx.conf:ro', '-v', release+'/web:/usr/share/nginx/html:ro',
                    nginx_item['Image'], 'nginx', '-t'], True)
        require(not server.run(['ss', '-lntH', 'sport = :18039']).strip(), 'Candidate API loopback port is occupied')
        candidate_env = dict(api_env, SERVER_PORT='18039', SERVER_ADDRESS='127.0.0.1', MOP_WECOM_DISPATCH_ENABLED='false')
        require(all('\n' not in v and '\r' not in v for v in candidate_env.values()), 'Multiline application environment is unsupported')
        server.write(private + '/candidate.env', ''.join(k+'='+v+'\n' for k,v in candidate_env.items()))
        command = ['docker', 'create', '--name', candidate, '--network', 'host', '--read-only', '--user', '10001:10001',
                   '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges', '--memory', '1536m',
                   '--tmpfs', '/tmp:rw,nosuid,size=384m', '--label', 'mop.project='+PROJECT, '--label', 'mop.update.run='+run_id,
                   '--env-file', private+'/candidate.env']
        for mount in desired['services']['api']['volumes']:
            command += ['--mount', 'type=bind,source='+mount['source']+',target='+mount['target']+(',readonly' if mount['read_only'] else '')]
        command += [desired['services']['api']['image'], 'java', '-XX:MaxRAMPercentage=60.0', '-jar', '/app/api.jar']
        server.run(command, True); candidate_created = True
        server.run(['docker', 'start', candidate], True)
        server.ready(18039)
        emit('candidate', ready=True, migrations_executed=0)
        server.run(['docker', 'rm', '-f', candidate], True); candidate_created = False
        require(all(server.read(path) == text for path, text in snapshots.items()), 'Concurrent deployment configuration changed')
        require(server.schema() == history, 'Schema changed during candidate verification')
        new_files, old_files = base + [private+'/deploy.json'], base + [private+'/rollback.json']

        def verify():
            ready = server.ready()
            actual = server.containers()
            require(metadata(actual, names) == protected, 'Unrelated component lifecycle changed')
            for item in choose(actual):
                require(item['Config']['Labels'].get('mop.source.commit') == revision, 'Application revision label mismatch')
                target = '/usr/share/nginx/html' if item['Name'] == '/mop-lan-web' else '/app'
                expected = release + '/web' if target == '/usr/share/nginx/html' else release
                require(any(m['Destination'] == target and m['Source'] == expected and not m['RW'] for m in item['Mounts']),
                        'Application immutable release mount mismatch')
                require(item['RestartCount'] == 0, 'Updated application restarted unexpectedly')
                require(item['State'].get('Health', {}).get('Status', 'healthy') != 'unhealthy', 'Application health is unhealthy')
            served = json.loads(server.run(['curl', '--fail', '--silent', 'http://127.0.0.1:10010/release-manifest.json']))
            require(served['build_id'] == web_manifest['build_id'], 'Served Web build differs')
            require(digest(server.run(['curl', '--fail', '--silent', 'http://127.0.0.1:10010/index.html']).encode()) == hashes['web/index.html'], 'Served HTML differs')
            require(server.schema() == history, 'Application startup changed schema history')
            require(all(server.read(path) == text for path, text in snapshots.items()), 'Private/base configuration changed')
            if args.enable_wecom_manual:
                options = json.loads(server.run(['curl', '--fail', '--silent', 'http://127.0.0.1:10010/api/v1/auth/wecom/options']))['data']
                require(options['enabled'] is True, 'WeCom login is not enabled')
            server.atomic(REMOTE+'/active-update.json', json.dumps(recovery))
            return ready

        emit('switch', services=server.selected)
        status = guarded_switch(server, new_files, old_files, verify)
        report = {'verified_at': dt.datetime.now(dt.timezone.utc).isoformat(), 'status': 'PASSED',
                  'environment': ENVIRONMENT, 'branch': branch, 'source_commit': revision, 'run_id': run_id,
                  'release': release, 'transaction': private+'/transaction.json', 'services': server.selected,
                  'web_build_id': web_manifest['build_id'], 'files_verified': len(hashes), 'artifact_sha256': hashes,
                  'schema_version': max(versions), 'schema_history_unchanged': True, 'migrations_executed': 0,
                  'protected_containers': protected, 'protected_container_lifecycle_unchanged': True,
                  'private_base_configuration_unchanged': True, 'ready': status['ready'],
                  'production_ready': status.get('production_ready'), 'wecom_manual_enabled_this_run': args.enable_wecom_manual,
                  'automatic_business_notifications_enabled': False, 'deployment_test_messages_sent': 0,
                  'formal_contract_acceptance': 'NOT_COMPLETED', 'credentials_recorded': False}
        (folder / 'result.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
        emit('complete', source_commit=revision, ready=True, result=str(folder/'result.json'), transaction=report['transaction'])
        return report
    finally:
        if candidate_created:
            server.run(['docker', 'rm', '-f', candidate], True, check=False)
        if locked:
            server.run(['rm', '--', lock+'/owner.json'], check=False)
            server.run(['rmdir', '--', lock], check=False)
        server.client.close()


def restore(transaction):
    require(re.fullmatch(re.escape(REMOTE)+r'/private/update-[A-Za-z0-9-]+/transaction\.json', transaction), 'Invalid recovery transaction path')
    server = Server(); locked = False
    try:
        server.run(['mkdir', REMOTE+'/.update-lock']); locked = True
        info = json.loads(server.read(transaction))
        require(info['environment'] == ENVIRONMENT, 'Recovery environment mismatch')
        active = json.loads(server.read(REMOTE+'/active-update.json'))
        require(active['run_id'] == info['run_id'] and not active.get('rolled_back'), 'Only the currently active transaction can be rolled back once')
        server.selected = info['services']
        require(set(server.selected) <= set(SERVICES), 'Unapproved recovery service')
        before = metadata(server.containers(), ['mop-lan-'+n for n in server.selected])
        history = server.schema()
        files = info['base_files'] + [info['rollback_file']]
        server.compose(files, 'config', '--quiet')
        server.compose(files, 'up', '-d', '--no-deps', '--pull', 'never', *server.selected)
        server.ready()
        require(server.schema() == history and metadata(server.containers(), ['mop-lan-'+n for n in server.selected]) == before,
                'Recovery dependency/schema verification failed')
        # Keep the effective rollback recipe for a later update; do not pretend the failed/new revision is still active.
        server.atomic(REMOTE+'/active-update.json', json.dumps({**info, 'compose_file': info['rollback_file'], 'rolled_back': True}))
        emit('restore', ready=True, transaction=transaction, migrations_executed=0)
    finally:
        if locked: server.run(['rmdir', '--', REMOTE+'/.update-lock'], check=False)
        server.client.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['update', 'restore'])
    parser.add_argument('--branch', help='Must match current branch and origin upstream; default: current tracking branch')
    parser.add_argument('--transaction', help='Remote transaction.json for explicit application rollback')
    parser.add_argument('--enable-wecom-manual', action='store_true', help='Explicitly enable login and user-requested delivery; no automatic business enqueue')
    parser.add_argument('--wecom-tenant-id')
    parser.add_argument('--wecom-callback', default='')
    parser.add_argument('--allow-http-wecom-callback', action='store_true')
    args = parser.parse_args()
    try:
        if args.action == 'restore':
            require(args.transaction is not None, 'Restore requires a transaction path')
            restore(args.transaction)
        else:
            branch, revision = synchronize(args.branch)
            folder = ROOT / '.local/test-deployment' / (dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'-'+uuid.uuid4().hex[:6])
            folder.mkdir(parents=True)
            build(folder)
            require(git('rev-parse', 'HEAD') == revision and not git('status', '--porcelain'), 'Source changed during build')
            deploy(args, branch, revision, folder)
    except Exception as failure:
        # Whitelist our own diagnostic messages only; third-party exceptions can contain credentials.
        safe = str(failure) if type(failure) is RuntimeError else 'Details omitted; inspect credentials/network/toolchain privately'
        emit('failed', error_type=type(failure).__name__, reason=safe)
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()

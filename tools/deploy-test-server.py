"""Deploy only MOP application containers to the designated existing test server.

No component installation, shared-service lifecycle change, schema migration or
automatic credential replacement is performed. Secrets never enter public output.
"""
from __future__ import annotations
import argparse
import datetime
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import tarfile
import urllib.error
import urllib.parse
import urllib.request
import uuid
from inspect_test_server import ROOT, connect, connection_values

REMOTE = '/opt/mop-platform-test'
PRIVATE = ROOT / 'deploy/private/server-test-runtime.json'
STATE = ROOT / '.local/test-server-deployment.json'
REALM = 'mop-acceptance'
CLIENT = 'mop-web-acceptance'
PORT = 10010
API_PORT = 18010
KEYCLOAK_ADMIN_ORIGIN = 'http://127.0.0.1:18110'
PROJECT = 'mop-platform-test'
ROLES = ('padmin', 'msp', 'tadmin', 'viewerA', 'viewerB', 'reviewer')
DB_KEYS = ('SPRING_DATASOURCE_URL', 'SPRING_DATASOURCE_USERNAME', 'SPRING_DATASOURCE_PASSWORD')
PHOTO_KEYS = ('MOP_S3_ENDPOINT', 'MOP_S3_BUCKET', 'MOP_S3_ACCESS_KEY', 'MOP_S3_SECRET_KEY', 'MOP_PHOTO_ALLOWED_MEDIA_TYPES')
IMPORT_KEYS = ('MOP_IMPORT_S3_ENDPOINT', 'MOP_IMPORT_S3_BUCKET', 'MOP_IMPORT_S3_ACCESS_KEY', 'MOP_IMPORT_S3_SECRET_KEY', 'MOP_IMPORT_ALLOWED_FORMATS')

def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))

def save_private(data):
    PRIVATE.write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')

def command(ssh, args, *, input_bytes=None, timeout=120):
    value = args if isinstance(args, str) else shlex.join(str(x) for x in args)
    stdin, stdout, stderr = ssh.exec_command(value, timeout=timeout)
    if input_bytes is not None:
        stdin.write(input_bytes)
        stdin.channel.shutdown_write()
    output = stdout.read()
    error = stderr.read()
    code = stdout.channel.recv_exit_status()
    if code:
        # Do not return commands, stderr or environment content: they may contain secrets.
        raise RuntimeError('Remote project operation failed with exit ' + str(code))
    return output.decode('utf-8', 'replace')

def kc(method, path, payload=None, token=None, form=False, missing=False):
    headers = {'Content-Type': 'application/x-www-form-urlencoded' if form else 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    body = None if payload is None else (urllib.parse.urlencode(payload).encode() if form else json.dumps(payload).encode())
    request = urllib.request.Request(KEYCLOAK_ADMIN_ORIGIN + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as failure:
        if missing and failure.code == 404:
            return 404, None
        raise RuntimeError('Project identity request failed: HTTP ' + str(failure.code)) from None

def configuration():
    host, _, _ = connection_values()
    origin = f'http://{host}:{PORT}'
    if PRIVATE.exists():
        cfg = read_json(PRIVATE)
        if cfg.get('public_origin') != origin or cfg.get('project') != PROJECT:
            raise RuntimeError('Existing private acceptance configuration has a different owner or origin')
        return cfg
    cfg = {
        'project': PROJECT, 'ownership_id': str(uuid.uuid4()), 'public_origin': origin, 'issuer': origin + '/realms/' + REALM,
        'client_id': CLIENT, 'client_secret': secrets.token_urlsafe(40),
        'test_users': [{'role': role, 'username': 'mopacceptance-' + role.lower(), 'password': secrets.token_urlsafe(24)} for role in ROLES],
        'environment': {}, 'identity_provisioned': False,
    }
    save_private(cfg)
    return cfg

def provision():
    cfg = configuration()
    bootstrap = read_json(ROOT / 'deploy/private/existing-components.json')['hltk-keycloak']['environment']
    _, reply = kc('POST', '/realms/master/protocol/openid-connect/token', {
        'client_id': 'admin-cli', 'grant_type': 'password',
        'username': bootstrap['KC_BOOTSTRAP_ADMIN_USERNAME'], 'password': bootstrap['KC_BOOTSTRAP_ADMIN_PASSWORD'],
    }, form=True)
    token = reply['access_token']
    status, realm = kc('GET', '/admin/realms/' + REALM, token=token, missing=True)
    if status == 404:
        if cfg['identity_provisioned']:
            raise RuntimeError('Previously bound realm disappeared; refusing to recreate identities')
        kc('POST', '/admin/realms', {'realm': REALM, 'enabled': True, 'displayName': 'MOP 服务器验收测试',
            'registrationAllowed': False, 'resetPasswordAllowed': False, 'sslRequired': 'none',
            'attributes': {'mop_project': PROJECT, 'mop_acceptance_owner': cfg['ownership_id'], 'frontendUrl': cfg['public_origin']}}, token=token)
    elif realm.get('attributes', {}).get('mop_project') != PROJECT or realm.get('attributes', {}).get('mop_acceptance_owner') != cfg['ownership_id'] or realm.get('attributes', {}).get('frontendUrl') != cfg['public_origin']:
        raise RuntimeError('Existing realm ownership/origin mismatch; no overwrite')
    _, clients = kc('GET', '/admin/realms/' + REALM + '/clients?clientId=' + CLIENT, token=token)
    if not clients:
        if cfg.get('client_uuid'):
            raise RuntimeError('Previously bound client disappeared')
        kc('POST', '/admin/realms/' + REALM + '/clients', {
            'clientId': CLIENT, 'name': 'MOP server acceptance only', 'enabled': True, 'protocol': 'openid-connect',
            'publicClient': False, 'secret': cfg['client_secret'], 'standardFlowEnabled': True,
            'directAccessGrantsEnabled': False, 'serviceAccountsEnabled': False,
            'redirectUris': [cfg['public_origin'] + '/login/oauth2/code/mop'], 'webOrigins': [cfg['public_origin']],
            'attributes': {'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': cfg['public_origin'] + '/login'},
        }, token=token)
        _, clients = kc('GET', '/admin/realms/' + REALM + '/clients?clientId=' + CLIENT, token=token)
    if len(clients) != 1 or cfg.get('client_uuid', clients[0]['id']) != clients[0]['id']:
        raise RuntimeError('Acceptance client identity changed')
    current_client = clients[0]
    if (current_client.get('redirectUris') != [cfg['public_origin'] + '/login/oauth2/code/mop'] or
        current_client.get('webOrigins') != [cfg['public_origin']] or current_client.get('publicClient') or
        current_client.get('directAccessGrantsEnabled') or current_client.get('serviceAccountsEnabled') or
        current_client.get('attributes', {}).get('pkce.code.challenge.method') != 'S256'):
        raise RuntimeError('Existing acceptance client policy differs')
    _, actual_secret = kc('GET', '/admin/realms/' + REALM + '/clients/' + current_client['id'] + '/client-secret', token=token)
    if actual_secret.get('value') != cfg['client_secret']:
        raise RuntimeError('Existing acceptance client secret differs; no rotation')
    cfg['client_uuid'] = clients[0]['id']
    save_private(cfg)
    for user in cfg['test_users']:
        path = '/admin/realms/' + REALM + '/users?exact=true&username=' + urllib.parse.quote(user['username'])
        _, users = kc('GET', path, token=token)
        if not users:
            if user.get('id'):
                raise RuntimeError('Previously bound subject disappeared; same-name recreation is forbidden')
            kc('POST', '/admin/realms/' + REALM + '/users', {'username': user['username'], 'enabled': True,
                'emailVerified': True, 'firstName': '验收测试', 'lastName': user['role'],
                'email': user['username'] + '@mop-test.invalid',
                'credentials': [{'type': 'password', 'value': user['password'], 'temporary': False}]}, token=token)
            _, users = kc('GET', path, token=token)
        if len(users) != 1 or user.get('id', users[0]['id']) != users[0]['id']:
            raise RuntimeError('Acceptance user identity changed')
        user['id'] = users[0]['id']
        user['identity_subject'] = cfg['issuer'] + '|' + user['id']
        user['platform_user_id'] = str(uuid.uuid5(uuid.NAMESPACE_URL, user['identity_subject']))
        save_private(cfg)
    spec = importlib.util.spec_from_file_location('namespace', ROOT / 'tools/prepare-test-namespace.py')
    namespace = importlib.util.module_from_spec(spec); spec.loader.exec_module(namespace)
    statements = ['BEGIN;', 'SET ROLE mop_test_migrator;']
    for user in cfg['test_users']:
        role = 'padmin' if user['role'] == 'reviewer' else 'viewer' if user['role'].startswith('viewer') else user['role']
        uid, subject = namespace.literal(user['platform_user_id']), namespace.literal(user['identity_subject'])
        label = namespace.literal('服务器验收虚构身份 ' + user['role'])
        statements.append(f"INSERT INTO mop_user(id,identity_source,subject,display_name,role_code) VALUES({uid}::uuid,'test_oidc',{subject},{label},'{role}') ON CONFLICT DO NOTHING;")
        statements.append(f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM mop_user WHERE id={uid}::uuid AND identity_source='test_oidc' AND subject={subject} AND display_name={label} AND role_code='{role}' AND active) THEN RAISE EXCEPTION 'Acceptance identity differs'; END IF; END $$;")
        tenants = [2] if user['role'] == 'viewerB' else [1] if user['role'] in ('viewerA', 'tadmin') else [1, 2]
        for number in tenants:
            tenant = f'55000000-0000-4000-8000-00000000000{number}'
            statements.append(f"INSERT INTO mop_membership(tenant_id,user_id,role_code) VALUES('{tenant}',{uid}::uuid,'{role}') ON CONFLICT DO NOTHING;")
            statements.append(f"DO $$ BEGIN IF NOT EXISTS(SELECT 1 FROM mop_membership WHERE tenant_id='{tenant}' AND user_id={uid}::uuid AND role_code='{role}' AND active) THEN RAISE EXCEPTION 'Acceptance membership differs'; END IF; END $$;")
    statements.append('COMMIT;')
    ssh = connect()
    try:
        namespace.sql(ssh, '\n'.join(statements), 'mop_test')
    finally:
        ssh.close()
    cfg['identity_provisioned'] = True
    save_private(cfg)
    print(json.dumps({'stage': 'identity', 'realm': REALM, 'test_identities': len(cfg['test_users']), 'existing_realm_unchanged': True, 'credentials': 'private_file_only'}))

def nginx_config(cfg):
    authority = urllib.parse.urlsplit(cfg['public_origin']).netloc
    host = urllib.parse.urlsplit(cfg['public_origin']).hostname
    return f'''worker_processes 2;
pid /tmp/nginx.pid;
error_log /dev/stderr warn;
events {{ worker_connections 512; }}
http {{
  include /etc/nginx/mime.types;
  default_type application/octet-stream;
  access_log off;
  client_body_temp_path /tmp/client_body;
  proxy_temp_path /tmp/proxy;
  fastcgi_temp_path /tmp/fastcgi;
  uwsgi_temp_path /tmp/uwsgi;
  scgi_temp_path /tmp/scgi;
  server {{
    listen 127.0.0.1:19000;
    server_name localhost;
    client_max_body_size 22m;
    location / {{
      proxy_pass http://127.0.0.1:12080;
      proxy_set_header Host $http_host;
      proxy_request_buffering off;
      proxy_buffering off;
    }}
  }}
  server {{
    listen {PORT};
    server_name {host};
    if ($http_host != "{authority}") {{ return 400; }}
    root /usr/share/nginx/html;
    client_max_body_size 22m;
    add_header X-Content-Type-Options nosniff always;
    add_header Referrer-Policy no-referrer always;
    proxy_set_header Host "{authority}";
    proxy_set_header X-Forwarded-Host "{authority}";
    proxy_set_header X-Forwarded-Proto http;
    proxy_set_header X-Forwarded-Port {PORT};
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Forwarded "";
    proxy_read_timeout 60s;
    location /api/ {{ proxy_pass http://127.0.0.1:{API_PORT}; }}
    location /oauth2/ {{ proxy_pass http://127.0.0.1:{API_PORT}; }}
    location /login/oauth2/ {{ proxy_pass http://127.0.0.1:{API_PORT}; }}
    location /auth/ {{ proxy_pass http://127.0.0.1:{API_PORT}; }}
    location /realms/{REALM}/ {{ proxy_pass http://127.0.0.1:12110; }}
    location /resources/ {{ proxy_pass http://127.0.0.1:12110; }}
    location /realms/ {{ return 404; }}
    location /admin/ {{ return 404; }}
    location = /assets {{ try_files /index.html =404; }}
    location = /assets/ {{ try_files /index.html =404; }}
    location /assets/ {{ try_files $uri =404; expires 1h; }}
    location / {{ try_files $uri $uri/ /index.html; }}
  }}
}}
'''

def artifact(pattern, build_root=None):
    files = list((build_root or ROOT).glob(pattern))
    if len(files) != 1 or files[0].is_symlink():
        raise RuntimeError('Expected one regular built artifact for ' + pattern)
    return files[0]

def stage():
    cfg = configuration()
    if not cfg.get('local_identity_provisioned') or not re.fullmatch('[0-9a-f]{64}',cfg.get('auth_command_key','')):
        raise RuntimeError('Explicitly bootstrap project local accounts and private command key before staging')
    build = read_json(ROOT / '.local/server-build-context.json')
    build_revision = build['revision']
    if not re.fullmatch('[0-9a-f]{40}', build_revision): raise RuntimeError('Invalid built source revision')
    if subprocess.check_output(['git', 'rev-parse', build_revision], cwd=ROOT, text=True).strip() != build_revision: raise RuntimeError('Unknown built source revision')
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if subprocess.run(['git', 'diff', '--quiet', build_revision, revision, '--', 'pom.xml', 'server/*/pom.xml', 'server/*/src/main/**'], cwd=ROOT).returncode:
        raise RuntimeError('Backend production sources changed since the isolated build; rebuild before staging')
    build_root = Path(build['build_root']).resolve()
    if not build_root.is_relative_to(ROOT / '.local') or build_root.is_symlink(): raise RuntimeError('Build artifacts must be in the owned isolated build directory')
    release = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + revision[:12]
    root = REMOTE + '/releases/' + release
    files = {
        'api.jar': artifact('server/api/target/mop-api-*.jar', build_root),
        'photo-worker.jar': artifact('server/worker/target/mop-worker-0.1.0-SNAPSHOT.jar', build_root),
        'import-worker.jar': artifact('server/worker/target/*-SNAPSHOT-imports.jar', build_root),
        'contact-worker.jar': artifact('server/worker/target/*-contact-imports.jar', build_root),
        'server/import-parser/target/parser.jar': artifact('server/import-parser/target/mop-import-parser-0.1.0-SNAPSHOT.jar', build_root),
        'export-worker.jar': artifact('server/export-preparation-worker/target/mop-export-preparation-worker-0.1.0-SNAPSHOT.jar', build_root),
    }
    if not (ROOT / 'apps/web/dist/index.html').is_file():
        raise RuntimeError('Web build missing')
    source_env = read_json(ROOT / 'deploy/private/runtime.json')['environment']
    environment = {key: source_env[key] for key in DB_KEYS + PHOTO_KEYS + IMPORT_KEYS}
    environment['SPRING_DATASOURCE_URL'] = 'jdbc:postgresql://127.0.0.1:12060/mop_test'
    environment['MOP_S3_ENDPOINT'] = environment['MOP_IMPORT_S3_ENDPOINT'] = 'http://127.0.0.1:19000'
    environment.update({'SPRING_PROFILES_ACTIVE': 'remote_test', 'MOP_TEST_PUBLIC_ORIGIN': cfg['public_origin'],
        'MOP_AUTH_MODE': 'project_local', 'MOP_AUTH_COMMAND_KEY': cfg['auth_command_key'], 'MOP_OIDC_ENABLED': 'false',
        'SERVER_ADDRESS': '127.0.0.1', 'SERVER_PORT': str(API_PORT), 'SERVER_FORWARD_HEADERS_STRATEGY': 'framework',
        'MOP_IMPORT_PARSER_JAR': '/app/server/import-parser/target/parser.jar', 'MOP_CONTACT_IMPORT_LEASE_MILLIS': '300000',
        'MOP_PHOTO_WORKER_ENABLED': 'false', 'MOP_IMPORT_WORKER_ENABLED': 'false', 'MOP_CONTACT_IMPORT_WORKER_ENABLED': 'false'})
    cfg['environment'] = environment
    save_private(cfg)
    all_files = {name: path.read_bytes() for name, path in files.items()}
    for path in (ROOT / 'apps/web/dist').rglob('*'):
        if path.is_symlink(): raise RuntimeError('Symlink in web build')
        if path.is_file(): all_files['web/' + path.relative_to(ROOT / 'apps/web/dist').as_posix()] = path.read_bytes()
    all_files['nginx.conf'] = nginx_config(cfg).encode()
    all_files['.local/.keep'] = b''
    manifest = {name: hashlib.sha256(data).hexdigest() for name, data in all_files.items()}
    all_files['manifest.json'] = (json.dumps({'revision': revision, 'backend_build_revision': build_revision, 'files': manifest}, indent=2) + '\n').encode()
    previous = read_json(STATE) if STATE.exists() else None
    reusable = bool(previous and previous.get('project') == PROJECT and previous.get('stage_verified') and
        re.fullmatch(re.escape(REMOTE) + r'/releases/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}', previous.get('release', '')) and
        previous.get('artifact_sha256') == {name: manifest[name] for name in files})
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode='w:gz') as archive:
        for name, data in all_files.items():
            if reusable and name in files: continue
            info = tarfile.TarInfo(name); info.size = len(data); info.mode = 0o644
            archive.addfile(info, io.BytesIO(data))
    roles = {
        'api': environment,
        'photo': {**{key: environment[key] for key in DB_KEYS + PHOTO_KEYS}, 'SPRING_PROFILES_ACTIVE': 'remote_test', 'MOP_PHOTO_WORKER_ENABLED': 'true'},
        'import': {**{key: environment[key] for key in DB_KEYS + IMPORT_KEYS}, 'SPRING_PROFILES_ACTIVE': 'remote_test', 'MOP_IMPORT_WORKER_ENABLED': 'true', 'MOP_IMPORT_PARSER_JAR': '/app/server/import-parser/target/parser.jar'},
        'contact': {**{key: environment[key] for key in DB_KEYS}, 'SPRING_PROFILES_ACTIVE': 'remote_test', 'MOP_CONTACT_IMPORT_WORKER_ENABLED': 'true', 'MOP_CONTACT_IMPORT_LEASE_MILLIS': '300000'},
        'export': {**{key: environment[key] for key in DB_KEYS}, 'SPRING_PROFILES_ACTIVE': 'remote_test'},
    }
    ssh = connect()
    try:
        marker = command(ssh, f'if test -e {REMOTE}; then cat {REMOTE}/owner.json; else printf ABSENT; fi')
        if marker != 'ABSENT' and json.loads(marker).get('project') != PROJECT:
            raise RuntimeError('Remote project directory ownership mismatch')
        command(ssh, ['mkdir', '-p', REMOTE + '/releases', REMOTE + '/private'])
        command(ssh, ['chmod', '700', REMOTE + '/private'])
        command(ssh, ['mkdir', root])
        if reusable:
            checks = '\n'.join(manifest[name] + '  ' + previous['release'] + '/' + name for name in files) + '\n'
            command(ssh, ['sha256sum', '--check', '--status'], input_bytes=checks.encode())
            for name in files:
                command(ssh, ['mkdir', '-p', root + '/' + str(Path(name).parent).replace('\\', '/')])
                command(ssh, ['cp', '--', previous['release'] + '/' + name, root + '/' + name])
        sftp = ssh.open_sftp()
        try:
            for name, data, mode in [('owner.json', (json.dumps({'project': PROJECT})+'\n').encode(), 0o644), ('release.tar.gz', stream.getvalue(), 0o600)]:
                path = REMOTE + '/' + name
                with sftp.file(path, 'wb') as target:
                    target.set_pipelined(True)
                    target.write(data)
                sftp.chmod(path, mode)
            for role, values in roles.items():
                if any('\n' in str(v) or '\r' in str(v) for v in values.values()): raise RuntimeError('Invalid multiline environment value')
                path = REMOTE + '/private/' + role + '.env'
                with sftp.file(path, 'wb') as target: target.write(('\n'.join(k+'='+str(v) for k,v in values.items())+'\n').encode())
                sftp.chmod(path, 0o600)
        finally:
            sftp.close()
        command(ssh, ['tar', '-xzf', REMOTE + '/release.tar.gz', '-C', root])
        checks = '\n'.join(hash_value + '  ' + root + '/' + name for name, hash_value in manifest.items()) + '\n'
        command(ssh, ['sha256sum', '--check', '--status'], input_bytes=checks.encode())
        images = {}
        for name in ('eclipse-temurin:17-jre', 'nginx:alpine'):
            images[name] = command(ssh, ['docker', 'image', 'inspect', name, '--format', '{{.Id}}']).strip()
        state = {'project': PROJECT, 'revision': revision, 'backend_build_revision': build_revision, 'reused_verified_backend_artifacts': reusable, 'release': root, 'public_origin': cfg['public_origin'], 'images': images,
            'artifact_sha256': {name: manifest[name] for name in files}, 'stage_verified': True, 'started': False}
        STATE.write_text(json.dumps(state, indent=2)+'\n', encoding='utf8')
    finally:
        ssh.close()
    print(json.dumps({'stage': 'artifacts', 'revision': revision, 'files_verified': len(manifest), 'public_port': PORT, 'migrations_executed': 0, 'new_component_images_pulled': 0}))

def start():
    state = read_json(STATE)
    if state['project'] != PROJECT or not state['stage_verified'] or not state['release'].startswith(REMOTE + '/releases/'):
        raise RuntimeError('Invalid verified deployment state')
    ssh = connect()
    try:
        # Starting an existing name is refused. Replacement/rollback is a separate reviewed operation.
        names = command(ssh, ['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
        wanted = ['mop-test-web', 'mop-test-api', 'mop-test-photo-worker', 'mop-test-import-worker', 'mop-test-contact-worker']
        if any(name in names for name in wanted): raise RuntimeError('MOP deployment containers already exist; inspect before replacement')
        ports = command(ssh, f"ss -lntH '( sport = :{PORT} or sport = :{API_PORT} or sport = :19000 )'").strip()
        if ports: raise RuntimeError('Deployment port already occupied')
        components = ('shared-postgres', 'hltk-keycloak', 'shared-minio')
        shared_before = {name: json.loads(command(ssh, ['docker', 'inspect', '--format', '{{json .State}}', name]))['StartedAt'] for name in components}
        root = state['release']
        command(ssh, ['docker', 'run', '--rm', '--network', 'none', '--read-only', '--user', '101:101', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--tmpfs', '/tmp:rw,nosuid,size=128m',
            '-v', root+'/nginx.conf:/etc/nginx/nginx.conf:ro', '-v', root+'/web:/usr/share/nginx/html:ro',
            state['images']['nginx:alpine'], 'nginx', '-t'])
        common = ['docker', 'run', '-d', '--network', 'host', '--restart', 'unless-stopped', '--read-only', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--label', 'mop.project='+PROJECT, '--label', 'mop.release='+state['revision'],
            '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3']
        command(ssh, common + ['--name', 'mop-test-web', '--user', '101:101', '--memory', '128m', '--cpus', '1',
            '--tmpfs', '/tmp:rw,nosuid,size=128m', '-v', root+'/nginx.conf:/etc/nginx/nginx.conf:ro', '-v', root+'/web:/usr/share/nginx/html:ro',
            state['images']['nginx:alpine'], 'nginx', '-g', 'daemon off;'])
        containers = [('api', 'api.jar', 'api', '1536m'), ('photo-worker', 'photo-worker.jar', 'photo', '512m'),
                      ('import-worker', 'import-worker.jar', 'import', '768m'), ('contact-worker', 'contact-worker.jar', 'contact', '512m')]
        for name, jar, env, memory in containers:
            parser_scratch = ['--tmpfs', '/app/.local:rw,nosuid,uid=10001,gid=10001,mode=0700,size=256m'] if env == 'import' else []
            command(ssh, common + ['--name', 'mop-test-'+name, '--user', '10001:10001', '--memory', memory, '--cpus', '2',
                '--tmpfs', '/tmp:rw,nosuid,size=384m', '--workdir', '/app' if env == 'import' else '/tmp', '-v', root+':/app:ro', '--env-file', REMOTE+'/private/'+env+'.env',
                ] + parser_scratch + [state['images']['eclipse-temurin:17-jre'], 'java', '-XX:MaxRAMPercentage=60.0', '-jar', '/app/'+jar])
        shared_after = {name: json.loads(command(ssh, ['docker', 'inspect', '--format', '{{json .State}}', name]))['StartedAt'] for name in components}
        if shared_before != shared_after: raise RuntimeError('Shared component lifecycle changed during deployment')
        state['shared_components_started_at'] = shared_before
        state['started'] = True
        STATE.write_text(json.dumps(state, indent=2)+'\n', encoding='utf8')
    finally:
        ssh.close()
    print(json.dumps({'stage': 'started', 'project_containers': 5, 'database_migrations': 0, 'shared_postgresql_restart': False, 'public_origin': state['public_origin']}))

def repair_import():
    state = read_json(STATE)
    ssh = connect()
    try:
        labels = json.loads(command(ssh, ['docker', 'inspect', '--format', '{{json .Config.Labels}}', 'mop-test-import-worker']))
        if labels.get('mop.project') != PROJECT or labels.get('mop.release') != state['revision']:
            raise RuntimeError('Import worker ownership mismatch')
        root = state['release']
        if not re.fullmatch(re.escape(REMOTE) + r'/releases/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{12}', root): raise RuntimeError('Invalid release root')
        command(ssh, ['sha256sum', '--check', '--status'], input_bytes=(state['artifact_sha256']['import-worker.jar']+'  '+root+'/import-worker.jar\n').encode())
        archived = 'mop-test-import-worker-failed-20261005'
        names = command(ssh, ['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines()
        if archived in names: raise RuntimeError('Preserve previous failed attempt; do not repeat repair blindly')
        command(ssh, ['docker', 'update', '--restart=no', 'mop-test-import-worker'])
        command(ssh, ['docker', 'stop', 'mop-test-import-worker'])
        command(ssh, ['docker', 'rename', 'mop-test-import-worker', archived])
        command(ssh, ['docker', 'run', '-d', '--name', 'mop-test-import-worker', '--network', 'host', '--restart', 'unless-stopped',
            '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
            '--label', 'mop.project='+PROJECT, '--label', 'mop.release='+state['revision'], '--memory', '768m', '--cpus', '2',
            '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3', '--tmpfs', '/tmp:rw,nosuid,size=384m',
            '--tmpfs', '/app/.local:rw,nosuid,uid=10001,gid=10001,mode=0700,size=256m', '--workdir', '/app',
            '-v', root+':/app:ro', '--env-file', REMOTE+'/private/import.env', state['images']['eclipse-temurin:17-jre'],
            'java', '-XX:MaxRAMPercentage=60.0', '-jar', '/app/import-worker.jar'])
    finally:
        ssh.close()
    print(json.dumps({'stage': 'repair_import', 'working_directory': '/app', 'old_failed_container_retained': True, 'guard_relaxed': False}))

def repair_web_route():
    state, cfg = read_json(STATE), configuration()
    ssh = connect()
    try:
        labels = json.loads(command(ssh, ['docker', 'inspect', '--format', '{{json .Config.Labels}}', 'mop-test-web']))
        if labels.get('mop.project') != PROJECT or labels.get('mop.release') != state['revision']:
            raise RuntimeError('Web container ownership mismatch')
        archived = 'mop-test-web-failed-route-20261005'
        if archived in command(ssh, ['docker', 'ps', '-a', '--format', '{{.Names}}']).splitlines():
            raise RuntimeError('Preserve prior route repair; inspect before repeating')
        command(ssh, ['mkdir', '-p', REMOTE + '/configs'])
        path = REMOTE + '/configs/nginx-assets-route-20261005.conf'
        data = nginx_config(cfg).encode()
        sftp = ssh.open_sftp()
        try:
            with sftp.file(path, 'wxb') as target: target.write(data)
            sftp.chmod(path, 0o644)
        finally: sftp.close()
        command(ssh, ['docker', 'run', '--rm', '--network', 'none', '--read-only', '--user', '101:101', '--tmpfs', '/tmp:rw,nosuid,size=128m',
            '-v', path+':/etc/nginx/nginx.conf:ro', '-v', state['release']+'/web:/usr/share/nginx/html:ro', state['images']['nginx:alpine'], 'nginx', '-t'])
        command(ssh, ['docker', 'update', '--restart=no', 'mop-test-web'])
        command(ssh, ['docker', 'stop', 'mop-test-web'])
        command(ssh, ['docker', 'rename', 'mop-test-web', archived])
        command(ssh, ['docker', 'run', '-d', '--name', 'mop-test-web', '--network', 'host', '--restart', 'unless-stopped', '--read-only',
            '--user', '101:101', '--memory', '128m', '--cpus', '1', '--tmpfs', '/tmp:rw,nosuid,size=128m', '--cap-drop', 'ALL',
            '--security-opt', 'no-new-privileges', '--label', 'mop.project='+PROJECT, '--label', 'mop.release='+state['revision'],
            '--log-opt', 'max-size=10m', '--log-opt', 'max-file=3', '-v', path+':/etc/nginx/nginx.conf:ro',
            '-v', state['release']+'/web:/usr/share/nginx/html:ro', state['images']['nginx:alpine'], 'nginx', '-g', 'daemon off;'])
        state['active_nginx_config'] = path
        state['active_nginx_sha256'] = hashlib.sha256(data).hexdigest()
        STATE.write_text(json.dumps(state, indent=2)+'\n', encoding='utf8')
    finally: ssh.close()
    print(json.dumps({'stage': 'repair_web_route', 'spa_asset_route': 'exact_match', 'missing_static_asset_still_404': True, 'old_container_retained': True}))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['provision', 'stage', 'start', 'repair-import', 'repair-web-route'])
    args = parser.parse_args()
    try:
        {'provision': provision, 'stage': stage, 'start': start, 'repair-import': repair_import, 'repair-web-route': repair_web_route}[args.action]()
    except Exception as failure:
        import traceback
        (ROOT / '.local' / ('test-server-' + args.action + '-failure.log')).write_text(traceback.format_exc(), encoding='utf8')
        print(json.dumps({'stage': args.action, 'status': 'FAILED', 'error_type': type(failure).__name__, 'detail': 'redacted; preserve existing project state and inspect privately'}))
        raise SystemExit(1)

if __name__ == '__main__':
    main()

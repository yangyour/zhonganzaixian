"""Create only MOP's database, roles and OIDC realm on existing services."""
import json
import secrets
import urllib.error
import urllib.parse
import urllib.request
from inspect_test_server import connect, ROOT

RUNTIME = ROOT / 'deploy/private/runtime.json'
EXISTING = ROOT / 'deploy/private/existing-components.json'
KEYCLOAK = 'http://127.0.0.1:18110'
REALM = 'mop-test'

def http(method, path, payload=None, token=None, form=False, allow_missing=False):
    headers = {'Content-Type': 'application/x-www-form-urlencoded' if form else 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if payload is None else (urllib.parse.urlencode(payload).encode() if form else json.dumps(payload).encode())
    request = urllib.request.Request(KEYCLOAK + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        if allow_missing and error.code == 404:
            return 404, None
        raise RuntimeError(f'Keycloak namespace request failed with HTTP {error.code}; credentials omitted') from None

def literal(value):
    return "'" + value.replace("'", "''") + "'"

def sql(client, text, database='postgres'):
    command = 'docker exec -i shared-postgres psql -U postgres -d ' + database + ' -v ON_ERROR_STOP=1 -At'
    stdin, stdout, stderr = client.exec_command(command, timeout=30)
    stdin.write(text)
    stdin.channel.shutdown_write()
    out = stdout.read().decode()
    code = stdout.channel.recv_exit_status()
    if code:
        # PostgreSQL errors can echo SQL/passwords, so never return their text.
        stderr.read()
        raise RuntimeError('Project namespace SQL failed; sensitive SQL output suppressed')
    return out

def main():
    runtime = json.loads(RUNTIME.read_text()) if RUNTIME.exists() else {
        'environment': {
            'SPRING_DATASOURCE_URL': 'jdbc:postgresql://127.0.0.1:15432/mop_test',
            'SPRING_DATASOURCE_USERNAME': 'mop_test_runtime',
            'SPRING_DATASOURCE_PASSWORD': secrets.token_urlsafe(32),
            'MOP_MIGRATION_USERNAME': 'mop_test_migrator',
            'MOP_MIGRATION_PASSWORD': secrets.token_urlsafe(32),
            'MOP_OIDC_ISSUER': KEYCLOAK + '/realms/' + REALM,
            'MOP_OIDC_CLIENT_ID': 'mop-web',
            'MOP_OIDC_CLIENT_SECRET': secrets.token_urlsafe(40),
        },
        'test_users': [{'username': 'mopfixture-' + role, 'password': secrets.token_urlsafe(24), 'role': role} for role in ['padmin', 'msp', 'tadmin', 'viewerA', 'viewerB']],
        'status': 'shared_remote_test_only_not_production',
    }
    env = runtime['environment']
    client = connect()
    try:
        exists = sql(client, "select count(*) from pg_database where datname='mop_test';").strip() == '1'
        if exists and not RUNTIME.exists():
            raise RuntimeError('mop_test already exists but local project credentials are absent; refusing to modify existing namespace')
        if not exists:
            sql(client, 'CREATE ROLE mop_test_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD ' + literal(env['MOP_MIGRATION_PASSWORD']) + ';\nCREATE ROLE mop_test_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD ' + literal(env['SPRING_DATASOURCE_PASSWORD']) + ';\nCREATE DATABASE mop_test OWNER mop_test_migrator;\n')
            sql(client, 'REVOKE ALL ON DATABASE mop_test FROM PUBLIC; GRANT CONNECT ON DATABASE mop_test TO mop_test_runtime;', 'mop_test')
            sql(client, 'REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO mop_test_runtime;', 'mop_test')
            # Persist before the OIDC step so a later retry does not invent database credentials.
            RUNTIME.write_text(json.dumps(runtime, indent=2) + '\n', encoding='utf-8')
        # Only migrations grant explicit runtime capabilities; new tables are never blanket writable.
        sql(client, 'SET ROLE mop_test_migrator; ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM mop_test_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM mop_test_runtime;', 'mop_test')
    finally:
        client.close()
    existing = json.loads(EXISTING.read_text())['hltk-keycloak']['environment']
    _, result = http('POST', '/realms/master/protocol/openid-connect/token', {
        'client_id': 'admin-cli', 'grant_type': 'password',
        'username': existing['KC_BOOTSTRAP_ADMIN_USERNAME'],
        'password': existing['KC_BOOTSTRAP_ADMIN_PASSWORD'],
    }, form=True)
    token = result['access_token']
    status, realm = http('GET', '/admin/realms/' + REALM, token=token, allow_missing=True)
    if status == 404:
        http('POST', '/admin/realms', {'realm': REALM, 'enabled': True, 'displayName': 'MOP 共享测试环境', 'registrationAllowed': False, 'resetPasswordAllowed': False, 'sslRequired': 'none', 'attributes': {'mop_project': 'mop-platform', 'frontendUrl': KEYCLOAK}}, token=token)
    elif realm.get('attributes', {}).get('mop_project') != 'mop-platform':
        raise RuntimeError('Existing realm is not marked as this project; refusing to alter it')
    elif realm.get('attributes', {}).get('frontendUrl') != KEYCLOAK:
        realm['attributes']['frontendUrl'] = KEYCLOAK
        http('PUT', '/admin/realms/' + REALM, realm, token=token)
    _, clients = http('GET', '/admin/realms/' + REALM + '/clients?clientId=mop-web', token=token)
    if not clients:
        http('POST', '/admin/realms/' + REALM + '/clients', {
            'clientId': 'mop-web', 'name': 'MOP remote test web', 'enabled': True,
            'publicClient': False, 'secret': env['MOP_OIDC_CLIENT_SECRET'],
            'standardFlowEnabled': True, 'directAccessGrantsEnabled': False,
            'serviceAccountsEnabled': False, 'redirectUris': ['http://127.0.0.1:5173/login/oauth2/code/mop', 'http://127.0.0.1:8080/login/oauth2/code/mop'],
            'webOrigins': ['http://127.0.0.1:5173'],
            'attributes': {'pkce.code.challenge.method': 'S256', 'post.logout.redirect.uris': 'http://127.0.0.1:5173/login##http://127.0.0.1:8080/login'},
        }, token=token)
    for user in runtime['test_users']:
        _, users = http('GET', '/admin/realms/' + REALM + '/users?exact=true&username=' + urllib.parse.quote(user['username']), token=token)
        if not users:
            if user.get('id'):
                raise RuntimeError('A previously bound test identity disappeared; refusing to recreate a same-name account with inherited access')
            http('POST', '/admin/realms/' + REALM + '/users', {'username': user['username'], 'enabled': True, 'emailVerified': True, 'firstName': 'MOP', 'lastName': user['role'], 'email': user['username'] + '@mop-test.invalid', 'credentials': [{'type': 'password', 'value': user['password'], 'temporary': False}]}, token=token)
            _, users = http('GET', '/admin/realms/' + REALM + '/users?exact=true&username=' + urllib.parse.quote(user['username']), token=token)
        if len(users) != 1 or user.get('id') and user['id'] != users[0]['id']:
            raise RuntimeError('Test identity ID changed or is ambiguous; a username cannot replace an established identity')
        user['id'] = users[0]['id']
        user['identity_subject'] = env['MOP_OIDC_ISSUER'] + '|' + user['id']
    RUNTIME.write_text(json.dumps(runtime, indent=2) + '\n', encoding='utf-8')
    _, discovery = http('GET', '/realms/' + REALM + '/.well-known/openid-configuration')
    print(json.dumps({'database': 'mop_test', 'roles': ['mop_test_migrator', 'mop_test_runtime'], 'realm': REALM, 'client': 'mop-web', 'issuer': discovery['issuer'], 'test_user_count': len(runtime['test_users']), 'credentials': 'stored_only_in_ignored_deploy_private_runtime_json', 'components_installed': 0, 'existing_services_reconfigured': 0}, indent=2))

if __name__ == '__main__':
    main()

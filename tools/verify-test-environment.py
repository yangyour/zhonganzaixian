"""Prove live permissions and revocation in MOP's own remote namespace only."""
import json
import urllib.error
import urllib.request
from inspect_test_server import ROOT, connect
from importlib.util import spec_from_file_location, module_from_spec

def load_namespace_helpers():
    spec = spec_from_file_location('mop_namespace_helpers', ROOT / 'tools/prepare-test-namespace.py')
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def main():
    namespace = load_namespace_helpers()
    session = json.loads((ROOT / 'deploy/private/qa-session.json').read_text(encoding='utf-8-sig'))
    previous = json.loads((ROOT / '.local/persistence-smoke.json').read_text(encoding='utf-8-sig'))
    actor = session['user_id']
    tenant = previous['tenant_id']
    # All values are trusted local server results, still validate identifiers before SQL interpolation.
    import uuid
    uuid.UUID(actor)
    uuid.UUID(tenant)
    base = 'http://127.0.0.1:8080/api/v1'

    def api(method, path, body=None, idem='remote-revocation-proof'):
        headers = {'Cookie': session['cookie'], 'X-CSRF-Token': session['csrf_token'], 'Idempotency-Key': idem, 'Content-Type': 'application/json'}
        request = urllib.request.Request(base + path, headers=headers, method=method, data=None if body is None else json.dumps(body).encode())
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    client = connect()
    result = {}
    initial = None
    where = "user_id=" + namespace.literal(actor) + "::uuid AND tenant_id=" + namespace.literal(tenant) + '::uuid'
    try:
        if namespace.sql(client, 'SELECT current_database();', 'mop_test').strip() != 'mop_test':
            raise RuntimeError('Unexpected database; no test mutation allowed')
        privileges = namespace.sql(client, "SELECT json_build_object('audit_insert',has_table_privilege('mop_test_runtime','public.mop_audit','INSERT'),'audit_update',has_table_privilege('mop_test_runtime','public.mop_audit','UPDATE'),'audit_delete',has_table_privilege('mop_test_runtime','public.mop_audit','DELETE'),'registry_update',has_column_privilege('mop_test_runtime','public.mop_configuration','registry_json','UPDATE'),'flyway_select',has_table_privilege('mop_test_runtime','public.flyway_schema_history','SELECT'));", 'mop_test')
        result['runtime_permissions'] = json.loads(privileges.strip())
        assert result['runtime_permissions'] == {'audit_insert': True, 'audit_update': False, 'audit_delete': False, 'registry_update': False, 'flyway_select': False}
        initial = json.loads(namespace.sql(client, 'SELECT json_build_object(\'active\',active,\'role\',role_code) FROM mop_membership WHERE ' + where + ';', 'mop_test').strip())
        assert initial['active'] and initial['role'] == 'padmin'
        namespace.sql(client, 'SET ROLE mop_test_migrator; UPDATE mop_membership SET active=false,permission_version=permission_version+1 WHERE ' + where + ';', 'mop_test')
        assert api('GET', '/session')[0] == 401
        replay_status, replay = api('POST', '/assets', previous['command'], previous['idempotency_key'])
        assert replay_status == 401 and replay['error']['code'] == 'SESSION_EXPIRED'
        result['revoked_old_cookie_and_committed_replay_rejected'] = True
        namespace.sql(client, 'SET ROLE mop_test_migrator; UPDATE mop_membership SET active=true,role_code=\'viewer\',permission_version=permission_version+1 WHERE ' + where + ';', 'mop_test')
        assert api('POST', '/assets', previous['command'], previous['idempotency_key'])[0] == 403
        assert api('GET', '/configurations')[0] == 403
        result['downgraded_role_cannot_replay_write_or_read_admin_config'] = True
    finally:
        if initial is not None:
            active = 'true' if initial['active'] else 'false'
            namespace.sql(client, 'SET ROLE mop_test_migrator; UPDATE mop_membership SET active=' + active + ',role_code=' + namespace.literal(initial['role']) + ',permission_version=permission_version+1 WHERE ' + where + ';', 'mop_test')
        client.close()
    assert api('GET', '/session')[0] == 200
    result['membership_restored_permission_version_monotonically_increased'] = True
    (ROOT / 'docs/evidence/remote-security-verification.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))

if __name__ == '__main__':
    main()

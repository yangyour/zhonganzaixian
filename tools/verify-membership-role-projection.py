"""Verify current-tenant role projection with an existing private OIDC session.

Only the current test actor's fixture-A membership is temporarily changed. No
global user role, migration, component configuration or other tenant is changed.
The public evidence contains booleans, permission versions and HTTP statuses.
"""
import json
import logging
import re
import sys
import urllib.error
import urllib.request
import uuid
from importlib.util import module_from_spec, spec_from_file_location
from inspect_test_server import ROOT, connect

TENANT_A = '55000000-0000-4000-8000-000000000001'
BASE = 'http://127.0.0.1:8080/api/v1'
PRIVATE_SESSION = ROOT / 'deploy/private/qa-ticket-reviewer.json'
EVIDENCE = ROOT / 'docs/evidence/membership-role-http-verification.json'


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Never forward the private Cookie/CSRF headers to another request."""
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def verify_redirect_policy_without_network():
    """Pure handler check: no opener.open, SSH, private-file read or evidence write."""
    handler = NoRedirectHandler()
    request = urllib.request.Request(BASE + '/session', headers={'Cookie': 'unit-only', 'X-CSRF-Token': 'unit-only'})
    original_headers = dict(request.header_items())
    for status in (301, 302, 303, 307, 308):
        assert handler.redirect_request(request, None, status, 'unit-only', {}, 'https://redirect-test.invalid/') is None
        assert dict(request.header_items()) == original_headers
    return True


def load_namespace_helpers():
    spec = spec_from_file_location('mop_membership_namespace_helpers', ROOT / 'tools/prepare-test-namespace.py')
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sql_json(namespace, client, text):
    # psql may include SET/UPDATE command tags; accept exactly one JSON row.
    rows = [line for line in namespace.sql(client, text, 'mop_test').splitlines() if line.startswith('{')]
    if len(rows) != 1:
        raise RuntimeError('Scoped project SQL did not return one row')
    return json.loads(rows[0])


def main():
    logging.getLogger('paramiko').setLevel(logging.CRITICAL)
    stage = 'load_private_session'
    failed_stage = None
    client = None
    initial = None
    mutation_attempted = False
    result = {'passed': False, 'restore_attempted': False, 'restore_failed': False}
    namespace = None
    where = None
    actor = None
    session = None
    original_cookie = None
    initial_data = None
    restored_version = None

    try:
        namespace = load_namespace_helpers()
        session = json.loads(PRIVATE_SESSION.read_text(encoding='utf-8-sig'))
        original_cookie = session['cookie']
        csrf = session['csrf_token']
        if not isinstance(original_cookie, str) or not original_cookie.startswith('MOP_SESSION=') or len(original_cookie) > 4096 or re.search(r'[\r\n\x00]', original_cookie):
            raise RuntimeError('Invalid private session cookie')
        if not isinstance(csrf, str) or not csrf or len(csrf) > 1024 or re.search(r'[\r\n\x00]', csrf):
            raise RuntimeError('Invalid private CSRF value')
        opener = urllib.request.build_opener(NoRedirectHandler())

        def api(method, path, body=None, idem=None):
            if not path.startswith('/') or path.startswith('//'):
                raise RuntimeError('Unexpected local API path')
            headers = {'Cookie': original_cookie, 'X-CSRF-Token': csrf, 'Content-Type': 'application/json'}
            if idem is not None:
                headers['Idempotency-Key'] = idem
            request = urllib.request.Request(BASE + path, headers=headers, method=method, data=None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'))
            try:
                with opener.open(request, timeout=15) as response:
                    if response.status not in (200, 202):
                        raise RuntimeError('Unexpected local API status')
                    return response.status, json.loads(response.read())
            except urllib.error.HTTPError as error:
                if error.code not in (400, 401, 403, 404, 409, 422, 503):
                    # Includes every 3xx. Never read or emit a redirect URL/body.
                    raise RuntimeError('Unexpected local API status') from None
                return error.code, json.loads(error.read())

        stage = 'initial_session'
        status, response = api('GET', '/session')
        result['initial_session_http_status'] = status
        assert status == 200
        initial_data = response['data']
        assert initial_data['mode'] == 'remote_test' and initial_data['identity_source'] == 'test_oidc'
        assert initial_data['tenant']['id'] == TENANT_A
        assert initial_data['user']['role'] == 'padmin'
        actor = str(uuid.UUID(initial_data['user']['id']))
        if session.get('user_id') is not None:
            assert str(uuid.UUID(session['user_id'])) == actor
        # Obtain a current CSRF value while preserving the exact original cookie.
        csrf = initial_data['csrf_token']
        assert isinstance(csrf, str) and csrf and len(csrf) <= 1024 and not re.search(r'[\r\n\x00]', csrf)
        result['initial_oidc_fixture_a_padmin'] = True

        stage = 'connect_project_database'
        client = connect()
        assert namespace.sql(client, 'SELECT current_database();', 'mop_test').strip() == 'mop_test'
        result['database_is_designated_mop_test'] = True
        where = 'm.user_id=' + namespace.literal(actor) + '::uuid AND m.tenant_id=' + namespace.literal(TENANT_A) + '::uuid'
        stage = 'initial_membership'
        initial = sql_json(namespace, client, "SELECT json_build_object('active',m.active,'role',m.role_code,'permission_version',m.permission_version,'global_padmin',u.role_code='padmin','user_active',u.active,'project_identity',u.identity_source='test_oidc' AND u.subject LIKE 'http://127.0.0.1:18110/realms/mop-test|%') FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE " + where + ';')
        assert initial['active'] and initial['role'] == 'padmin' and initial['global_padmin'] and initial['user_active'] and initial['project_identity']
        assert isinstance(initial['permission_version'], int) and initial['permission_version'] >= 1
        assert initial_data['permission_version'] == initial['permission_version']
        result['initial_permission_version'] = initial['permission_version']
        result['global_and_initial_member_roles_are_padmin'] = True

        stage = 'downgrade_only_current_membership'
        # Mark before dispatch: an ambiguous SSH response must still trigger restoration.
        mutation_attempted = True
        lowered = sql_json(namespace, client, "SET ROLE mop_test_migrator; WITH changed AS (UPDATE public.mop_membership m SET role_code='viewer',permission_version=permission_version+1 WHERE " + where + " AND m.active AND m.role_code='padmin' AND m.permission_version=" + str(initial['permission_version']) + " RETURNING m.permission_version) SELECT json_build_object('permission_version',permission_version) FROM changed;")
        assert lowered['permission_version'] == initial['permission_version'] + 1
        result['downgraded_permission_version'] = lowered['permission_version']

        stage = 'viewer_session_projection'
        status, response = api('GET', '/session')
        result['viewer_session_http_status'] = status
        assert status == 200
        data = response['data']
        capabilities = set(data['capabilities'])
        assert data['user']['id'] == actor and data['user']['role'] == 'viewer'
        assert data['user']['display_name'] == initial_data['user']['display_name']
        assert data['tenant']['id'] == TENANT_A and data['permission_version'] == lowered['permission_version']
        assert {'assets:read', 'tickets:read', 'tickets:report'} <= capabilities
        assert not {'assets:write', 'configurations:read', 'configurations:write', 'audit:read'} & capabilities
        result['viewer_identity_preserved_role_and_capabilities_match'] = True

        stage = 'viewer_customer_report_with_original_cookie'
        suffix = uuid.uuid4().hex
        body = {'source': 'customer_report', 'description': '虚构成员角色投影 HTTP 验证 ' + suffix, 'category': '其他', 'impact_scope': 'individual', 'contact_name': '虚构角色验证联系人', 'contact_phone': '+86 (010) 1234-5678', 'urgency': 'normal'}
        status, response = api('POST', '/tickets', body, 'membership-role-report-' + suffix)
        result['viewer_report_http_status'] = status
        assert status == 202 and response['data']['status'] == 'PENDING'
        assert set(response['data']) == {'operation_id', 'status'}
        operation_id = str(uuid.UUID(response['data']['operation_id']))
        stage = 'viewer_registered_report_readback'
        status, response = api('GET', '/operations/' + operation_id)
        result['viewer_operation_http_status'] = status
        assert status == 200
        operation = response['data']
        assert operation['id'] == operation_id and operation['tenant_id'] == TENANT_A
        assert operation['kind'] == 'CUSTOMER_REPORT_REGISTRATION' and operation['status'] == 'PENDING'
        assert operation['step'] == 'AWAITING_TICKET_CONFIGURATION'
        path = operation['result_ref']
        assert re.fullmatch(r'/api/v1/tickets/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}', path)
        status, response = api('GET', path[len('/api/v1'):])
        result['viewer_ticket_http_status'] = status
        assert status == 200
        ticket = response['data']
        assert ticket['tenant_id'] == TENANT_A and ticket['reporter_id'] == actor and ticket['operation_id'] == operation_id
        assert ticket['description'] == body['description'] and ticket['registration_status'] == 'REGISTERED'
        assert ticket['authority_source'] == 'MOP' and ticket['authority_status'] == 'RULES_PENDING'
        assert ticket['state'] is None and ticket['priority'] is None and ticket['external_id'] is None and ticket['external_state'] is None
        result['viewer_report_uses_session_identity_and_remains_rules_pending'] = True
        result['original_cookie_preserved'] = True

        stage = 'viewer_admin_config_denied'
        status, response = api('GET', '/configurations')
        result['viewer_config_http_status'] = status
        assert status == 403 and response['error']['code'] == 'ACTION_FORBIDDEN'
        stage = 'viewer_asset_write_denied'
        status, response = api('POST', '/assets', {'name': '虚构拒绝资产 ' + suffix, 'type': 'router', 'management_ip': '192.0.2.123', 'site_id': 'site-a-main'}, 'membership-role-assets-' + suffix)
        result['viewer_asset_write_http_status'] = status
        assert status == 403 and response['error']['code'] == 'ACTION_FORBIDDEN'
        result['viewer_config_and_asset_write_rejected'] = True
    except Exception:
        # Never emit exception messages, request/response bodies or SSH/SQL output.
        failed_stage = stage
    finally:
        if mutation_attempted and client is not None and initial is not None:
            result['restore_attempted'] = True
            try:
                stage = 'restore_current_membership'
                active = 'true' if initial['active'] else 'false'
                # CAS avoids overwriting an unrelated concurrent authorization change.
                restored = sql_json(namespace, client, 'SET ROLE mop_test_migrator; WITH changed AS (UPDATE public.mop_membership m SET active=' + active + ',role_code=' + namespace.literal(initial['role']) + ',permission_version=permission_version+1 WHERE ' + where + ' AND ((m.role_code=\'viewer\' AND m.permission_version=' + str(initial['permission_version'] + 1) + ') OR (m.role_code=' + namespace.literal(initial['role']) + ' AND m.permission_version=' + str(initial['permission_version']) + ")) RETURNING m.active,m.role_code,m.permission_version) SELECT json_build_object('active',active,'role',role_code,'permission_version',permission_version) FROM changed;")
                assert restored['active'] == initial['active'] and restored['role'] == initial['role']
                assert restored['permission_version'] > initial['permission_version']
                restored_version = restored['permission_version']
                result['restored_permission_version'] = restored_version
                result['membership_restored'] = True
            except Exception:
                result['restore_failed'] = True
                result['membership_restored'] = False
                if failed_stage is None:
                    failed_stage = 'restore_current_membership'
        if client is not None:
            try:
                client.close()
            except Exception:
                if failed_stage is None:
                    failed_stage = 'close_project_connection'

    if restored_version is not None and not result['restore_failed']:
        try:
            stage = 'restored_session_projection'
            status, response = api('GET', '/session')
            result['restored_session_http_status'] = status
            assert status == 200
            data = response['data']
            assert data['user']['id'] == actor and data['user']['role'] == initial['role']
            assert data['tenant']['id'] == TENANT_A and data['permission_version'] == restored_version
            assert {'assets:write', 'configurations:read', 'configurations:write', 'audit:read', 'tickets:read'} <= set(data['capabilities'])
            assert 'tickets:report' not in data['capabilities']
            result['restored_session_role_and_capabilities_match'] = True
        except Exception:
            result['restored_session_role_and_capabilities_match'] = False
            if failed_stage is None:
                failed_stage = stage

    result['passed'] = failed_stage is None and result.get('membership_restored', False) and result.get('restored_session_role_and_capabilities_match', False)
    if failed_stage is not None:
        result['failed_stage'] = failed_stage
    try:
        EVIDENCE.parent.mkdir(parents=True, exist_ok=True)
        EVIDENCE.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    except Exception:
        result['passed'] = False
        result['failed_stage'] = 'write_safe_evidence'
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['passed'] else 1


if __name__ == '__main__':
    if sys.argv[1:] == ['--verify-redirect-policy-only']:
        try:
            passed = verify_redirect_policy_without_network()
        except Exception:
            passed = False
        print(json.dumps({'redirect_policy_without_network_passed': passed}))
        raise SystemExit(0 if passed else 1)
    if sys.argv[1:]:
        print(json.dumps({'passed': False, 'failed_stage': 'unsupported_arguments'}))
        raise SystemExit(1)
    raise SystemExit(main())

"""Scoped project test membership revocation with monotonic-version CAS restoration."""
import importlib.util
import json
import logging
import sys
import uuid
from inspect_test_server import ROOT, connect

STATE = ROOT / '.local/photo-resilience-membership.json'
SESSION = ROOT / 'deploy/private/qa-photo-viewerA.json'
TENANT = '55000000-0000-4000-8000-000000000001'


def main(action):
    logging.getLogger('paramiko').setLevel(logging.CRITICAL)
    client = None
    try:
        spec = importlib.util.spec_from_file_location('photo_scoped_namespace', ROOT / 'tools/prepare-test-namespace.py')
        namespace = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(namespace)
        session = json.loads(SESSION.read_text(encoding='utf-8-sig'))
        actor = str(uuid.UUID(session['user_id']))
        where = "m.user_id='" + actor + "'::uuid AND m.tenant_id='" + TENANT + "'::uuid"
        client = connect()

        def sql(text):
            rows = [line for line in namespace.sql(client, text, 'mop_test').splitlines() if line.startswith('{')]
            if len(rows) != 1:
                raise RuntimeError('Scoped query did not return exactly one row')
            return json.loads(rows[0])

        if action == 'initial':
            initial = sql("SELECT json_build_object('active',m.active,'role',m.role_code,'permission_version',m.permission_version,'user_active',u.active,'project_identity',u.identity_source='test_oidc' AND u.subject LIKE 'http://127.0.0.1:18110/realms/mop-test|%') FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE " + where + ';')
            assert initial['active'] and initial['role'] == 'viewer' and initial['user_active'] and initial['project_identity']
            initial.update(actor_id=actor, tenant_id=TENANT, mutation_attempted=False, restored=False)
            STATE.write_text(json.dumps(initial) + '\n', encoding='utf-8')
            return {'status': 'passed', 'action': action, 'permission_version': initial['permission_version'], 'project_scope_verified': True}
        if action == 'counts':
            counts = sql("SELECT json_build_object('files',(SELECT count(*) FROM public.mop_file WHERE tenant_id='" + TENANT + "'::uuid AND created_by='" + actor + "'::uuid),'jobs',(SELECT count(*) FROM public.mop_photo_job j JOIN public.mop_file f ON f.tenant_id=j.tenant_id AND f.id=j.file_id WHERE f.tenant_id='" + TENANT + "'::uuid AND f.created_by='" + actor + "'::uuid),'file_operations',(SELECT count(*) FROM public.mop_operation o JOIN public.mop_file f ON f.tenant_id=o.tenant_id AND f.id=o.file_id WHERE f.tenant_id='" + TENANT + "'::uuid AND f.created_by='" + actor + "'::uuid));")
            return {'status': 'passed', 'action': action, **counts}
        initial = json.loads(STATE.read_text(encoding='utf-8'))
        assert initial['actor_id'] == actor and initial['tenant_id'] == TENANT and initial['role'] == 'viewer'
        version = initial['permission_version']
        if action == 'revoke':
            initial['mutation_attempted'] = True
            STATE.write_text(json.dumps(initial) + '\n', encoding='utf-8')
            changed = sql("SET ROLE mop_test_migrator; WITH changed AS (UPDATE public.mop_membership m SET active=false,permission_version=permission_version+1 WHERE " + where + " AND m.active AND m.role_code='viewer' AND m.permission_version=" + str(version) + " RETURNING m.permission_version,m.active) SELECT json_build_object('permission_version',permission_version,'active',active) FROM changed;")
            assert not changed['active'] and changed['permission_version'] == version + 1
            return {'status': 'passed', 'action': action, 'permission_version': changed['permission_version'], 'only_project_actor_revoked': True}
        if action == 'restore':
            if initial.get('restored'):
                return {'status': 'passed', 'action': action, 'permission_version': initial['restored_version'], 'already_restored': True}
            changed = sql("SET ROLE mop_test_migrator; WITH changed AS (UPDATE public.mop_membership m SET active=true,role_code='viewer',permission_version=permission_version+1 WHERE " + where + " AND m.role_code='viewer' AND ((NOT m.active AND m.permission_version=" + str(version + 1) + ") OR (m.active AND m.permission_version=" + str(version) + ")) RETURNING m.permission_version,m.active,m.role_code) SELECT json_build_object('permission_version',permission_version,'active',active,'role',role_code) FROM changed;")
            assert changed['active'] and changed['role'] == 'viewer' and changed['permission_version'] > version
            initial.update(restored=True, restored_version=changed['permission_version'])
            STATE.write_text(json.dumps(initial) + '\n', encoding='utf-8')
            return {'status': 'passed', 'action': action, 'permission_version': changed['permission_version'], 'membership_restored': True}
        raise RuntimeError('Unsupported action')
    except Exception:
        return {'status': 'failed', 'action': action, 'details': 'redacted; no SQL, request or credential output'}
    finally:
        if client is not None:
            client.close()


if __name__ == '__main__':
    action = sys.argv[1] if len(sys.argv) == 2 and sys.argv[1] in {'initial', 'counts', 'revoke', 'restore'} else 'unsupported'
    result = main(action)
    print(json.dumps(result))
    raise SystemExit(0 if result['status'] == 'passed' else 1)

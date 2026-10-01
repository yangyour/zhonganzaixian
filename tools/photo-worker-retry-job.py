"""Schedule only one fresh MOP-FAKE photo job; never edit leases or result states."""
import importlib.util
import json
import logging
import re
import sys
import uuid
from inspect_test_server import ROOT, connect

REQUEST = ROOT / '.local/photo-worker-retry-request.json'
STATE = ROOT / '.local/photo-worker-retry-job.json'


def main(action):
    logging.getLogger('paramiko').setLevel(logging.CRITICAL)
    client = None
    try:
        request = json.loads(REQUEST.read_text(encoding='utf-8'))
        for field in ('run_id', 'actor_id', 'tenant_id', 'file_id', 'operation_id'):
            request[field] = str(uuid.UUID(request[field]))
        assert request['tenant_id'] == '55000000-0000-4000-8000-000000000001'
        assert request['filename'].startswith('MOP-FAKE-WORKER-RETRY-')
        assert len(request['filename']) < 200 and re.fullmatch('[0-9a-f]{64}', request['sha256'])
        assert isinstance(request['permission_version'], int) and request['permission_version'] >= 1
        spec = importlib.util.spec_from_file_location('retry_scoped_namespace', ROOT / 'tools/prepare-test-namespace.py')
        namespace = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(namespace)
        client = connect()
        literal = namespace.literal
        scope = "f.tenant_id=" + literal(request['tenant_id']) + "::uuid AND f.id=" + literal(request['file_id']) + "::uuid AND f.created_by=" + literal(request['actor_id']) + "::uuid AND f.operation_id=" + literal(request['operation_id']) + "::uuid AND f.original_name=" + literal(request['filename']) + " AND f.sha256=" + literal(request['sha256']) + " AND f.permission_version=" + str(request['permission_version'])
        joins = " FROM public.mop_photo_job j JOIN public.mop_file f ON f.tenant_id=j.tenant_id AND f.id=j.file_id JOIN public.mop_operation o ON o.tenant_id=f.tenant_id AND o.id=f.operation_id AND o.file_id=f.id AND o.kind='FILE_UPLOAD'"

        def rows(text):
            assert namespace.sql(client, 'SELECT current_database();', 'mop_test').strip() == 'mop_test'
            return [json.loads(line) for line in namespace.sql(client, text, 'mop_test').splitlines() if line.startswith('{')]

        def one(text):
            result = rows(text)
            if len(result) != 1:
                raise RuntimeError('Expected exactly one scoped row')
            return result[0]

        initial = "j.state='PENDING' AND j.fencing_token=0 AND j.attempts=0 AND j.lease_owner IS NULL AND j.lease_until IS NULL AND f.upload_status='PENDING' AND f.version=1 AND f.ticket_id IS NULL AND o.status='PENDING' AND o.step='QUEUED'"
        if action == 'hold':
            if STATE.exists():
                previous = json.loads(STATE.read_text(encoding='utf-8'))
                assert previous.get('complete') or previous.get('cleanup_complete')
            row = one("SELECT json_build_object('job_id',j.id,'held_next_at',(clock_timestamp()+interval '15 minutes')::timestamptz(3),'other_pending_jobs',(SELECT count(*) FROM public.mop_photo_job other_job JOIN public.mop_file other_file ON other_file.tenant_id=other_job.tenant_id AND other_file.id=other_job.file_id WHERE other_job.id<>j.id AND other_file.upload_status='PENDING' AND other_job.state IN ('PENDING','LEASED')),'authorized',EXISTS(SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=f.tenant_id AND m.user_id=f.created_by AND m.active AND u.active AND m.role_code='viewer' AND m.permission_version=f.permission_version))" + joins + " WHERE " + scope + " AND " + initial + ';')
            assert row['other_pending_jobs'] == 0 and row['authorized']
            state = {**request, 'job_id': row['job_id'], 'held_next_at': row['held_next_at'], 'hold_attempted': True, 'released': False, 'complete': False, 'cleanup_complete': False}
            # Persist the exact server timestamp before dispatch, so an ambiguous response remains recoverable by CAS.
            STATE.write_text(json.dumps(state) + '\n', encoding='utf-8')
            changed = one("BEGIN; SET LOCAL ROLE mop_test_migrator; WITH changed AS (UPDATE public.mop_photo_job j SET next_at=" + literal(state['held_next_at']) + "::timestamptz FROM public.mop_file f,public.mop_operation o WHERE f.tenant_id=j.tenant_id AND f.id=j.file_id AND o.tenant_id=f.tenant_id AND o.id=f.operation_id AND o.file_id=f.id AND o.kind='FILE_UPLOAD' AND " + scope + " AND j.id=" + literal(state['job_id']) + "::uuid AND " + initial + " RETURNING j.id) SELECT json_build_object('changed_rows',count(*)) FROM changed; COMMIT;")
            assert changed['changed_rows'] == 1
            return {'status': 'passed', 'action': action, 'changed_rows': 1, 'only_next_at_changed': True, 'initial_token': 0, 'initial_attempts': 0}

        state = json.loads(STATE.read_text(encoding='utf-8'))
        for field in ('run_id', 'actor_id', 'tenant_id', 'file_id', 'operation_id', 'filename', 'sha256', 'permission_version'):
            assert state[field] == request[field]
        job_scope = scope + ' AND j.id=' + literal(str(uuid.UUID(state['job_id']))) + '::uuid'
        held = 'j.next_at=' + literal(state['held_next_at']) + '::timestamptz'
        if action in {'make-due', 'cleanup'}:
            changed = one("BEGIN; SET LOCAL ROLE mop_test_migrator; WITH changed AS (UPDATE public.mop_photo_job j SET next_at=clock_timestamp() FROM public.mop_file f,public.mop_operation o WHERE f.tenant_id=j.tenant_id AND f.id=j.file_id AND o.tenant_id=f.tenant_id AND o.id=f.operation_id AND o.file_id=f.id AND o.kind='FILE_UPLOAD' AND " + job_scope + " AND " + initial + ' AND ' + held + " RETURNING j.id) SELECT json_build_object('changed_rows',count(*)) FROM changed; COMMIT;")
            if action == 'make-due':
                assert changed['changed_rows'] == 1
                state['released'] = True
            else:
                remaining = one("SELECT json_build_object('still_initial_harness_hold',(" + initial + ' AND ' + held + '))' + joins + ' WHERE ' + job_scope + ';')
                assert not remaining['still_initial_harness_hold']
                state['cleanup_complete'] = True
            STATE.write_text(json.dumps(state) + '\n', encoding='utf-8')
            return {'status': 'passed', 'action': action, 'changed_rows': changed['changed_rows'], 'no_lease_token_attempt_or_result_reset': True}
        if action == 'observe':
            row = one("SELECT json_build_object('job_state',j.state,'token',j.fencing_token,'attempts',j.attempts,'next_at_future',j.next_at>clock_timestamp(),'lease_owner_null',j.lease_owner IS NULL,'lease_until_null',j.lease_until IS NULL,'file_status',f.upload_status,'file_version',f.version,'operation_status',o.status,'operation_step',o.step,'error_code',o.error->>'code','retryable',o.error->>'retryable','worker_retry_audit_count',(SELECT count(*) FROM public.mop_audit a WHERE a.tenant_id=f.tenant_id AND a.payload->>'resource_id'=f.id::text AND a.payload->>'action'='file.worker.retry' AND a.payload->>'reason'='RETRY_WAIT'))" + joins + ' WHERE ' + job_scope + ';')
            if row['job_state'] == 'SUCCEEDED' and row['file_status'] == 'READY' and row['operation_status'] == 'SUCCEEDED' and row['operation_step'] == 'STORED':
                state['complete'] = True
                STATE.write_text(json.dumps(state) + '\n', encoding='utf-8')
            return {'status': 'passed', 'action': action, 'same_run_project_job': True, **row}
        raise RuntimeError('Unsupported action')
    except Exception:
        return {'status': 'failed', 'action': action, 'details': 'redacted; no SQL, connection or credential output'}
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass


if __name__ == '__main__':
    action = sys.argv[1] if len(sys.argv) == 2 and sys.argv[1] in {'hold', 'make-due', 'cleanup', 'observe'} else 'unsupported'
    result = main(action)
    print(json.dumps(result))
    raise SystemExit(0 if result['status'] == 'passed' else 1)

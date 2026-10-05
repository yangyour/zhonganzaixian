"""Restart only the labelled project API and compare selected persisted acceptance records."""
import datetime
import importlib.util
import json
import re
import time
import urllib.error
import urllib.request
from inspect_test_server import ROOT, connect

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT/path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module

deploy = load('deployment', 'tools/deploy-test-server.py')
namespace = load('namespace', 'tools/prepare-test-namespace.py')
out = ROOT/'docs/evidence/server-api-restart-20261005.json'
assert not out.exists(), 'Do not repeat a completed lifecycle check'
cfg = deploy.read_json(ROOT/'deploy/private/server-test-runtime.json')
state = deploy.read_json(ROOT/'.local/test-server-deployment.json')
actions = deploy.read_json(ROOT/'.local/server-acceptance/private/browser-actions-control.json')
workers = deploy.read_json(ROOT/'.local/server-acceptance/private/deployed-worker-control.json')
export = deploy.read_json(ROOT/'.local/server-acceptance/private/browser-export-control.json')
selected = {
    'mop_asset': actions['receipts'][0]['data']['id'], 'mop_inspection_source_collection': actions['source_id'],
    'mop_notification_preference_draft': actions['preference_id'], 'mop_customer_contact': workers['objects']['contact_id'],
    'mop_asset_import_source': workers['objects']['source_id'], 'mop_file': workers['objects']['photo_id'],
    'mop_asset_export_job': export['receipt']['job_id'],
}
assert all(re.fullmatch('[0-9a-f-]{36}', identifier) for identifier in selected.values())
sql = 'BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SELECT json_object_agg(name,digest) FROM ('+' UNION ALL '.join(
    f"SELECT '{table}' name, encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') digest FROM {table} t WHERE id='{identifier}'::uuid"
    for table, identifier in selected.items()) + ') s; ROLLBACK;'
ssh = connect()
try:
    labels = json.loads(deploy.command(ssh, ['docker','inspect','--format','{{json .Config.Labels}}','mop-test-api']))
    assert labels.get('mop.project') == 'mop-platform-test' and labels.get('mop.release') == state['revision']
    names = ['mop-test-api','mop-test-web','mop-test-photo-worker','mop-test-import-worker','mop-test-contact-worker','shared-postgres','hltk-keycloak','shared-minio']
    def starts(): return {name: deploy.command(ssh,['docker','inspect','--format','{{.State.StartedAt}}',name]).strip() for name in names}
    before_starts = starts()
    before = json.loads(namespace.sql(ssh,sql,'mop_test').splitlines()[1]); assert len(before) == 7
    deploy.command(ssh,['docker','restart','mop-test-api'])
    ready = False
    for attempt in range(45):
        try:
            with urllib.request.urlopen(cfg['public_origin']+'/api/v1/health/ready',timeout=5) as response:
                ready = response.status == 200 and json.load(response)['data']['ready'] is True
            if ready: break
        except (urllib.error.URLError, TimeoutError): pass
        time.sleep(1)
    assert ready
    after = json.loads(namespace.sql(ssh,sql,'mop_test').splitlines()[1]); assert after == before
    after_starts = starts(); assert before_starts['mop-test-api'] != after_starts['mop-test-api']
    assert all(after_starts[name] == before_starts[name] for name in names if name != 'mop-test-api')
    old = deploy.read_json(ROOT/'.local/server-acceptance/private/sessions/qa-padmin.json')
    request = urllib.request.Request(cfg['public_origin']+'/api/v1/session', headers={'Cookie':old['cookie']})
    try:
        urllib.request.urlopen(request,timeout=10)
        raise AssertionError('Old API in-memory session should require a fresh login')
    except urllib.error.HTTPError as failure: assert failure.code == 401
    evidence = {'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'designated-server-acceptance',
        'only_project_api_restarted':True,'other_seven_containers_start_times_unchanged':True,'selected_persisted_rows':7,
        'selected_row_sha256_before_after_equal':True,'ready_after_restart':True,'old_session_status':401,
        'postgresql_restarted':False,'database_restore_tested':False,'formal_acceptance':'NOT_RUN'}
    out.write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf8');print(json.dumps(evidence))
finally: ssh.close()

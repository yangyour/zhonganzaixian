"""Prepare exactly the job accepted through the real browser, then inspect its real XLSX bytes."""
import base64
import datetime
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from inspect_test_server import ROOT, connect

def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value

deployment = module('deployment', 'tools/deploy-test-server.py')
namespace = module('namespace', 'tools/prepare-test-namespace.py')
output = ROOT / 'docs/evidence/server-export-worker-20261005.json'
if output.exists(): raise SystemExit('Preserve the completed export evidence; no repeat execution')
state = deployment.read_json(ROOT / '.local/test-server-deployment.json')
cfg = deployment.read_json(ROOT / 'deploy/private/server-test-runtime.json')
control = deployment.read_json(ROOT / '.local/server-acceptance/private/browser-export-control.json')
job = control['receipt']['job_id']
actor = next(user['platform_user_id'] for user in cfg['test_users'] if user['role'] == 'padmin')
assert re.fullmatch('[0-9a-f-]{36}', job) and re.fullmatch('[0-9a-f-]{36}', actor)
where = f"id='{job}'::uuid AND created_by='{actor}'::uuid"
ssh = connect()
try:
    ready = namespace.sql(ssh, f"SELECT state='QUEUED' AND fencing_token=0 AND file_bytes IS NULL FROM mop_asset_export_job WHERE {where};", 'mop_test').strip()
    assert ready == 't', 'The selected original job must still be unclaimed; do not create or overwrite it'
    fingerprint = f"SELECT encode(sha256(convert_to(COALESCE(string_agg(to_jsonb(j)::text,E'\\n' ORDER BY j.id),''),'UTF8')),'hex') FROM mop_asset_export_job j WHERE j.id<>'{job}'::uuid;"
    before = namespace.sql(ssh, fingerprint, 'mop_test').strip()
    result = deployment.command(ssh, ['docker', 'run', '--rm', '--name', 'mop-test-explicit-export-proof', '--network', 'host',
        '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
        '--label', 'mop.project=mop-platform-test', '--label', 'mop.job='+job, '--memory', '768m', '--cpus', '2',
        '--tmpfs', '/tmp:rw,nosuid,size=256m', '--workdir', '/tmp', '-v', state['release']+':/app:ro',
        '--env-file', '/opt/mop-platform-test/private/export.env',
        '-e', 'MOP_EXPORT_PREPARATION_WORKER_ENABLED=true', '-e', 'MOP_EXPORT_PREPARATION_WORKER_ID=server-acceptance-20261005',
        '-e', 'MOP_EXPORT_PREPARATION_LEASE_MILLIS=60000', state['images']['eclipse-temurin:17-jre'],
        'java', '-XX:MaxRAMPercentage=60.0', '-jar', '/app/export-worker.jar', '--prepare-one-without-publication', job])
    (ROOT / '.local/server-acceptance/private/export-worker.log').write_text(result, encoding='utf8')
    data = next(json.loads(line) for line in reversed(result.splitlines()) if line.startswith('{'))
    assert data['status'] == 'PREPARED_STORAGE_NOT_ATTEMPTED' and data['data_rows'] == 1
    raw = namespace.sql(ssh, f"SELECT encode(file_bytes,'base64') FROM mop_asset_export_job WHERE {where};", 'mop_test')
    file_bytes = base64.b64decode(raw)
    assert hashlib.sha256(file_bytes).hexdigest() == data['file_sha256']
    with zipfile.ZipFile(io.BytesIO(file_bytes)) as archive:
        assert archive.testzip() is None
        sheet = ET.fromstring(archive.read('xl/worksheets/sheet1.xml'))
        ns = {'x': 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
        rows = sheet.findall('x:sheetData/x:row', ns)
        assert len(rows) == 2 and len(rows[0].findall('x:c', ns)) == 16 and len(rows[1].findall('x:c', ns)) == 16
    (ROOT / '.local/server-acceptance/private/actual-server-export.xlsx').write_bytes(file_bytes)
    assert namespace.sql(ssh, fingerprint, 'mop_test').strip() == before
    evidence = {'verified_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'environment': 'designated-server-acceptance',
        'api_version': '0.44.0', 'trigger': 'real browser form 202', 'remote_java_worker': True, 'row_count': 1, 'columns': 16,
        'file_bytes': len(file_bytes), 'file_sha256': data['file_sha256'], 'xlsx_zip_and_sheet_verified': True,
        'stored_pg_bytes_equal_worker_hash': True, 'other_export_jobs_unchanged': True, 'published': False,
        'object_store_requests': 0, 'download_grants': 0, 'notifications': 0, 'completed_preparation': True, 'formal_acceptance': 'NOT_RUN'}
    output.write_text(json.dumps(evidence, indent=2)+'\n', encoding='utf8')
    print(json.dumps(evidence))
finally:
    ssh.close()

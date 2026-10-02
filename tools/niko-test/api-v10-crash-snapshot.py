"""Fingerprint V1-V10 business rows for a separate explicit owned API crash scenario; never output row contents."""
from pathlib import Path
import os,json,subprocess,sys,datetime,socket,errno
ROOT=Path.cwd();LOCAL=ROOT/'.local/niko-test'
BEFORE=LOCAL/'api-v10-crash-db-before.json';AFTER=LOCAL/'api-v10-crash-db-verified.json';HTTP=LOCAL/'api-v10-crash-http-before.json';FINAL=ROOT/'docs/evidence/niko-api-v10-crash-recovery-20261002.json'
TABLES=('mop_asset','mop_ticket','mop_worklog','mop_worklog_revision','mop_asset_dependency_graph','mop_asset_dependency_revision','mop_asset_dependency_edge','mop_configuration_revision','mop_configuration_revision_history','mop_idempotency','mop_audit','mop_asset_manual_details','mop_asset_manual_revision','mop_ticket_internal_note')
def private_write(path,value):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as file:json.dump(value,file,ensure_ascii=False,indent=2);file.write('\n')
def check_new():
    if any(path.exists() or path.is_symlink() for path in (BEFORE,AFTER,HTTP,FINAL)):raise RuntimeError('Evidence path already exists; no overwrite or fault run')
def fingerprint():
    runtime=json.loads((LOCAL/'private/runtime.json').read_text());settings=runtime['environment'];pg=ROOT.parent/'mop-toolchain/components/pg'
    if settings['SPRING_DATASOURCE_URL']!='jdbc:postgresql://127.0.0.1:25432/mop_test' or settings['SPRING_DATASOURCE_USERNAME']!='mop_test_runtime':raise RuntimeError('Unexpected test database scope')
    env=os.environ.copy();env.update(PGPASSWORD=settings['SPRING_DATASOURCE_PASSWORD'],LD_LIBRARY_PATH=str(pg/'usr/lib/x86_64-linux-gnu'))
    args=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
    # One repeatable-read read-only snapshot, explicit whitelisted tables, and no raw rows on stdout.
    sql='BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET LOCAL statement_timeout=10000;\n'
    for table in TABLES:sql+="SELECT json_build_object('table','"+table+"','rows',count(*),'digest',encode(sha256(convert_to(COALESCE(string_agg(row_to_json(t)::text,E'\\n' ORDER BY row_to_json(t)::text),''),'UTF8')),'hex'))::text FROM public."+table+" t;\n"
    sql+='ROLLBACK;\n'
    result=subprocess.run(args,input=sql,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
    if result.returncode:raise RuntimeError('Snapshot query failed; private details withheld')
    rows=[json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
    if [row['table'] for row in rows]!=list(TABLES):raise RuntimeError('Incomplete snapshot')
    # These are equality fingerprints only, not cryptographic provenance or authenticity proofs.
    return {row['table']:{'rows':row['rows'],'equality_sha256':row['digest']} for row in rows}
def main():
    mode=sys.argv[1:]
    if mode==['--check-new-run']:check_new();print('new_api_crash_evidence_paths_available=true')
    elif mode==['--record']:
        if BEFORE.exists() or AFTER.exists() or FINAL.exists():raise RuntimeError('Evidence path already exists')
        private_write(BEFORE,{'recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'tables':fingerprint()});print('api_pre_crash_fingerprint_recorded=true tables='+str(len(TABLES)))
    elif mode==['--api-unavailable']:
        try:
            with socket.create_connection(('127.0.0.1',8080),timeout=3):pass
        except OSError as failure:
            if failure.errno==errno.ECONNREFUSED:print('owned_api_connection_refused_after_kill=true');return
            raise RuntimeError('API unavailability was not a confirmed connection refusal') from None
        raise RuntimeError('API listener still reachable; fault not verified')
    elif mode==['--verify']:
        expected=json.loads(BEFORE.read_text());actual=fingerprint()
        if expected['tables']!=actual:raise RuntimeError('Business fingerprints differ after API restart')
        private_write(AFTER,{'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'before_at':expected['recorded_at'],'database_fingerprints_equal':True,'tables':actual,'fingerprint_purpose':'equality_only_not_authenticity','api_exit_code':137,'postgres_restarted':False,'keycloak_restarted':False});print('api_post_crash_fingerprints_equal=true tables='+str(len(TABLES)))
    else:raise RuntimeError('Use one explicit supported mode')
if __name__=='__main__':
    try:main()
    except Exception as failure:print(json.dumps({'status':'FAILED','exception_type':type(failure).__name__,'details':'redacted'}));raise SystemExit(1)

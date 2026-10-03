"""Explicit V15 legacy-row equality check. Read-only; no raw rows or credentials are emitted."""
from pathlib import Path
import os,json,subprocess,sys,datetime
ROOT=Path.cwd();LOCAL=ROOT/'.local/niko-test'
BEFORE=LOCAL/'warranty-v15-legacy-before.json';AFTER=LOCAL/'warranty-v15-legacy-after.json'
TABLES=('mop_asset','mop_ticket','mop_worklog','mop_worklog_revision','mop_asset_dependency_graph','mop_asset_dependency_revision','mop_asset_dependency_edge','mop_configuration_revision','mop_configuration_revision_history','mop_idempotency','mop_audit','mop_asset_manual_details','mop_asset_manual_revision','mop_ticket_internal_note','mop_ticket_comment','mop_working_calendar_draft','mop_working_calendar_revision','mop_manual_incident_registration','mop_manual_recovery_confirmation','mop_configuration')
def private_write(path,value):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as file:json.dump(value,file,ensure_ascii=False,indent=2);file.write('\n')
def fingerprint():
    runtime=json.loads((LOCAL/'private/runtime.json').read_text());settings=runtime['environment'];pg=ROOT.parent/'mop-toolchain/components/pg'
    if settings['SPRING_DATASOURCE_URL']!='jdbc:postgresql://127.0.0.1:25432/mop_test' or settings['SPRING_DATASOURCE_USERNAME']!='mop_test_runtime':raise RuntimeError('Unexpected test database scope')
    env=os.environ.copy();env.update(PGPASSWORD=settings['SPRING_DATASOURCE_PASSWORD'],LD_LIBRARY_PATH=str(pg/'usr/lib/x86_64-linux-gnu'))
    args=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
    # One repeatable-read read-only snapshot, explicit whitelisted tables, and no raw rows on stdout.
    sql='BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSET LOCAL statement_timeout=10000;\n'
    for table in TABLES:
        projection="(to_jsonb(t)-ARRAY['warranty_start','warranty_supplier','warranty_details_declared'])::text" if table=='mop_asset_manual_revision' else 'to_jsonb(t)::text'
        sql+="SELECT json_build_object('table','"+table+"','rows',count(*),'digest',encode(sha256(convert_to(COALESCE(string_agg("+projection+",E'\\n' ORDER BY "+projection+"),''),'UTF8')),'hex'))::text FROM public."+table+" t;\n"
    sql+='ROLLBACK;\n'
    result=subprocess.run(args,input=sql,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=90)
    if result.returncode:raise RuntimeError('Snapshot query failed; private details withheld')
    rows=[json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
    if [row['table'] for row in rows]!=list(TABLES):raise RuntimeError('Incomplete snapshot')
    # These are equality fingerprints only, not cryptographic provenance or authenticity proofs.
    return {row['table']:{'rows':row['rows'],'equality_sha256':row['digest']} for row in rows}
def main():
    mode=sys.argv[1:]
    if mode not in [['--record'],['--verify']]:raise RuntimeError('Explicit record or verify mode required')
    if AFTER.exists() or AFTER.is_symlink():raise RuntimeError('Existing after evidence; refusing overwrite')
    if mode==['--record']:
        if BEFORE.exists() or BEFORE.is_symlink():raise RuntimeError('Existing before evidence; refusing overwrite')
        private_write(BEFORE,{'recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'tables':fingerprint()})
        print('legacy_row_fingerprints_recorded=true tables='+str(len(TABLES)))
    else:
        expected=json.loads(BEFORE.read_text());actual=fingerprint()
        if expected['tables']!=actual:raise RuntimeError('Legacy business rows differ')
        private_write(AFTER,{'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'before_at':expected['recorded_at'],'legacy_rows_equal':True,'tables':actual,'excluded_columns':{'mop_asset_manual_revision':['warranty_start','warranty_supplier','warranty_details_declared']},'purpose':'equality_only_not_authenticity'})
        print('legacy_row_fingerprints_equal=true tables='+str(len(TABLES)))
if __name__=='__main__':
    try:main()
    except Exception as failure:print(json.dumps({'status':'FAILED','exception_type':type(failure).__name__,'details':'redacted'}));raise SystemExit(1)

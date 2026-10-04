"""Read-only equality proof around an explicitly owned graceful stack restart. Never stops processes."""
from pathlib import Path
import datetime,json,os,subprocess,sys
ROOT=Path.cwd();LOCAL=ROOT/'.local/niko-test'
BEFORE=LOCAL/'export-queue-restart-before.json';AFTER=LOCAL/'export-queue-restart-after.json'
TABLES=('mop_asset_export_job','mop_asset_export_attempt','mop_idempotency','mop_audit')
def private_write(path,value):
    fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:json.dump(value,f,indent=2);f.write('\n')
def capture():
    runtime=json.loads((LOCAL/'private/runtime.json').read_text())['environment'];pg=ROOT.parent/'mop-toolchain/components/pg'
    if runtime['SPRING_DATASOURCE_URL']!='jdbc:postgresql://127.0.0.1:25432/mop_test' or runtime['SPRING_DATASOURCE_USERNAME']!='mop_test_runtime':raise RuntimeError('Unexpected project test database')
    env=os.environ.copy();env.update(PGPASSWORD=runtime['SPRING_DATASOURCE_PASSWORD'],LD_LIBRARY_PATH=str(pg/'usr/lib/x86_64-linux-gnu'))
    args=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
    sql="BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET LOCAL statement_timeout=10000; SET LOCAL timezone='UTC';\n"
    sql+="SELECT json_build_object('kind','instance','postmaster_started_at',pg_postmaster_start_time(),'server_version',current_setting('server_version'),'current_role',current_user)::text;\n"
    for table in TABLES:
        sql+="SELECT json_build_object('kind','table','table','"+table+"','rows',count(*),'equality_sha256',encode(sha256(convert_to(COALESCE(string_agg(to_jsonb(t)::text,E'\\n' ORDER BY to_jsonb(t)::text),''),'UTF8')),'hex'))::text FROM public."+table+" t;\n"
    sql+="SELECT json_build_object('kind','queue','queued',count(*) FILTER(WHERE state='QUEUED'),'total',count(*),'prepared',count(*) FILTER(WHERE file_bytes IS NOT NULL),'stored_receipt_descriptions',count(*) FILTER(WHERE object_id IS NOT NULL))::text FROM public.mop_asset_export_job; ROLLBACK;\n"
    result=subprocess.run(args,input=sql,env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60)
    if result.returncode:raise RuntimeError('Readonly restart snapshot failed; private diagnostics withheld')
    values=[json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
    if len(values)!=len(TABLES)+2 or values[0]['kind']!='instance' or values[-1]['kind']!='queue' or tuple(v['table'] for v in values[1:-1])!=TABLES:raise RuntimeError('Incomplete snapshot')
    instance=values[0]
    if instance['current_role']!='mop_test_runtime':raise RuntimeError('Unexpected runtime identity')
    if values[-1]['total']<1 or values[-1]['queued']!=values[-1]['total'] or values[-1]['prepared'] or values[-1]['stored_receipt_descriptions']:raise RuntimeError('Expected only actual pending acceptance test jobs')
    return {'instance':instance,'tables':{v['table']:{'rows':v['rows'],'equality_sha256':v['equality_sha256']} for v in values[1:-1]},'queue':values[-1]}
def main():
    if sys.argv[1:] not in [['--record'],['--verify']]:raise RuntimeError('Explicit record or verify required')
    mode=sys.argv[1];path=BEFORE if mode=='--record' else AFTER
    if path.exists() or path.is_symlink() or (mode=='--record' and AFTER.exists()):raise RuntimeError('Evidence already exists; refusing overwrite')
    if mode=='--verify' and (BEFORE.is_symlink() or not BEFORE.is_file()):raise RuntimeError('Recorded before snapshot required')
    actual=capture()
    if mode=='--verify':
        before=json.loads(BEFORE.read_text())['snapshot']
        if before['tables']!=actual['tables'] or before['queue']!=actual['queue'] or before['instance']['server_version']!=actual['instance']['server_version']:raise RuntimeError('Persisted queue, receipt or audit facts changed')
        if before['instance']['postmaster_started_at']==actual['instance']['postmaster_started_at']:raise RuntimeError('No PostgreSQL restart observed')
    private_write(path,{'recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'snapshot':actual})
    print(json.dumps({'environment':'niko-local-test','stage':mode,'tables_compared':len(TABLES),'queued_jobs':actual['queue']['queued'],'equal_to_before':True if mode=='--verify' else None,'different_postmaster_start_verified':mode=='--verify','sql_writes':0,'actual_store_verified':False}))
if __name__=='__main__':
    try:main()
    except Exception as failure:print(json.dumps({'status':'FAILED','exception_type':type(failure).__name__,'details':'redacted'}));raise SystemExit(1)

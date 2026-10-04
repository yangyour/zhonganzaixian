"""One owned real preparation JVM for a freshly accepted native-page proof job. No queue draining or publication."""
from pathlib import Path
import base64,datetime,hashlib,io,json,os,re,signal,subprocess,sys,time,uuid,zipfile
import xml.etree.ElementTree as ET
from export_worker_artifact import prepare_artifact,verified_artifact
from owned_export_worker import is_owned_export_worker

def canonical_id(value):
    if not isinstance(value,str) or str(uuid.UUID(value))!=value:raise ValueError('Canonical UUID required')
    return value

def target(control):
    if not isinstance(control,dict) or control.get('completed') is not False or control.get('ui_receipt_confirmed') is not True:raise ValueError('Fresh accepted page proof required')
    marker=control.get('marker');prefix='SYNTHETIC_EXPORT_E2E_'
    if not isinstance(marker,str) or not marker.startswith(prefix):raise ValueError('Synthetic proof marker required')
    canonical_id(marker[len(prefix):])
    original=control.get('original');scope=control.get('accepted_scope')
    if not isinstance(original,dict) or set(original)!={'path','key','body','job_id'} or original['path']!='/asset-export-jobs':raise ValueError('Exact original command required')
    if not isinstance(original['key'],str) or re.fullmatch(r'[A-Za-z0-9._:-]{1,128}',original['key']) is None:raise ValueError('Original key required')
    job=canonical_id(original['job_id']);body=original['body']
    if not isinstance(body,dict) or set(body)!={'expected_version','mode','filters'} or type(body['expected_version']) is not int or body['expected_version']!=0 or body['mode']!='PREPARE_ONLY_NO_PUBLICATION' or body['filters']!={'type':None,'status':None,'site_id':'','query':marker}:raise ValueError('Explicit isolated preparation filter required')
    if not isinstance(scope,dict) or set(scope)!={'actor_id','tenant_id','role','permission_version'} or scope['role'] not in ('padmin','msp') or type(scope['permission_version']) is not int or not 1<=scope['permission_version']<=9007199254740991:raise ValueError('Current captured scope required')
    canonical_id(scope['actor_id']);canonical_id(scope['tenant_id'])
    return job,scope

def private_environment(settings,ambient):
    if settings.get('SPRING_DATASOURCE_URL')!='jdbc:postgresql://127.0.0.1:25432/mop_test' or settings.get('SPRING_DATASOURCE_USERNAME')!='mop_test_runtime' or not isinstance(settings.get('SPRING_DATASOURCE_PASSWORD'),str) or not settings['SPRING_DATASOURCE_PASSWORD'].strip():raise ValueError('Existing restricted niko runtime required')
    env={k:v for k,v in ambient.items() if k in ('PATH','JAVA_HOME','HOME','LANG','TMPDIR')}
    env.update({k:settings[k] for k in ('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD')})
    env.update(TZ='UTC',SPRING_PROFILES_ACTIVE='remote_test',MOP_EXPORT_PREPARATION_WORKER_ENABLED='true',MOP_EXPORT_PREPARATION_WORKER_ID='niko-page-e2e',MOP_EXPORT_PREPARATION_LEASE_MILLIS='30000')
    for k in ('MOP_PHOTO_WORKER_ENABLED','MOP_IMPORT_WORKER_ENABLED','MOP_ASSET_IMPORT_WORKER_ENABLED','MOP_CONTACT_IMPORT_WORKER_ENABLED'):env[k]='false'
    return env

def decode_file(value):
    if not isinstance(value,str) or len(value)>24*1024*1024:raise ValueError('Bounded PostgreSQL base64 required')
    # PostgreSQL encode(...,'base64') inserts LF line wrapping. Remove only LF; validate all other bytes.
    return base64.b64decode(value.replace('\n',''),validate=True)

def workbook(bytes_value,descriptor):
    if len(bytes_value)!=descriptor['file_size'] or hashlib.sha256(bytes_value).hexdigest()!=descriptor['file_sha256'] or descriptor['row_count']!=0:raise ValueError('Actual bytes differ from expected empty selection descriptor')
    with zipfile.ZipFile(io.BytesIO(bytes_value)) as z:
        names=z.namelist()
        if len(names)!=len(set(names)) or len(names)>100 or sum(i.file_size for i in z.infolist())>16*1024*1024 or z.testzip() is not None:raise ValueError('Invalid bounded workbook')
        ns={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
        sheet=ET.fromstring(z.read('xl/worksheets/sheet1.xml'));rows=sheet.findall('s:sheetData/s:row',ns)
        if len(rows)!=1 or len(rows[0].findall('s:c',ns))!=16:raise ValueError('Expected header-only sixteen-column workbook')
        strings=[''.join(node.itertext()) for node in ET.fromstring(z.read('xl/sharedStrings.xml')).findall('s:si',ns)]
        headers=[strings[int(cell.find('s:v',ns).text)] for cell in rows[0].findall('s:c',ns)]
        if headers[-1]!='维保状态' or len(set(headers))!=16:raise ValueError('Unexpected workbook header')
        if b'ENCODED_NOT_PUBLISHED' not in z.read('docProps/custom.xml'):raise ValueError('Workbook publication boundary missing')
    return {'valid_zip':True,'header_columns':16,'data_rows':0,'warranty_status_header':True,'publication_metadata':'ENCODED_NOT_PUBLISHED','bytes_match_persisted_sha256':True}

def inspect_job(root,job,scope,settings,include_bytes=False):
    pg=root.parent/'mop-toolchain/components/pg';psql=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
    pg_env={k:v for k,v in os.environ.items() if k in ('PATH','HOME','LANG','TMPDIR')};pg_env.update(PGPASSWORD=settings['SPRING_DATASOURCE_PASSWORD'],LD_LIBRARY_PATH=str(pg/'usr/lib/x86_64-linux-gnu'),TZ='UTC')
    sql="BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET LOCAL statement_timeout=10000; SELECT json_build_object('tenant_id',tenant_id,'created_by',created_by,'role',accepted_role,'permission_version',permission_version,'state',state,'version',version,'attempts',fencing_token,'history_count',(SELECT count(*) FROM public.mop_asset_export_attempt h WHERE h.job_id=j.id AND h.tenant_id=j.tenant_id),'prepared',file_bytes IS NOT NULL,'file_sha256',file_sha256,'file_size',file_size,'row_count',row_count,'object_id',object_id,'error',error_code"+(",'encoded_file',encode(file_bytes,'base64')" if include_bytes else '')+") FROM public.mop_asset_export_job j WHERE id='"+job+"'::uuid; ROLLBACK;"
    r=subprocess.run(psql,input=sql,env=pg_env,capture_output=True,text=True,timeout=25)
    if r.returncode:raise RuntimeError('Read-only project inspection failed; details withheld')
    lines=[json.loads(x) for x in r.stdout.splitlines() if x.startswith('{')]
    if len(lines)!=1:raise ValueError('Expected one owned proof job')
    row=lines[0]
    if row['tenant_id']!=scope['tenant_id'] or row['created_by']!=scope['actor_id'] or row['role']!=scope['role'] or row['permission_version']!=scope['permission_version']:raise ValueError('Proof scope mismatch')
    return row

def main():
    if sys.argv[1:]!=['--prepare-accepted-page-proof']:raise ValueError('Explicit one-shot proof mode required')
    root=Path.cwd();local=root/'.local/niko-test';control_file=local/'export-page-e2e-control.json';journal=local/'export-page-e2e-worker-control.json'
    if journal.exists() or journal.is_symlink():raise ValueError('Existing worker proof; no automatic repeat')
    control=json.loads(control_file.read_text());job,scope=target(control)
    settings=json.loads((local/'private/runtime.json').read_text())['environment'];env=private_environment(settings,os.environ)
    java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java';pg=root.parent/'mop-toolchain/components/pg';psql=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
    query=lambda include_bytes=False:inspect_job(root,job,scope,settings,include_bytes)
    before=query()
    if before['state']!='QUEUED' or before['version']!=0 or before['attempts']!=0 or before['history_count']!=0 or before['prepared'] or before['object_id'] is not None or before['error'] is not None:raise ValueError('Only fresh unprepared accepted proof job allowed')
    pinned=prepare_artifact(root);verified_artifact(root,pinned)
    state={'started_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'job_id':job,'artifact_sha256':pinned.stem,'before':before,'completed':False,'worker_identity_verified':False,'exit_code':None,'failure':None}
    fd=os.open(journal,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:json.dump(state,f,indent=2);f.write('\n')
    def save():journal.write_text(json.dumps(state,indent=2)+'\n')
    process=None;start_ticks=None
    try:
        with (local/'logs/export-page-e2e-worker-20261004.log').open('xb') as log:
            process=subprocess.Popen([str(java),'-Xms64m','-Xmx256m','-jar',str(pinned),'--prepare-one-without-publication',job],cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+20
            while time.monotonic()<deadline:
                if process.poll() is not None:raise RuntimeError('Worker exited before ownership verification')
                if is_owned_export_worker(process.pid,os.getpid(),root,artifact=pinned,job_id=job):
                    stat=(Path('/proc')/str(process.pid)/'stat').read_text();start_ticks=stat[stat.rfind(')')+2:].split()[19];state.update(worker_identity_verified=True,pid=process.pid,start_ticks=start_ticks);save();break
                time.sleep(.02)
            if start_ticks is None:raise RuntimeError('Worker ownership unverified')
            state['exit_code']=process.wait(timeout=90)
        if state['exit_code']!=0:raise RuntimeError('Worker preparation did not succeed')
        lines=[json.loads(x) for x in (local/'logs/export-page-e2e-worker-20261004.log').read_text().splitlines() if x.startswith('{')]
        if len(lines)!=1 or lines[0].get('status')!='PREPARED_STORAGE_NOT_ATTEMPTED' or lines[0].get('published') is not False or any(lines[0].get(k)!=0 for k in ('store_requests','download_grants','notifications_sent')):raise ValueError('Unexpected worker result')
        after=query(True);raw=decode_file(after.pop('encoded_file'))
        if after['state']!='RUNNING' or after['version']!=2 or after['attempts']!=1 or after['history_count']!=1 or not after['prepared'] or after['object_id'] is not None or after['error'] is not None:raise ValueError('Unexpected preparation progression')
        verified=workbook(raw,after)
        if lines[0]['file_sha256']!=after['file_sha256'] or lines[0]['file_size']!=after['file_size']:raise ValueError('Worker and database descriptors differ')
        state.update(completed=True,finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),after=after,workbook=verified,result=lines[0],worker_process_ended=process.poll() is not None)
    except Exception as error:
        state['failure']={'type':type(error).__name__,'details':'Original job retained; do not repeat automatically'}
        raise
    finally:
        if process is not None and process.poll() is None and start_ticks is not None and is_owned_export_worker(process.pid,os.getpid(),root,artifact=pinned,start_ticks=start_ticks,job_id=job):
            os.kill(process.pid,signal.SIGTERM)
            try:process.wait(timeout=30)
            except subprocess.TimeoutExpired:state['cleanup']='Owned worker did not exit after TERM; further action required'
        save()
    print(json.dumps({'completed':True,'artifact_sha256':pinned.stem,'worker_identity_verified':True,'worker_exit_code':0,'worker_process_ended':True,'result':state['result'],'workbook':state['workbook']}))

def verify_existing():
    root=Path.cwd();local=root/'.local/niko-test';output=local/'export-page-e2e-recovery.json'
    if output.exists() or output.is_symlink():raise ValueError('Existing readonly recovery proof; do not overwrite')
    control=json.loads((local/'export-page-e2e-control.json').read_text());job,scope=target(control)
    journal_path=local/'export-page-e2e-worker-control.json';journal=json.loads(journal_path.read_text())
    if journal.get('job_id')!=job or journal.get('completed') is not False or journal.get('worker_identity_verified') is not True or journal.get('exit_code')!=0 or journal.get('failure',{}).get('type')!='Error':raise ValueError('Expected retained successful worker with post-read base64 failure')
    settings=json.loads((local/'private/runtime.json').read_text())['environment'];private_environment(settings,{})
    row=inspect_job(root,job,scope,settings,True);raw=decode_file(row.pop('encoded_file'))
    if row['state']!='RUNNING' or row['version']!=2 or row['attempts']!=1 or row['history_count']!=1 or row['object_id'] is not None or row['error'] is not None:raise ValueError('Original prepared state changed')
    checked=workbook(raw,row)
    logfile=local/'logs/export-page-e2e-worker-20261004.log';results=[json.loads(line) for line in logfile.read_text().splitlines() if line.startswith('{')]
    if len(results)!=1:raise ValueError('Expected one original worker result')
    result=results[0]
    if result.get('job_id')!=job or result.get('status')!='PREPARED_STORAGE_NOT_ATTEMPTED' or result.get('file_sha256')!=row['file_sha256'] or result.get('file_size')!=row['file_size'] or result.get('published') is not False or any(result.get(k)!=0 for k in ('store_requests','download_grants','notifications_sent')):raise ValueError('Original worker result mismatch')
    evidence={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'completed':True,'read_only_recovery':True,'new_workers_started':0,'original_worker_exit_code':0,'original_worker_identity_verified':True,'original_journal_sha256':hashlib.sha256(journal_path.read_bytes()).hexdigest(),'original_worker_log_sha256':hashlib.sha256(logfile.read_bytes()).hexdigest(),'initial_decoder_source_sha256':hashlib.sha256((local/'prepare-accepted-export-proof-initial.py').read_bytes()).hexdigest(),'artifact_sha256':journal['artifact_sha256'],'retained_progress':row,'workbook':checked,'result':result}
    fd=os.open(output,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:json.dump(evidence,f,indent=2);f.write('\n')
    print(json.dumps(evidence))

if __name__=='__main__':
    try:
        if sys.argv[1:]==['--verify-existing-page-proof']:verify_existing()
        else:main()
    except Exception as error:
        print(json.dumps({'completed':False,'exception_type':type(error).__name__,'details':'redacted; retain original proof control'}));raise SystemExit(1)

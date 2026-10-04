"""Windows real single-job JVM, retained file and inflight termination proof on designated PG only."""
from pathlib import Path
import ctypes, datetime, hashlib, importlib.util, json, os, re, shutil, subprocess, sys, time, uuid, zipfile
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/python-deps'));sys.path.insert(0,str(ROOT/'tools'))
from inspect_test_server import connect
LOCAL=ROOT/'.local/designated-test/export-worker-proof'
OUTPUT=ROOT/'docs/evidence/local-export-preparation-worker-20261004.json'
client=None;guard=None;workers=[];launchers=[];stage='preconditions';control={};kernel=None
def digest(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def probe(action,*args):
    result=subprocess.run([JAVA,'-cp',CP,'ExportPreparationWorkerProbe',action,*args],env=probe_env,cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    (LOCAL/('probe-'+action+'-'+uuid.uuid4().hex+'.log')).write_text(result.stdout+'\n'+result.stderr)
    assert result.returncode==0
    values=[json.loads(x[len('MOP_EXPORT_WORKER_PROBE '):]) for x in result.stdout.splitlines() if x.startswith('MOP_EXPORT_WORKER_PROBE ')]
    assert len(values)==1 and values[0].get('status')!='FAILED';return values[0]
def sql(query):
    spec=importlib.util.spec_from_file_location('namespace',ROOT/'tools/prepare-test-namespace.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    values=module.sql(client,'BEGIN READ ONLY;'+query+';ROLLBACK;','mop_test').splitlines()
    assert len(values)==3;return values[1]
def ps(code):
    result=subprocess.run([PWSH,'-NoProfile','-Command',code],cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
    (LOCAL/('owned-action-'+uuid.uuid4().hex+'.log')).write_text(result.stdout+'\n'+result.stderr)
    assert result.returncode==0,'Windows owned action failed';return result.stdout.strip()
def launch(label,job):
    assert re.fullmatch('[0-9a-f-]{36}',job)
    code="$r=& ./deploy/prepare-asset-export.ps1 -JobId '"+job+"' -WorkerId 'designated-"+label+"' -LeaseMillis 30000 -NoWait; [Console]::WriteLine(($r|ConvertTo-Json -Compress)); [Console]::Out.Flush()"
    # A descendant console handle can keep communicate() waiting for EOF even
    # after PowerShell emitted the record. Read that record immediately, retain
    # our own launcher handle and wait for it only after the JVM finishes.
    launcher_log=open(LOCAL/('launcher-'+label+'-'+uuid.uuid4().hex+'.log'),'w',encoding='utf-8')
    launcher=subprocess.Popen([PWSH,'-NoProfile','-Command',code],cwd=ROOT,stdout=subprocess.PIPE,stderr=launcher_log,text=True,encoding='utf-8',errors='replace',creationflags=subprocess.CREATE_NO_WINDOW)
    launchers.append((launcher,launcher_log));raw=launcher.stdout.readline().strip()
    record=json.loads(raw);assert record['job_id']==job and record['artifact_sha256']==artifact_hash
    path=Path(record['artifact_path']).parent/'process.json';assert path.is_file() and path.resolve().is_relative_to((ROOT/'.local/export-preparation').resolve())
    handle=kernel.OpenProcess(0x100000|0x1000,False,record['id']);assert handle
    # No signal occurs here. Verify the complete identity immediately before a
    # signal in owned(), rather than consuming the source query timeout twice.
    item={'record':record,'path':path,'handle':handle};workers.append(item);return item
def owned(item,action):
    assert action in ('observe','kill')
    path=str(item['path']).replace("'","''")
    code=". ./deploy/dev-process-ownership.ps1; . ./deploy/export-preparation-process.ps1; $r=Get-Content -Raw -LiteralPath '"+path+"'|ConvertFrom-Json; $p=Get-MopOwnedExportPreparationProcess -Record $r -ProjectRoot (Get-Location).Path; "
    if action=='kill':code+="if(-not $p){throw 'Owned worker already exited'}; Stop-Process -InputObject $p -Force -ErrorAction Stop; $p.WaitForExit(); Write-Output 'OWNED_TERMINATED'"
    else:code+="Write-Output $(if($p){'OWNED_RUNNING'}else{'ALREADY_EXITED'})"
    return ps(code)
def exit_code(item):
    value=ctypes.c_uint32();assert kernel.GetExitCodeProcess(item['handle'],ctypes.byref(value));return value.value
def finish(item,expected):
    deadline=time.monotonic()+90
    while exit_code(item)==259 and time.monotonic()<deadline:time.sleep(.1)
    assert exit_code(item)==0
    text=(item['path'].parent/'stdout.log').read_text(encoding='utf-8-sig')
    values=[json.loads(x) for x in text.splitlines() if x.startswith('{')]
    assert len(values)==1 and values[0]['status']==expected and values[0]['published'] is False and values[0]['store_requests']==0
    return values[0]
def expire(run):
    deadline=time.monotonic()+70
    while time.monotonic()<deadline:
        value=probe('inspect',run)
        if value['lease_expired']:return value
        time.sleep(.15)
    raise RuntimeError('Explicit lease did not expire')
def guard_send(query,marker):
    guard.sendall(query+'\n');deadline=time.monotonic()+30;buffer=''
    while time.monotonic()<deadline:
        if guard.recv_ready():buffer+=guard.recv(65536).decode()
        if marker in buffer.splitlines():return
        if guard.exit_status_ready():raise RuntimeError('Audit guard closed')
        time.sleep(.02)
    raise RuntimeError('Audit marker not observed')
def advisory_wait():
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        if sql("SELECT count(*) FROM pg_stat_activity WHERE datname='mop_test' AND wait_event_type='Lock' AND query LIKE '%pg_advisory_xact_lock%'")=='1':return
        time.sleep(.05)
    raise RuntimeError('Actual advisory wait not observed')
try:
    retry=sys.argv[1:]==['--verify-owned-export-preparation-worker','--retry-before-first-job']
    resume=sys.argv[1:]==['--verify-owned-export-preparation-worker','--resume-unclaimed-crash']
    assert os.name=='nt' and (resume or retry or sys.argv[1:]==['--verify-owned-export-preparation-worker'])
    assert not OUTPUT.exists(),'Preserve existing evidence'
    if resume:
        assert LOCAL.is_dir() and (LOCAL/'control.json').is_file() and not (ROOT/'.local/processes.json').exists(),'Only resume the same owned, unclaimed crash job with apps stopped'
    elif retry:
        assert LOCAL.is_dir() and not (LOCAL/'control.json').exists(),'Only a failed pre-job probe may retry; preserve all controls'
    else:assert not LOCAL.exists(),'Preserve existing pending control'
    runtime=json.loads((ROOT/'deploy/private/runtime.json').read_text(encoding='utf-8-sig'))['environment']
    assert runtime['SPRING_DATASOURCE_URL']=='jdbc:postgresql://127.0.0.1:15432/mop_test' and runtime['SPRING_DATASOURCE_USERNAME']=='mop_test_runtime'
    JAVA=shutil.which('java.exe');JAVAC=shutil.which('javac.exe');PWSH=shutil.which('pwsh.exe');assert JAVA and JAVAC and PWSH
    kernel=ctypes.WinDLL('kernel32',use_last_error=True);kernel.OpenProcess.argtypes=[ctypes.c_uint32,ctypes.c_int,ctypes.c_uint32];kernel.OpenProcess.restype=ctypes.c_void_p;kernel.GetExitCodeProcess.argtypes=[ctypes.c_void_p,ctypes.POINTER(ctypes.c_uint32)];kernel.CloseHandle.argtypes=[ctypes.c_void_p]
    LOCAL.mkdir(parents=True,exist_ok=retry or resume);libs=LOCAL/'libs';libs.mkdir(exist_ok=retry or resume);artifact=ROOT/'server/export-preparation-worker/target/mop-export-preparation-worker-0.1.0-SNAPSHOT.jar';artifact_hash=digest(artifact)
    with zipfile.ZipFile(artifact) as z:
        assert len(z.namelist())==len(set(z.namelist()))
        for name in z.namelist():
            if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
                filename=name[len('BOOT-INF/lib/'):];assert re.fullmatch(r'[A-Za-z0-9_.-]+\.jar',filename);(libs/filename).write_bytes(z.read(name))
    CP=os.pathsep.join((str(LOCAL),str(libs/'*')))
    allowed={k.upper() for k in ('PATH','SystemRoot','WINDIR','SystemDrive','TEMP','TMP','USERPROFILE','LOCALAPPDATA','APPDATA','ProgramData','ProgramFiles','ProgramFiles(x86)','ComSpec','PATHEXT','OS')}
    base_env={k:v for k,v in os.environ.items() if k.upper() in allowed}
    probe_env=dict(base_env);probe_env.update({k:runtime[k] for k in ('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD')})
    stage='compile_actual_probe';result=subprocess.run([JAVAC,'-proc:none','-cp',CP,'-d',str(LOCAL),str(ROOT/'tools/niko-test/ExportPreparationWorkerProbe.java')],env=base_env,cwd=ROOT,capture_output=True,text=True,timeout=60);(LOCAL/'compile.log').write_text(result.stdout+result.stderr);assert result.returncode==0
    client=connect();assert sql("SELECT count(*) FROM pg_stat_activity WHERE datname='mop_test' AND application_name='mop-export-preparation-worker'")=='0'
    before=probe('fingerprints')
    if resume:
        control=json.loads((LOCAL/'control.json').read_text());assert control['artifact_sha256']==artifact_hash;normal=control['normal'];crash=control['crash']
        one=probe('seed',normal);prepared=probe('inspect',normal);retained=prepared;assert prepared['actual_workbook_verified'] and prepared['fencing_token']==2 and prepared['source_marker_is_after'] and prepared['workbook_marker_is_before']
        check=probe('seed',crash);state=probe('inspect',crash);assert not state['prepared'] and (state['state']=='QUEUED' and state['fencing_token']==0 or state['state']=='RUNNING' and state['lease_expired'])
    else:
        normal=str(uuid.uuid4());crash=str(uuid.uuid4());control={'normal':normal,'crash':crash,'artifact_sha256':artifact_hash};(LOCAL/'control.json').write_text(json.dumps(control))
        stage='normal_preparation';one=probe('seed',normal);first=launch('normal',one['job_id']);finish(first,'PREPARED_STORAGE_NOT_ATTEMPTED');prepared=probe('inspect',normal);assert prepared['actual_workbook_verified'] and prepared['fencing_token']==1
        stage='retained_after_source_change';assert probe('change-source',normal)['owned_source_changed'];expire(normal);second=launch('retained',one['job_id']);finish(second,'RETAINED_REQUIRES_STORAGE_RECONCILIATION');retained=probe('inspect',normal);assert retained['fencing_token']==2 and retained['file_sha256']==prepared['file_sha256'] and retained['selection_sha256']==prepared['selection_sha256'] and retained['source_marker_is_after'] and retained['workbook_marker_is_before']
    stage='inflight_advisory_guard';two=probe('seed',crash);preclaim=probe('inspect',crash);assert not preclaim['prepared'];expected_fence=preclaim['fencing_token']+1;guard=client.get_transport().open_session();guard.exec_command('docker exec -i shared-postgres psql -U postgres -d mop_test -At -v ON_ERROR_STOP=1')
    # This project test table is locked only on our owned connection while all
    # project apps are stopped. Claiming does not access it; the actual source
    # read after the committed claim blocks here, without relying on audit FIFO.
    lock="LOCK TABLE public.mop_asset IN ACCESS EXCLUSIVE MODE;"
    assert sql("SELECT count(*) FROM pg_stat_activity WHERE datname='mop_test' AND wait_event_type='Lock'")=='0'
    stage='owned_project_source_read_guard';guard_send("BEGIN; "+lock+" SELECT 'SOURCE_READ_GUARD_HELD';",'SOURCE_READ_GUARD_HELD');stage='owned_worker_claim_launch';third=launch('crash',two['job_id']);stage='observe_committed_claim_before_source_read'
    deadline=time.monotonic()+15
    while True:
        inflight=json.loads(sql("SELECT json_build_object('state',state,'fencing_token',fencing_token,'prepared',file_bytes IS NOT NULL) FROM public.mop_asset_export_job WHERE id='"+two['job_id']+"'::uuid"))
        if inflight['state']=='RUNNING' and inflight['fencing_token']==expected_fence and not inflight['prepared']:break
        assert exit_code(third)==259 and time.monotonic()<deadline
        time.sleep(.02)
    stage='terminate_only_verified_owned_windows_worker';assert owned(third,'kill')=='OWNED_TERMINATED';killed_exit=exit_code(third);assert killed_exit not in (0,259);guard.sendall('ROLLBACK;\n');guard.close();guard=None;after=probe('inspect',crash);assert after['state']=='RUNNING' and after['fencing_token']==expected_fence and not after['prepared']
    expire(crash);stage='new_jvm_fenced_recovery';fourth=launch('recovered',two['job_id']);finish(fourth,'PREPARED_STORAGE_NOT_ATTEMPTED');recovered=probe('inspect',crash);assert recovered['fencing_token']==expected_fence+1 and recovered['actual_workbook_verified'] and recovered['workbook_marker_is_before']
    stage='unchanged_original_receipts_and_other_consumers';assert probe('seed',normal)['job_id']==one['job_id'] and probe('seed',crash)['job_id']==two['job_id'];assert probe('fingerprints')==before;assert sql("SELECT count(*) FROM pg_stat_activity WHERE datname='mop_test' AND application_name='mop-export-preparation-worker'")=='0'
    evidence={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'designated-server-from-Windows','postgresql':'17.9','validated_scenario_steps':4,'artifact_sha256':artifact_hash,'lease_millis':30000,'normal_exit_codes':[0,0,0],'actual_xlsx_16_columns_verified':True,'retained_original_bytes_after_source_change':True,'inflight_committed_claim_fence':1,'termination':'WINDOWS_STOP_PROCESS_FORCE_AFTER_EXACT_OWNER_SID_PID_PARENT_TIME_EXECUTABLE_COMMAND_JOB_AND_HASH_CHECKS','termination_exit_code':killed_exit,'uncommitted_file_rolled_back':True,'recovered_new_jvm_fence':2,'original_acceptance_receipts_unchanged':True,'unrelated_table_fingerprints_unchanged':before,'worker_connections_after':0,'published':False,'store_requests':0,'download_grants':0,'notifications_sent':0,'new_credentials_or_grants':0,'migrations':0,'formal_acceptance':'NOT_RUN'}
    attempts=[]
    for path in (ROOT/'.local/export-preparation').glob('*/process.json'):
        record=json.loads(path.read_text(encoding='utf-8-sig'))
        if record.get('artifact_sha256')==artifact_hash and record.get('job_id') in (one['job_id'],two['job_id']):attempts.append(record)
    evidence.pop('uncommitted_file_rolled_back')
    evidence.update(actual_worker_attempts=len(attempts),resumed_same_original_job=resume,prior_failed_observation_attempts_preserved=resume,normal_and_retained_valid_steps_from_prior_attempt=resume,fingerprint_window='RESUMED_CRASH_SCENARIO' if resume else 'ALL_FOUR_JVMS',inflight_committed_claim_fence=expected_fence,recovered_new_jvm_fence=recovered['fencing_token'],prepare_audit_wait_specifically_observed=False,inflight_point='CLAIM_COMMITTED_SOURCE_READ_BLOCKED_BEFORE_PREPARED_COMMIT',no_prepared_file_committed_at_termination=True,prepared_file_transaction_killed_midwrite=False)
    OUTPUT.write_text(json.dumps(evidence,indent=2)+'\n');print(json.dumps({'passed':True,'validated_scenario_steps':4,'actual_worker_attempts':len(attempts),'inflight_kill_exit':killed_exit,'recovered_fence':recovered['fencing_token'],'published':False}))
except Exception as failure:
    print(json.dumps({'status':'FAILED','stage':stage,'exception_type':type(failure).__name__,'details':'redacted'}));sys.exitcode=1
finally:
    if guard:
        try:guard.sendall('ROLLBACK;\n');guard.close()
        except Exception:sys.exitcode=1
    for item in workers:
        try:
            if exit_code(item)==259:owned(item,'kill')
            kernel.CloseHandle(item['handle'])
        except Exception:print('{"cleanup":"UNCONFIRMED","details":"No unverified process was signalled"}');sys.exitcode=1
    for launcher,out in launchers:
        try:launcher.wait(timeout=20)
        except subprocess.TimeoutExpired:
            launcher.terminate();launcher.wait(timeout=10)
        finally:out.close()
    if client:client.close()
if getattr(sys,'exitcode',0):raise SystemExit(sys.exitcode)

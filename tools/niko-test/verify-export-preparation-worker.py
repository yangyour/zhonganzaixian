"""Owned real JVM preparation, committed-byte recovery, and inflight audit-lock kill/recovery. No publication."""
from pathlib import Path
import datetime,hashlib,json,os,re,selectors,signal,subprocess,sys,time,uuid,zipfile
from export_worker_artifact import prepare_artifact,digest,verified_artifact
from owned_export_worker import is_owned_export_worker
ROOT=Path.cwd();LOCAL=ROOT/'.local/niko-test';CONTROL=LOCAL/'export-preparation-worker-proof-control.json';EVIDENCE=ROOT/'docs/evidence/niko-export-preparation-worker-process-20261004.json'
workers=[];logs=[];guard=None;stage='initialization'
def private_new(path,value):
 fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
 with os.fdopen(fd,'w') as out:json.dump(value,out,indent=2);out.write('\n')
def probe(action,*args):
 result=subprocess.run([str(JAVA),'-cp',CP,'ExportPreparationWorkerProbe',action,*args],env=PROBE_ENV,cwd=ROOT,capture_output=True,text=True,timeout=60)
 if result.returncode:raise RuntimeError('Owned export probe failed; private diagnostics withheld')
 values=[json.loads(line[len('MOP_EXPORT_WORKER_PROBE '):]) for line in result.stdout.splitlines() if line.startswith('MOP_EXPORT_WORKER_PROBE ')]
 if len(values)!=1 or values[0].get('status')=='FAILED':raise RuntimeError('Unexpected probe result')
 return values[0]
def psql(sql):
 result=subprocess.run(PSQL,input='BEGIN READ ONLY;'+sql+';ROLLBACK;',env=PG_ENV,text=True,capture_output=True,timeout=20)
 if result.returncode:raise RuntimeError('Readonly project query failed; details withheld')
 values=[line for line in result.stdout.splitlines() if line not in ('BEGIN','ROLLBACK') and line]
 if len(values)!=1:raise RuntimeError('Unexpected query result')
 return values[0]
def ticks(pid):
 value=(Path('/proc')/str(pid)/'stat').read_text();return value[value.rfind(')')+2:].split()[19]
def launch(label,job):
 env=PROBE_ENV.copy();env.update(SPRING_PROFILES_ACTIVE='remote_test',MOP_EXPORT_PREPARATION_WORKER_ENABLED='true',MOP_EXPORT_PREPARATION_WORKER_ID='niko-export-'+label,MOP_EXPORT_PREPARATION_LEASE_MILLIS='30000')
 for name in ('MOP_PHOTO_WORKER_ENABLED','MOP_IMPORT_WORKER_ENABLED','MOP_ASSET_IMPORT_WORKER_ENABLED','MOP_CONTACT_IMPORT_WORKER_ENABLED'):env[name]='false'
 logfile=LOCAL/'logs'/('export-worker-'+label+'-20261004.log');out=open(logfile,'xb');logs.append(out)
 process=subprocess.Popen([str(JAVA),'-Xms64m','-Xmx256m','-jar',str(PINNED),'--prepare-one-without-publication',job],env=env,cwd=ROOT,stdout=out,stderr=subprocess.STDOUT)
 item={'process':process,'job':job,'ticks':None,'log':logfile};workers.append(item);deadline=time.monotonic()+20
 while time.monotonic()<deadline:
  if process.poll() is not None:raise RuntimeError('Worker exited before ownership verification')
  if is_owned_export_worker(process.pid,os.getpid(),ROOT,artifact=PINNED,job_id=job):item['ticks']=ticks(process.pid);return item
  time.sleep(.02)
 raise RuntimeError('Worker identity not verified; no signal sent')
def stop(item,crash=False):
 process=item['process']
 if process.poll() is not None:return process.returncode
 if item['ticks'] is None or not is_owned_export_worker(process.pid,os.getpid(),ROOT,artifact=PINNED,start_ticks=item['ticks'],job_id=item['job']):raise RuntimeError('Worker identity changed; signal refused')
 os.kill(process.pid,signal.SIGKILL if crash else signal.SIGTERM);code=process.wait(timeout=30)
 if crash and code!=-9:raise RuntimeError('Unexpected owned crash exit')
 return code
def finish(item,status):
 code=item['process'].wait(timeout=90)
 if code!=0:raise RuntimeError('Owned worker did not finish preparation')
 values=[json.loads(line) for line in item['log'].read_text().splitlines() if line.startswith('{')]
 if len(values)!=1 or values[0].get('status')!=status or values[0].get('published') is not False or values[0].get('store_requests')!=0:raise RuntimeError('Unexpected worker phase result')
 return values[0]
def wait_expired(run):
 deadline=time.monotonic()+70
 while time.monotonic()<deadline:
  state=probe('inspect',run)
  if state['lease_expired']:return state
  time.sleep(.2)
 raise RuntimeError('Expected bounded lease did not expire')
def guard_marker(marker,timeout=20):
 selector=selectors.DefaultSelector();selector.register(guard.stdout,selectors.EVENT_READ);deadline=time.monotonic()+timeout;buffer=''
 try:
  while time.monotonic()<deadline:
   if guard.poll() is not None:raise RuntimeError('Owned guard connection ended')
   if selector.select(.1):
    chunk=os.read(guard.stdout.fileno(),4096)
    if not chunk:raise RuntimeError('Owned guard output ended')
    buffer+=chunk.decode('utf-8')
    if marker in [line.strip() for line in buffer.splitlines()]:return
 finally:selector.close()
 raise RuntimeError('Owned advisory marker not observed')
def hold_audit_guard():
 global guard
 guard=subprocess.Popen(PSQL,env=PG_ENV,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
 guard.stdin.write("BEGIN; SELECT pg_advisory_xact_lock(hashtextextended('audit|55000000-0000-4000-8000-000000000001',0)); SELECT 'EXPORT_GUARD_HELD';\n");guard.stdin.flush();guard_marker('EXPORT_GUARD_HELD')
def release_and_requeue_guard():
 # The observed worker is already queued on this exact advisory lock. Requeue behind its claim transaction.
 guard.stdin.write("COMMIT; BEGIN; SELECT pg_advisory_xact_lock(hashtextextended('audit|55000000-0000-4000-8000-000000000001',0)); SELECT 'EXPORT_GUARD_REACQUIRED';\n");guard.stdin.flush();guard_marker('EXPORT_GUARD_REACQUIRED')
def release_guard():
 global guard
 if guard is not None and guard.poll() is None:guard.stdin.write('ROLLBACK;\n');guard.stdin.close();guard.wait(timeout=15)
 guard=None
def wait_advisory_worker():
 deadline=time.monotonic()+15
 while time.monotonic()<deadline:
  if psql("SELECT count(*) FROM pg_stat_activity WHERE application_name='mop-export-preparation-worker' AND wait_event_type='Lock' AND query LIKE '%pg_advisory_xact_lock%'")=='1':return
  time.sleep(.03)
 raise RuntimeError('No actual worker advisory wait observed')
try:
 if sys.argv[1:]!=['--verify-owned-export-preparation-worker']:raise RuntimeError('Explicit owned preparation scenario required')
 if CONTROL.exists() or EVIDENCE.exists():raise RuntimeError('Preserve existing proof control and evidence; no repeat run started')
 runtime=json.loads((LOCAL/'private/runtime.json').read_text())['environment']
 if runtime['SPRING_DATASOURCE_URL']!='jdbc:postgresql://127.0.0.1:25432/mop_test' or runtime['SPRING_DATASOURCE_USERNAME']!='mop_test_runtime':raise RuntimeError('Unexpected approved test scope')
 JAVA=ROOT.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java';JAVAC=JAVA.with_name('javac')
 PROBE_ENV={'PATH':str(JAVA.parent)+':/usr/bin:/bin','JAVA_HOME':str(JAVA.parent.parent),'LANG':'C.UTF-8','TZ':'UTC'}
 for name in ('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD'):PROBE_ENV[name]=runtime[name]
 pg=ROOT.parent/'mop-toolchain/components/pg';PG_ENV={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','TZ':'UTC','PGPASSWORD':runtime['SPRING_DATASOURCE_PASSWORD'],'LD_LIBRARY_PATH':str(pg/'usr/lib/x86_64-linux-gnu')}
 PSQL=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-X','-q','-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
 stage='pin_dedicated_artifact';PINNED=prepare_artifact(ROOT);libs=LOCAL/'export-worker-probe-libs'/PINNED.stem;libs.mkdir(parents=True,exist_ok=True,mode=0o700)
 if libs!=libs.resolve():raise RuntimeError('Probe library symlink rejected')
 expected_libraries=set()
 with zipfile.ZipFile(PINNED) as archive:
  for name in archive.namelist():
   if name.startswith('BOOT-INF/lib/') and name.endswith('.jar'):
    filename=name[len('BOOT-INF/lib/'):]
    if not re.fullmatch(r'[A-Za-z0-9_.-]+\.jar',filename):raise RuntimeError('Unexpected nested dependency name')
    expected_libraries.add(filename);target=libs/filename;value=archive.read(name)
    if target.exists():
     if target.is_symlink() or target.read_bytes()!=value:raise RuntimeError('Probe library differs from pinned artifact')
    else:
     fd=os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o400)
     with os.fdopen(fd,'wb') as out:out.write(value)
 if {p.name for p in libs.iterdir()}!=expected_libraries:raise RuntimeError('Unexpected additional probe dependencies')
 CP=os.pathsep.join([str(LOCAL),str(libs/'*')]);stage='compile_owned_probe'
 compiled=subprocess.run([str(JAVAC),'-proc:none','-cp',CP,'-d',str(LOCAL),str(ROOT/'tools/niko-test/ExportPreparationWorkerProbe.java')],env={k:v for k,v in PROBE_ENV.items() if k in ('PATH','JAVA_HOME','LANG','TZ')},capture_output=True,text=True,timeout=45)
 if compiled.returncode:raise RuntimeError('Owned probe compile failed; no test data or worker started')
 stage='isolated_worker_preconditions'
 if psql("SELECT count(*) FROM pg_stat_activity WHERE application_name='mop-export-preparation-worker'")!='0':raise RuntimeError('Another export preparation worker is active')
 before=probe('fingerprints');normal=str(uuid.uuid4());crash=str(uuid.uuid4());private_new(CONTROL,{'normal_run':normal,'crash_run':crash,'artifact':str(PINNED),'sha256':digest(PINNED),'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat()})
 stage='normal_seed';normal_job=probe('seed',normal);assert normal_job['accepted_state']=='QUEUED'
 stage='normal_actual_worker';first=launch('normal',normal_job['job_id']);normal_result=finish(first,'PREPARED_STORAGE_NOT_ATTEMPTED');normal_prepared=probe('inspect',normal);assert normal_prepared['prepared'] and normal_prepared['actual_workbook_verified'] and normal_prepared['workbook_marker_is_before'] and normal_prepared['fencing_token']==1
 stage='change_only_owned_source';assert probe('change-source',normal)['owned_source_changed'];changed=probe('inspect',normal);assert changed['source_marker_is_after'] and changed['workbook_marker_is_before'];wait_expired(normal)
 stage='new_jvm_recovers_original_bytes';second=launch('retained',normal_job['job_id']);retained_result=finish(second,'RETAINED_REQUIRES_STORAGE_RECONCILIATION');retained=probe('inspect',normal);assert retained['fencing_token']==2 and retained['attempts']==2 and retained['file_sha256']==normal_prepared['file_sha256'] and retained['selection_sha256']==normal_prepared['selection_sha256'] and retained['workbook_marker_is_before'] and retained['source_marker_is_after']
 stage='crash_seed';crash_job=probe('seed',crash)
 stage='hold_owned_audit_guard';hold_audit_guard();third=launch('crash',crash_job['job_id']);wait_advisory_worker();pending=probe('inspect',crash);assert pending['state']=='QUEUED' and not pending['prepared']
 stage='requeue_guard_after_claim_commit';release_and_requeue_guard();wait_advisory_worker();inflight=probe('inspect',crash);assert inflight['state']=='RUNNING' and inflight['fencing_token']==1 and inflight['attempts']==1 and not inflight['prepared']
 stage='kill_verified_owned_worker_during_prepare_audit';crash_exit=stop(third,True);after_kill=probe('inspect',crash);assert crash_exit==-9 and after_kill['state']=='RUNNING' and after_kill['fencing_token']==1 and not after_kill['prepared'];release_guard();wait_expired(crash)
 stage='new_jvm_recovers_uncommitted_preparation';fourth=launch('recovered',crash_job['job_id']);recovered_result=finish(fourth,'PREPARED_STORAGE_NOT_ATTEMPTED');recovered=probe('inspect',crash);assert recovered['fencing_token']==2 and recovered['attempts']==2 and recovered['prepared'] and recovered['actual_workbook_verified'] and recovered['workbook_marker_is_before']
 stage='original_receipts_preserved';assert probe('seed',normal)['job_id']==normal_job['job_id'];assert probe('seed',crash)['job_id']==crash_job['job_id'];assert probe('fingerprints')==before
 if psql("SELECT count(*) FROM pg_stat_activity WHERE application_name='mop-export-preparation-worker'")!='0':raise RuntimeError('An owned worker connection remains active')
 evidence={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'niko-local-test','scope':'ACTUAL_SINGLE_JOB_PREPARATION_JVMS_NORMAL_RETAINED_AND_INFLIGHT_KILL_RECOVERY','artifact_sha256':digest(PINNED),'start_class':'cn.mop.exportworker.ExportPreparationWorkerApplication','actual_worker_runs':4,'lease_millis':30000,'owned_process_checks':['uid','direct_parent','exact_java_argv_and_target_job','exe_path','start_ticks','pinned_jar_sha256_and_manifest'],'normal':{'fence':1,'actual_workbook_verified':True,'data_rows':normal_prepared['data_rows'],'file_sha256':normal_prepared['file_sha256'],'exit_code':0},'retained':{'new_jvm_fence':2,'owned_source_changed_before_restart':True,'original_file_sha256_unchanged':True,'original_selection_sha256_unchanged':True,'original_workbook_marker_retained':True,'state':retained['state'],'exit_code':0},'crash':{'actual_pg_prepare_audit_wait_observed':True,'signal':'SIGKILL','exit_code':crash_exit,'committed_claim_retained':True,'partially_committed_prepared_file':False,'recovered_new_jvm_fence':2,'recovered_actual_workbook_verified':True,'recovery_exit_code':0},'synthetic_source_assets_created':2,'original_acceptance_receipts_stay_queued_version_zero':True,'unrelated_table_fingerprints_unchanged':before,'worker_connections_after_proof':0,'new_credentials_or_grants':0,'schema_migrations':0,'api_or_pg_restarted':False,'export_http_tested':False,'oss_requests':0,'download_grants':0,'notifications_sent':0,'published':False,'formal_acceptance':'NOT_RUN'}
 with EVIDENCE.open('x') as out:json.dump(evidence,out,indent=2);out.write('\n')
 print(json.dumps({'passed':True,'actual_worker_runs':4,'inflight_kill_exit':crash_exit,'recovered_fence':2,'retained_bytes_after_source_change':True,'published':False}))
except Exception as failure:
 print(json.dumps({'status':'FAILED','stage':stage,'exception_type':type(failure).__name__,'details':'redacted'}));sys.exitcode=1
finally:
 failed=False
 try:release_guard()
 except Exception:failed=True
 for item in workers:
  try:stop(item)
  except Exception:failed=True
 for out in logs:out.close()
 if failed:print(json.dumps({'cleanup':'UNCONFIRMED','details':'No unrelated or unverified process was signalled'}));sys.exitcode=1
if getattr(sys,'exitcode',0):raise SystemExit(sys.exitcode)

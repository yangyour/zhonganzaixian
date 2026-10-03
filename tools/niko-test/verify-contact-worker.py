"""Real owned worker process proof. Fixed loopback test scope; no credential creation, migration, storage or unrelated signals."""
from pathlib import Path
import datetime,json,os,selectors,signal,subprocess,sys,time,uuid
from contact_worker_artifact import prepare_artifact,digest
from owned_contact_worker import is_owned_contact_worker
ROOT=Path.cwd();LOCAL=ROOT/'.local/niko-test';EVIDENCE=ROOT/'docs/evidence/niko-contact-import-worker-process-20261003.json';CONTROL=LOCAL/'contact-worker-proof-control.json'
stage='initialization';workers=[];lock=None;logfiles=[]
def private_new(path,value):
 fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
 with os.fdopen(fd,'w') as out:json.dump(value,out,indent=2);out.write('\n')
def start_ticks(pid):
 value=(Path('/proc')/str(pid)/'stat').read_text();return value[value.rfind(')')+2:].split()[19]
def run_probe(action,*args):
 result=subprocess.run([str(JAVA),'-cp',CP,'ContactImportWorkerProbe',action,*args],env=PROBE_ENV,cwd=ROOT,capture_output=True,text=True,timeout=40)
 if result.returncode:raise RuntimeError('Owned probe failed; details withheld')
 rows=[json.loads(line[len('MOP_CONTACT_WORKER_PROBE '):]) for line in result.stdout.splitlines() if line.startswith('MOP_CONTACT_WORKER_PROBE ')]
 if len(rows)!=1:raise RuntimeError('Probe did not return exactly one structured result')
 return rows[0]
def psql_value(sql):
 result=subprocess.run(PSQL,input='BEGIN READ ONLY;'+sql+';ROLLBACK;',text=True,env=PG_ENV,capture_output=True,timeout=15)
 if result.returncode:raise RuntimeError('Read-only test query failed; details withheld')
 lines=[line for line in result.stdout.splitlines() if line not in ('BEGIN','ROLLBACK') and line]
 if len(lines)!=1:raise RuntimeError('Unexpected test query shape')
 return lines[0]
def launch(label):
 log=open(LOCAL/'logs'/('contact-worker-'+label+'.log'),'xb');logfiles.append(log)
 process=subprocess.Popen([sys.executable,'tools/niko-test/start-contact-worker.py',str(PINNED),'3000'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
 item={'process':process,'ticks':None,'label':label};workers.append(item)
 deadline=time.monotonic()+30
 while time.monotonic()<deadline:
  if process.poll() is not None:raise RuntimeError('Owned worker exited during startup')
  if is_owned_contact_worker(process.pid,os.getpid(),ROOT,artifact=PINNED):item['ticks']=start_ticks(process.pid);return item
  time.sleep(.05)
 raise RuntimeError('Worker identity was not verified; no signal sent')
def stop(item,crash=False):
 process=item['process']
 if process.poll() is not None:return process.returncode
 if item['ticks'] is None or not is_owned_contact_worker(process.pid,os.getpid(),ROOT,artifact=PINNED,start_ticks=item['ticks']):raise RuntimeError('Worker identity changed; signal refused')
 os.kill(process.pid,signal.SIGKILL if crash else signal.SIGTERM)
 code=process.wait(timeout=45)
 if crash and code!=-9:raise RuntimeError('Unexpected crash exit')
 if not crash and code not in (0,143,-15):raise RuntimeError('Unexpected graceful worker exit')
 return code
def wait_job(job,run,state,timeout=60):
 deadline=time.monotonic()+timeout
 while time.monotonic()<deadline:
  result=run_probe('inspect',job,run)
  if result['state']==state:return result
  if result['state']=='FAILED':raise RuntimeError('Owned test job failed rather than completing')
  time.sleep(.1)
 raise RuntimeError('Owned job did not reach requested state')
def hold_guard():
 global lock
 lock=subprocess.Popen(PSQL,env=PG_ENV,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
 lock.stdin.write("BEGIN; SELECT 'MOP_GUARD_HELD' FROM public.mop_contact_tenant_guard WHERE tenant_id='55000000-0000-4000-8000-000000000002' FOR UPDATE;\n");lock.stdin.flush()
 selector=selectors.DefaultSelector();selector.register(lock.stdout,selectors.EVENT_READ);deadline=time.monotonic()+15
 try:
  while time.monotonic()<deadline:
   if lock.poll() is not None:raise RuntimeError('Owned guard connection ended')
   if selector.select(.2):
    line=lock.stdout.readline().strip()
    if line=='MOP_GUARD_HELD':return
 finally:selector.close()
 raise RuntimeError('Controlled tenant guard was not acquired')
def release_guard():
 global lock
 if lock is None:return
 if lock.poll() is None:
  lock.stdin.write('ROLLBACK;\n');lock.stdin.close();lock.wait(timeout=10)
 lock=None
try:
 if sys.argv[1:]!=['--verify-owned-contact-worker']:raise RuntimeError('Explicit owned contact-worker scenario required')
 if EVIDENCE.exists() or CONTROL.exists():raise RuntimeError('Preserve existing evidence/control; no repeat run started')
 runtime=json.loads((LOCAL/'private/runtime.json').read_text())['environment']
 if runtime['SPRING_DATASOURCE_URL']!='jdbc:postgresql://127.0.0.1:25432/mop_test' or runtime['SPRING_DATASOURCE_USERNAME']!='mop_test_runtime':raise RuntimeError('Unexpected approved test scope')
 JAVA=ROOT.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java';JAVAC=JAVA.with_name('javac');CP=os.pathsep.join([str(LOCAL),str(ROOT/'server/domain/target/classes'),str(ROOT/'server/persistence/target/classes'),str(LOCAL/'java-libs/*')])
 PROBE_ENV={'PATH':str(JAVA.parent)+':/usr/bin:/bin','JAVA_HOME':str(JAVA.parent.parent),'LANG':'C.UTF-8','TZ':'UTC'}
 for key in ('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD'):PROBE_ENV[key]=runtime[key]
 pg=ROOT.parent/'mop-toolchain/components/pg';PG_ENV={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','TZ':'UTC','PGPASSWORD':runtime['SPRING_DATASOURCE_PASSWORD'],'LD_LIBRARY_PATH':str(pg/'usr/lib/x86_64-linux-gnu')}
 PSQL=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-X','-q','-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
 stage='compile_owned_probe';compiled=subprocess.run([str(JAVAC),'-cp',CP,'-d',str(LOCAL),str(ROOT/'tools/niko-test/ContactImportWorkerProbe.java')],env=PROBE_ENV,capture_output=True,text=True,timeout=40)
 if compiled.returncode:raise RuntimeError('Probe compilation failed; no worker started')
 stage='isolated_queue_preconditions'
 if psql_value("SELECT count(*) FROM public.mop_contact_import_job WHERE state IN ('PENDING','LEASED')")!='0':raise RuntimeError('Unrelated pending jobs exist; do not claim them')
 if psql_value("SELECT count(*) FROM pg_stat_activity WHERE application_name='mop-contact-import-worker'")!='0':raise RuntimeError('Another contact worker is active; no process will be stopped')
 before=run_probe('fingerprints');PINNED=prepare_artifact(ROOT);normal_run=str(uuid.uuid4());crash_run=str(uuid.uuid4());private_new(CONTROL,{'normal_run':normal_run,'crash_run':crash_run,'artifact':str(PINNED),'sha256':digest(PINNED),'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat()})
 stage='seed_normal';normal=run_probe('seed',normal_run);pending=run_probe('inspect',normal['job_id'],normal_run);assert pending['state']=='PENDING' and pending['matching_created_contacts']==0
 stage='normal_owned_worker';worker=launch('normal');normal_done=wait_job(normal['job_id'],normal_run,'SUCCEEDED');assert normal_done['attempts']==1 and normal_done['result_count']==3 and normal_done['input_sha256']==pending['input_sha256'];normal_exit=stop(worker)
 stage='seed_crash';crash=run_probe('seed',crash_run);crash_pending=run_probe('inspect',crash['job_id'],crash_run)
 stage='controlled_real_guard_lock';hold_guard();worker=launch('crash');claimed=wait_job(crash['job_id'],crash_run,'LEASED');assert claimed['fencing_token']==1 and claimed['matching_created_contacts']==0 and claimed['result_count']==0
 stage='verify_worker_actually_waiting_on_pg_lock';deadline=time.monotonic()+5
 while psql_value("SELECT count(*) FROM pg_stat_activity WHERE application_name='mop-contact-import-worker' AND wait_event_type='Lock'")=='0':
  if time.monotonic()>deadline:raise RuntimeError('No actual worker lock wait observed')
  time.sleep(.05)
 stage='kill_only_verified_owned_worker';crash_exit=stop(worker,True);after_kill=run_probe('inspect',crash['job_id'],crash_run);assert after_kill['state']=='LEASED' and after_kill['fencing_token']==1 and after_kill['matching_created_contacts']==0 and after_kill['result_count']==0;release_guard()
 stage='restart_same_pinned_worker';restarted=launch('restarted');recovered=wait_job(crash['job_id'],crash_run,'SUCCEEDED');assert recovered['fencing_token']==2 and recovered['attempts']==2 and recovered['result_count']==3 and recovered['matching_created_contacts']==3 and recovered['input_sha256']==crash_pending['input_sha256'];restart_exit=stop(restarted)
 stage='original_acceptance_replay';replay=run_probe('seed',crash_run);assert replay['job_id']==crash['job_id'] and replay['accepted_state']=='PENDING' and replay['accepted_version']==1
 stage='archive_only_owned_synthetic_contacts';cleanup_normal=run_probe('cleanup',normal['job_id'],normal_run);cleanup_crash=run_probe('cleanup',crash['job_id'],crash_run);assert cleanup_normal['owned_contacts_archived']==cleanup_crash['owned_contacts_archived']==3
 stage='independent_consumers_and_identities_unchanged';assert run_probe('fingerprints')==before;assert psql_value("SELECT count(*) FROM public.mop_contact_import_job WHERE state IN ('PENDING','LEASED')")=='0'
 evidence={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'niko-local-test','environment_instance':'rebuilt-20261003-after-executor-loss','scope':'ACTUAL_CONTACT_IMPORT_WORKER_PROCESS_NORMAL_AND_INFLIGHT_KILL_RESTART','worker_artifact_sha256':digest(PINNED),'start_class':'cn.mop.contactworker.ContactImportWorkerApplication','owned_process_identity_checks':['uid','direct_parent','exact_java_argv','exe_path','start_ticks','immutable_pinned_jar_hash_and_manifest'],'lease_millis':3000,'normal':{'attempts':normal_done['attempts'],'rows':3,'graceful_exit':normal_exit},'crash':{'real_pg_guard_lock_wait_observed':True,'signal':'SIGKILL','exit_code':crash_exit,'retained_state':'LEASED','partial_contact_rows':0,'partial_result_rows':0,'recovered_fencing_token':recovered['fencing_token'],'recovered_attempts':recovered['attempts'],'completed_rows':3,'graceful_restart_exit':restart_exit},'source_hash_preserved':True,'original_acceptance_receipt_stays_pending_version_one':True,'created_synthetic_contacts':6,'owned_contacts_archived':6,'jobs_retained_terminal':True,'unrelated_fingerprints_unchanged':before,'new_credentials_or_identity_grants':False,'storage_used':False,'notification_sent':False,'schema_migrations_executed':0,'api_restarted':False,'public_202_http_tested':False,'browser_dom_tested':False,'credentials_output':False,'contract_done':False}
 with EVIDENCE.open('x') as out:json.dump(evidence,out,indent=2);out.write('\n')
 print(json.dumps({'passed':True,'actual_worker_runs':3,'crash_exit':crash_exit,'recovered_fence':recovered['fencing_token'],'contacts_created':6,'contacts_archived':6}))
except Exception as failure:
 print(json.dumps({'status':'FAILED','stage':stage,'exception_type':type(failure).__name__,'details':'redacted'}));sys.exitcode=1
finally:
 cleanup_failed=False
 try:release_guard()
 except Exception:cleanup_failed=True
 for item in workers:
  try:stop(item)
  except Exception:cleanup_failed=True
 for log in logfiles:log.close()
 if cleanup_failed:print(json.dumps({'cleanup':'UNCONFIRMED','details':'No unverified or unrelated process was signalled'}));sys.exitcode=1
if getattr(sys,'exitcode',0):raise SystemExit(sys.exitcode)

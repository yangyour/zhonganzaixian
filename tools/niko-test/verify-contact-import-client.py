"""Explicit compiled frontend integration with one owned worker. Reuses approved isolated components, no setup/migration."""
from pathlib import Path
import datetime,json,os,signal,subprocess,sys,time
from contact_worker_artifact import prepare_artifact,digest
from owned_contact_worker import is_owned_contact_worker
ROOT=Path.cwd();LOCAL=ROOT/'.local/niko-test';process=None;log=None;ticks=None;stage='initialization'
def execute(phase):
 result=subprocess.run(['node','tests/niko-contact-import-client-http.mjs',phase],cwd=ROOT,capture_output=True,text=True,timeout=240)
 for line in result.stdout.splitlines():print(line,flush=True)
 if result.returncode:
  for line in result.stderr.splitlines():
   if line.startswith('{"status":"failed"'):print(line,flush=True)
  raise RuntimeError('HTTP phase did not pass; preserve private control and logs')
def probe():
 result=subprocess.run([str(JAVA),'-cp',CP,'ContactImportWorkerProbe','fingerprints'],env=ENV,cwd=ROOT,capture_output=True,text=True,timeout=30)
 if result.returncode:raise RuntimeError('Read-only fingerprint probe failed')
 values=[json.loads(line[len('MOP_CONTACT_WORKER_PROBE '):]) for line in result.stdout.splitlines() if line.startswith('MOP_CONTACT_WORKER_PROBE ')]
 if len(values)!=1:raise RuntimeError('Invalid fingerprint shape')
 return values[0]
def stop():
 global process
 if process is None:return None
 if process.poll() is not None:return process.returncode
 if ticks is None or not is_owned_contact_worker(process.pid,os.getpid(),ROOT,artifact=PINNED,start_ticks=ticks):raise RuntimeError('Owned worker identity not verified; signal refused')
 os.kill(process.pid,signal.SIGTERM);code=process.wait(timeout=45)
 if code not in (0,143,-15):raise RuntimeError('Unexpected owned worker shutdown')
 return code
try:
 if sys.argv[1:]!=['--verify-contact-import-client']:raise RuntimeError('Explicit API scenario required')
 if (LOCAL/'contact-import-client-http-control.json').exists() or (ROOT/'docs/evidence/niko-contact-import-client-worker-20261003.json').exists():raise RuntimeError('Existing control/evidence must be preserved; no new scenario started')
 runtime=json.loads((LOCAL/'private/runtime.json').read_text())['environment']
 if runtime['SPRING_DATASOURCE_URL']!='jdbc:postgresql://127.0.0.1:25432/mop_test' or runtime['SPRING_DATASOURCE_USERNAME']!='mop_test_runtime':raise RuntimeError('Unexpected isolated test scope')
 JAVA=ROOT.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java';CP=os.pathsep.join([str(LOCAL),str(ROOT/'server/domain/target/classes'),str(ROOT/'server/persistence/target/classes'),str(LOCAL/'java-libs/*')]);ENV={'PATH':str(JAVA.parent)+':/usr/bin:/bin','TZ':'UTC','LANG':'C.UTF-8'}
 for key in ('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD'):ENV[key]=runtime[key]
 compiled=subprocess.run([str(JAVA.with_name('javac')),'-cp',CP,'-d',str(LOCAL),str(ROOT/'tools/niko-test/ContactImportWorkerProbe.java')],env=ENV,capture_output=True,timeout=30)
 if compiled.returncode:raise RuntimeError('Probe compilation failed')
 pg=ROOT.parent/'mop-toolchain/components/pg';pg_env={'PATH':'/usr/bin:/bin','PGPASSWORD':runtime['SPRING_DATASOURCE_PASSWORD'],'LD_LIBRARY_PATH':str(pg/'usr/lib/x86_64-linux-gnu')}
 checked=subprocess.run([str(pg/'usr/lib/postgresql/17/bin/psql'),'-X','-q','-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At','-c',"SELECT (SELECT count(*) FROM public.mop_contact_import_job WHERE state IN ('PENDING','LEASED'))::text||'|'||(SELECT count(*) FROM pg_stat_activity WHERE application_name='mop-contact-import-worker')::text"],env=pg_env,capture_output=True,text=True,timeout=15)
 if checked.returncode or checked.stdout.strip()!='0|0':raise RuntimeError('Unrelated pending jobs or active worker; no test started')
 before=probe();PINNED=prepare_artifact(ROOT)
 stage='launch_verified_owned_worker';log=open(LOCAL/'logs/contact-import-client-worker.log','xb');process=subprocess.Popen([sys.executable,'tools/niko-test/start-contact-worker.py',str(PINNED),'30000'],cwd=ROOT,stdout=log,stderr=subprocess.STDOUT)
 deadline=time.monotonic()+30
 while time.monotonic()<deadline:
  if process.poll() is not None:raise RuntimeError('Worker exited before ownership verification')
  if is_owned_contact_worker(process.pid,os.getpid(),ROOT,artifact=PINNED):
   raw=(Path('/proc')/str(process.pid)/'stat').read_text();ticks=raw[raw.rfind(')')+2:].split()[19];break
  time.sleep(.05)
 if ticks is None:raise RuntimeError('Owned worker identity unavailable')
 stage='public_results_and_owned_archival';execute('complete');code=stop()
 stage='unchanged_unrelated_tables';after=probe()
 if after!=before:raise RuntimeError('Unrelated table fingerprint changed')
 result={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'niko-local-test','environment_instance':'rebuilt-20261003-after-executor-loss','api_version':'0.32.0','worker_artifact_sha256':digest(PINNED),'actual_frontend_http_with_worker':True,'owned_worker_identity_verified':True,'lease_millis':30000,'worker_graceful_exit':code,'unrelated_table_fingerprints_unchanged':before,'schema_migrations':0,'new_credentials':False,'storage_used':False,'notifications_sent':False,'credentials_output':False,'contract_done':False}
 path=ROOT/'docs/evidence/niko-contact-import-client-worker-20261003.json'
 with path.open('x') as output:json.dump(result,output,indent=2);output.write('\n')
 print(json.dumps({'passed':True,'worker_stopped':True,'unrelated_tables_unchanged':True}),flush=True)
except Exception:
 print(json.dumps({'status':'failed','stage':stage,'details':'redacted'}),flush=True);sys.exit(1)
finally:
 try:stop()
 finally:
  if log:log.close()

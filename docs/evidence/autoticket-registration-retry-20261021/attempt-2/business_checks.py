import pathlib,subprocess,json,xml.etree.ElementTree as ET,hashlib,re,uuid
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 env={k:v for k,v in env.items() if not k.startswith('MOP_MIGRATION_')}
 env.update(MOP_RUN_AUTOTICKET_CAS_TESTS='true',MOP_RUN_MONITORING_EVENT_CENTER_TESTS='true',MOP_RUN_AUTOTICKET_RETRY_TESTS='true')
 classes=['PostgresAutoTicketCasIntegrationTest','PostgresMonitoringEventCenterIntegrationTest','PostgresAutoTicketRetryIntegrationTest']
 cp=str(root/'server/persistence/target/test-classes')+':'+pathlib.Path('/tmp/za-retry-harness/classpath.txt').read_text()
 args=[JAVA,'-jar','/tmp/za-java/junit.jar','execute','--disable-ansi-colors','--config','junit.jupiter.execution.parallel.enabled=false','--class-path',cp,'--reports-dir',str(evidence/'junit')]
 for name in classes:args+=['--select-class','cn.mop.persistence.'+name]
 files=[root/'server/persistence/src/test/java/cn/mop/persistence'/f'{name}.java' for name in classes]
 write('test-source-hashes.json',{str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
 with (evidence/'pg-tests.log').open('w') as log:r=subprocess.run(args,env=env,stdout=log,stderr=log,timeout=90)
 cases=[]
 for file in (evidence/'junit').glob('TEST-*.xml'):
  for case in ET.parse(file).getroot().findall('testcase'):
   if case.get('classname','').split('.')[-1] in classes:cases.append({'class':case.get('classname'),'name':case.get('name'),'passed':all(case.find(t) is None for t in ['failure','error','skipped'])})
 write('pg-tests.json',{'exit':r.returncode,'cases':cases,'expected':13})
 assert r.returncode==0 and len(cases)==13 and all(c['passed'] for c in cases),'PostgreSQL retry regression failed'
 print('PG_RETRY_TESTS_PASS count=13',flush=True)
 cp=pathlib.Path('/tmp/za-retry-harness/classpath.txt').read_text()
 seeded=subprocess.run([JAVA,'-cp',cp,'cn.mop.persistence.SeedAutoTicketRetry'],env=env,capture_output=True,text=True,timeout=30)
 write('seed-process.json',{'exit':seeded.returncode})
 assert seeded.returncode==0,'Synthetic worker seed failed'
 seed=json.loads(next(line.split('=',1)[1] for line in seeded.stdout.splitlines() if line.startswith('SEED_RESULT=')));write('seed.json',seed)
 for key in ['tenant_id','actor_id','event_id','attempt_id']:assert str(uuid.UUID(seed[key]))==seed[key]
 worker=root/'server/worker/target/mop-worker-0.1.0-SNAPSHOT.jar'
 manifest=json.loads(pathlib.Path('/tmp/za-retry-harness/snapshot.json').read_text());assert hashlib.sha256(worker.read_bytes()).hexdigest()==manifest['worker_jar_sha256']
 worker_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD'}}
 worker_env.update(SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTOTICKET_REGISTRATION_RETRY_ENABLED='true',MOP_AUTOTICKET_RETRY_SCOPE='REGISTRATION_ONLY',MOP_AUTOTICKET_RETRY_TENANT_ID=seed['tenant_id'],MOP_AUTOTICKET_RETRY_ACTOR_ID=seed['actor_id'],MOP_AUTOTICKET_RETRY_BATCH_SIZE='25',MOP_AUTOTICKET_RETRY_POLL_MILLIS='1000',MOP_AUTOTICKET_RETRY_MODE='ONCE')
 before=snapshot();due=int(sql("SELECT count(*) FROM public.mop_autoticket_attempt WHERE tenant_id='"+seed['tenant_id']+"'::uuid AND status='RETRY_SCHEDULED' AND next_retry_at<=statement_timestamp()"));assert 1<=due<=25
 processes=[]
 for index in [1,2]:
  r=subprocess.run([JAVA,'-jar',str(worker),'--autoticket-registration-retries'],env=worker_env,capture_output=True,text=True,timeout=35)
  match=re.search(r'Autoticket registration retry batch handled=(\d+)',r.stdout);handled=None if not match else int(match.group(1));processes.append({'run':index,'exit':r.returncode,'handled':handled})
  write('worker-processes.json',processes);assert r.returncode==0 and handled==(due if index==1 else 0),'Standalone ONCE worker result mismatch'
 after=snapshot();changed=sorted(t for t in before if before[t]!=after.get(t));allowed={'mop_ticket','mop_ticket_history','mop_operation','mop_outbox','mop_monitoring_event','mop_autoticket_attempt','mop_audit','mop_idempotency'};assert set(changed)<=allowed,changed
 state=json.loads(sql("SELECT row_to_json(x) FROM (SELECT a.status,a.attempt_count,a.ticket_id IS NOT NULL AS has_ticket,a.next_retry_at IS NULL AS no_next_retry,e.ticket_id=a.ticket_id AS same_ticket FROM public.mop_autoticket_attempt a JOIN public.mop_monitoring_event e ON e.tenant_id=a.tenant_id AND e.id=a.event_id WHERE a.id='"+seed['attempt_id']+"'::uuid) x"));assert state=={'status':'SUCCEEDED','attempt_count':2,'has_ticket':True,'no_next_retry':True,'same_ticket':True}
 unauthorized=worker_env.copy();unauthorized['MOP_AUTOTICKET_RETRY_ACTOR_ID']=str(uuid.UUID(hashlib.md5(b'mop-fixture-user-viewerA').hexdigest()));before_denied=snapshot()
 denied=subprocess.run([JAVA,'-jar',str(worker),'--autoticket-registration-retries'],env=unauthorized,capture_output=True,text=True,timeout=35);assert denied.returncode!=0 and snapshot()==before_denied,'Unauthorized worker changed state'
 write('worker-validation.json',{'result':'PASS','due_before':due,'processes':processes,'target_attempt':state,'changed_tables':changed,'workflow_and_notification_tables_unchanged':True,'unauthorized_exit':denied.returncode,'unauthorized_rows_unchanged':True,'provider_sends':False,'worker_processes_exited':True,'scope':'REGISTRATION_ONLY'})
 print('STANDALONE_WORKER_PASS process_runs=3 registration_only=true',flush=True)

 import worker_http_checks;worker_http_checks.run_http(root,evidence,env,sql,snapshot,private,jar,seed)

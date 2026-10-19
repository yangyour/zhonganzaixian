#!/usr/bin/env python3
import os,pathlib,sys,subprocess,json,hashlib
base=pathlib.Path('/tmp/dot-test-services-upstream-b72c3cf');repo=pathlib.Path(sys.argv[1]);env=os.environ.copy();env['PGPASSWORD']=env['MOP_MIGRATION_PASSWORD'];env['PGOPTIONS']='-c timezone=UTC -c datestyle=ISO,MDY';env['MOP_RUN_CHANGE_NOTICE_APPROVAL_TESTS']='true';env['JAVA_HOME']='/tmp/dot-tools/jdk';env['PATH']='/tmp/dot-tools/jdk/bin:'+env['PATH']
psql=['/tmp/dot-test-services/pg-icu/bin/psql','-X','-h','127.0.0.1','-p','25432','-U','mop_test_migrator','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
def sql(s):return subprocess.run(psql,input=s,text=True,env=env,capture_output=True,check=True).stdout.strip()
def snapshot():
 tables=sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename;").splitlines();out={}
 for t in tables:
  condition=" WHERE key<>'change_notice.approver_policy'" if t=='mop_configuration' else ''
  out[t]=sql('SELECT count(*)::text||\'|\'||md5(coalesce(string_agg(row_to_json(t)::text,E\'\\n\' ORDER BY row_to_json(t)::text),\'\')) FROM public."'+t+'" t'+condition+';')
 return out
before=snapshot();libs=[]
for part in ['org/flywaydb','org/postgresql','com/fasterxml/jackson','com/google/code/gson']:libs.extend(str(p) for p in pathlib.Path('/tmp/dot-tools/m2',part).rglob('*.jar'))
cp=':'.join([str(repo/'server/persistence/target/classes'),str(repo/'server/domain/target/classes')]+libs)
cli=['/tmp/dot-tools/jdk/bin/java','-cp',cp,'cn.mop.persistence.TestSchemaMigration']
expected='a24085a280ff822da932904c20cb3189fd813ef1d6ddc2f5458723ba48025756'
assert hashlib.sha256(next((repo/'server/persistence/src/main/resources/db/migration').glob('V40__*')).read_bytes()).hexdigest()==expected
results=[]
for flag in ['--apply-change-notice-approval','--apply-change-notice-approval','--check']:
 r=subprocess.run(cli+[flag],text=True,capture_output=True,env=env);results.append({'mode':flag,'exit':r.returncode,'summary':r.stdout.strip()});print(r.stdout,flush=True)
 if r.returncode:print(r.stderr);raise RuntimeError('V40 CLI failure')
 if len(results)==1 and not (base/'v40-applied-freeze.json').exists():
  import datetime
  applied={'sha256':expected,'applied_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'dot-upstream-b72c3cf-fresh-pg'}
  (base/'v40-applied-freeze.json').write_text(json.dumps(applied,indent=2)+'\n');print('V40_FIRST_APPLY_SUCCESS '+json.dumps(applied),flush=True)
after=snapshot();changed=[t for t in before if before[t]!=after.get(t)];added=sorted(set(after)-set(before));assert not changed,changed;assert added==['mop_change_notice_approval_event'],added
assert sql("SELECT count(*) FROM mop_configuration WHERE key='change_notice.approver_policy' AND registry_json->'value'='null'::jsonb AND registry_json->>'decision_status'='pending';")=='1'
checks=sql("SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='public.mop_change_notice_approval_event'::regclass AND contype='c' ORDER BY conname;")
(base/'v40-check-constraints.txt').write_text(checks+'\n')
check_json=json.loads(sql("SELECT jsonb_object_agg(conname,pg_get_constraintdef(oid) ORDER BY conname) FROM pg_constraint WHERE conrelid='public.mop_change_notice_approval_event'::regclass AND contype='c';"))
(base/'v40-check-constraints-utc.json').write_text(json.dumps(check_json,ensure_ascii=True,indent=2)+'\n')
(base/'v40-check-constraint-hashes-utc.json').write_text(json.dumps({k:hashlib.sha256(v.encode('utf-8')).hexdigest() for k,v in check_json.items()},indent=2)+'\n')
fks=sql("SELECT conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE confrelid IN ('public.mop_raw_monitoring_event'::regclass,'public.mop_monitoring_quarantine'::regclass) AND contype='f' ORDER BY conname;");(base/'v37-dependent-fks.txt').write_text(fks+'\n')
report={'environment':'dot-upstream-b72c3cf-fresh-pg','postgresql':'17.9 ICU76.1','sql_sha256':hashlib.sha256(next((repo/'server/persistence/src/main/resources/db/migration').glob('V40__*')).read_bytes()).hexdigest(),'migration_results':results,'existing_tables_compared':len(before),'existing_tables_unchanged':not changed,'expected_configuration_addition':'change_notice.approver_policy=null,pending','new_tables':added,'before':before,'after':after,'constraint_definitions':checks.splitlines()};(base/'v40-migration-evidence.json').write_text(json.dumps(report,indent=2)+'\n');print('V40_MIGRATION_PASS fingerprints_unchanged='+str(len(before)),flush=True)
assert hashlib.sha256(next((repo/'server/persistence/src/main/resources/db/migration').glob('V40__*')).read_bytes()).hexdigest()==expected
args=['/tmp/dot-tools/apache-maven-3.9.9/bin/mvn','-o','-s','/tmp/dot-tools/maven-curl-settings.xml','-pl','server/persistence','-am','-Dtest=PostgresChangeNoticeApprovalIntegrationTest','-Dsurefire.failIfNoSpecifiedTests=false','-DargLine=-javaagent:/tmp/dot-tools/m2/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar','test']
with (base/'v40-integration-tests.log').open('w') as log:r=subprocess.run(args,cwd=repo,env=env,stdout=log,stderr=log)
print('V40_TESTS_EXIT='+str(r.returncode),flush=True)
api_exit=0
if os.environ.get('DOT_API_APPROVAL_JAR'):
 api_exit=subprocess.run([sys.executable,str(base/'api-approval-smoke.py'),os.environ['DOT_API_APPROVAL_JAR']],env=env).returncode
 print('APPROVAL_HTTP_EXIT='+str(api_exit),flush=True)
sys.exit(r.returncode or api_exit)

#!/usr/bin/env python3
import os,pathlib,sys,subprocess,json,hashlib,re,datetime,xml.etree.ElementTree as ET,tempfile,zipfile,shutil,signal
signal.signal(signal.SIGTERM,lambda *_:sys.exit(143))
base=pathlib.Path(__file__).parent;manifest=json.loads((base/'snapshot.json').read_text());repo=pathlib.Path(manifest['snapshot_path']);env=os.environ.copy();env['PGPASSWORD']=env['MOP_MIGRATION_PASSWORD'];env['PGOPTIONS']='-c timezone=UTC -c datestyle=ISO,MDY';env['JAVA_HOME']='/tmp/dot-tools/jdk';env['PATH']='/tmp/dot-tools/jdk/bin:'+env['PATH']
psql=['/tmp/dot-test-services/pg-icu/bin/psql','-X','-h','127.0.0.1','-p','25433','-U','mop_test_migrator','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
def sql(s):return subprocess.run(psql,input=s,text=True,env=env,capture_output=True,check=True).stdout.strip()
def write(n,d):(base/n).write_text(json.dumps(d,indent=2)+'\n')
def snapshot():
 out={}
 for t in sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;").splitlines():
  out[t]=sql('SELECT count(*)::text||\'|\'||md5(coalesce(string_agg(row_to_json(t)::text,E\'\\n\' ORDER BY row_to_json(t)::text),\'\')) FROM public."'+t+'" t;')
 return out
libs=[]
for part in ['org/flywaydb','org/postgresql','com/fasterxml/jackson','com/google/code/gson']:libs.extend(str(p) for p in pathlib.Path('/tmp/dot-tools/m2',part).rglob('*.jar'))
cp=':'.join([str(base),str(repo/'server/persistence/target/classes'),str(repo/'server/domain/target/classes')]+libs);java='/tmp/dot-tools/jdk/bin/'
subprocess.run([java+'javac','-cp',cp,str(base/'DotEmptyBootstrap.java')],check=True)
assert sql("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")=='0'
subprocess.run([java+'java','-cp',cp,'DotEmptyBootstrap'],env=env,check=True)
cli=[java+'java','-cp',cp,'cn.mop.persistence.TestSchemaMigration'];source=(repo/'server/persistence/src/main/java/cn/mop/persistence/TestSchemaMigration.java').read_text();enums={n:int(v) for n,v in re.findall(r'(APPLY_[A-Z_]+)\((\d+)\)',source)};flags=[(enums[n],f) for f,n in re.findall(r'"(--apply-[a-z-]+)"\.equals\(args\[0\]\)\)return Mode\.(APPLY_[A-Z_]+)',source)];assert sorted(v for v,f in flags)==list(range(5,42))
results=[]
for v,flag in sorted(flags):
 r=subprocess.run(cli+[flag],env=env,text=True,capture_output=True);results.append({'version':v,'mode':flag,'exit':r.returncode,'summary':r.stdout.strip()});write('migration-initialization.json',results)
 assert r.returncode==0,('migration_failed',v);print('FROZEN_MIGRATION_PASS V'+str(v),flush=True)
assert sql('SELECT count(*) FROM flyway_schema_history WHERE success;')=='41'
history=json.loads(sql("SELECT jsonb_agg(jsonb_build_object('version',version,'script',script,'checksum',checksum,'success',success) ORDER BY installed_rank) FROM flyway_schema_history;"));before=snapshot();checks=[]
for flag in ['--apply-inspection-quarterly-reports','--check']:
 r=subprocess.run(cli+[flag],env=env,text=True,capture_output=True);checks.append({'mode':flag,'exit':r.returncode,'summary':r.stdout.strip()});assert r.returncode==0,(flag,'schema check failed')
after=snapshot();assert before==after,'repeat and read-only schema checks changed database rows';write('migration-attestation.json',{'history':history,'frozen_migration_hashes':manifest['migrations'],'database_initially_empty':True,'bootstrap_v1_v4_executed':4,'remaining_versions_explicitly_applied':list(range(5,42)),'new_migrations':[],'repeat_and_check':checks,'all_tables_unchanged_by_repeat_and_check':len(before),'before':before,'after':after,'server_version':sql('SHOW server_version'),'icu_collation':sql("SELECT collversion FROM pg_collation WHERE collname='und-x-icu'"),'runtime_role':json.loads(sql("SELECT row_to_json(r) FROM (SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname='mop_test_runtime') r"))})
private=pathlib.Path(tempfile.mkdtemp(prefix='classpath-',dir=base));private.chmod(0o700)
try:
 jar=repo/'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'
 with zipfile.ZipFile(jar) as z:
  for n in z.namelist():
   if n.startswith('BOOT-INF/classes/') or n.startswith('BOOT-INF/lib/'):z.extract(n,private)
 testcp=[str(repo/'server/persistence/target/test-classes'),str(repo/'server/persistence/target/classes')]+[str(p) for p in (private/'BOOT-INF/lib').glob('*.jar')]
 for part in ['org/mockito','net/bytebuddy','org/objenesis']:testcp += [str(p) for p in pathlib.Path('/tmp/dot-tools/m2',part).rglob('*.jar')]
 classes=['PostgresGlobalSearchIntegrationTest','PostgresEventGlobalSearchIntegrationTest'];expected={name:len(re.findall(r'@Test\b',(repo/'server/persistence/src/test/java/cn/mop/persistence'/f'{name}.java').read_text())) for name in classes}
 args=[java+'java','-javaagent:/tmp/dot-tools/m2/org/mockito/mockito-core/5.17.0/mockito-core-5.17.0.jar','-jar','/tmp/dot-tools/junit-platform-console-standalone.jar','execute','--disable-ansi-colors','--class-path',':'.join(testcp),'--reports-dir',str(base/'junit')]
 for name in classes:args+=['--select-class','cn.mop.persistence.'+name]
 with (base/'real-pg-tests.log').open('w') as f:r=subprocess.run(args,env=env,stdout=f,stderr=f)
 summaries={};cases=[]
 for path in (base/'junit').glob('TEST-*.xml'):
  tree=ET.parse(path).getroot()
  for tc in tree.findall('testcase'):
   name=tc.attrib['classname'].split('.')[-1]
   if name not in classes:continue
   item=summaries.setdefault(name,{'tests':0,'failures':0,'errors':0,'skipped':0});item['tests']+=1
   for kind in ['failure','error','skipped']:
    if tc.find(kind) is not None:item[{'failure':'failures','error':'errors','skipped':'skipped'}[kind]]+=1
   cases.append({'class':name,'name':tc.attrib['name'],'time_seconds':tc.attrib.get('time'),'passed':not any(tc.find(k) is not None for k in ['failure','error','skipped'])})
 write('real-pg-tests.json',{'expected':expected,'results':summaries,'process_exit':r.returncode});write('real-pg-test-cases.json',cases)
 assert r.returncode==0,('PG tests failed',summaries)
 for name,count in expected.items():assert summaries.get(name)=={'tests':count,'failures':0,'errors':0,'skipped':0},(name,summaries.get(name))
 print('REAL_PG_PASS '+json.dumps(summaries),flush=True)
 r=subprocess.run(cli+['--check'],env=env,text=True,capture_output=True);write('post-tests-schema-check.json',{'exit':r.returncode,'summary':r.stdout.strip()});assert r.returncode==0
 import event_http_checks
 event_http_checks.run(jar,private,sql,snapshot)
finally:shutil.rmtree(private)
changed=[name for name,h in manifest['files'].items() if hashlib.sha256((repo/name).read_bytes()).hexdigest()!=h];write('post-validation-snapshot.json',{'files_compared':len(manifest['files']),'changed_files':changed});assert not changed
print('EVENT_SEARCH_ALL_CHECKS_PASS',flush=True)

"""One fresh, disposable loopback lifecycle. No original server, secrets or provider settings inherited."""
import os,pathlib,tempfile,secrets,subprocess,json,hashlib,datetime,re,shutil,sys,signal
signal.signal(signal.SIGTERM,lambda *_:sys.exit(143))
root=pathlib.Path('/tmp/za-integration-work');base=pathlib.Path('/tmp/za-workflow-recovery-harness');evidence=root/'docs/evidence/workflow-recovery-api-20261021/attempt-2';evidence.mkdir(parents=True,exist_ok=True)
manifest=json.loads((base/'snapshot.json').read_text());jar=root/'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'
assert hashlib.sha256(jar.read_bytes()).hexdigest()==manifest['api_jar_sha256']
for name,digest in manifest['migrations'].items():assert hashlib.sha256((root/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest()==digest
pg=pathlib.Path('/tmp/za-pg/usr/lib/postgresql/17/bin');java='/tmp/za-java/jdk-17.0.20.1+1/bin/java';cp=(base/'classpath.txt').read_text()
private=pathlib.Path(tempfile.mkdtemp(prefix='za-disposable-pg-'));private.chmod(0o700)
env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL'}}
env['LD_LIBRARY_PATH']='/tmp/za-pg/usr/lib/x86_64-linux-gnu'
env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false')
admin=secrets.token_urlsafe(32);migrator=secrets.token_urlsafe(32);runtime=secrets.token_urlsafe(32);pw=private/'init-password';pw.write_text(admin);pw.chmod(0o600)
started=False;status='FAILED';stage='initdb';results=[];cleanup={'loopback_only':True,'provider_sends':False,'temporary_credentials_only':True}
def write(name,value):(evidence/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
def sql(command,user='mop_test_migrator',database='mop_test'):
 settings=env.copy();settings['PGPASSWORD']=admin if user=='dot_ephemeral_admin' else migrator if user=='mop_test_migrator' else runtime
 settings['PGOPTIONS']='-c timezone=UTC -c datestyle=ISO,MDY'
 r=subprocess.run([str(pg/'psql'),'-X','-h','127.0.0.1','-p','25432','-U',user,'-d',database,'-v','ON_ERROR_STOP=1','-At'],input=command,text=True,env=settings,capture_output=True)
 if r.returncode:raise RuntimeError('SQL check failed (details withheld)')
 return r.stdout.strip()
def history():return json.loads(sql("SELECT coalesce(jsonb_agg(jsonb_build_object('version',version,'script',script,'checksum',checksum,'success',success,'installed_on',installed_on) ORDER BY installed_rank),'[]'::jsonb) FROM public.flyway_schema_history;"))
def snapshot():
 out={}
 for table in sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename;").splitlines():
  assert re.fullmatch(r'[a-z_][a-z_0-9]*',table)
  out[table]=sql('SELECT count(*)::text||\'|\'||md5(coalesce(string_agg(row_to_json(t)::text,E\'\\n\' ORDER BY row_to_json(t)::text),\'\')) FROM public."'+table+'" t;')
 return out
try:
 subprocess.run([str(pg/'initdb'),'-D',str(private/'data'),'-U','dot_ephemeral_admin','--auth=scram-sha-256','--encoding=UTF8','--locale=C','--pwfile='+str(pw)],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
 with (private/'data/postgresql.conf').open('a') as f:f.write("\nlisten_addresses='127.0.0.1'\nport=25432\nunix_socket_directories=''\nlog_statement='none'\nlog_min_error_statement='panic'\n")
 stage='start';subprocess.run([str(pg/'pg_ctl'),'-D',str(private/'data'),'-l',str(private/'postgres.log'),'-w','-t','20','start'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True);started=True
 stage='roles';sql(f"CREATE ROLE mop_test_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{migrator}'; CREATE ROLE mop_test_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{runtime}';",'dot_ephemeral_admin','postgres')
 sql('CREATE DATABASE mop_test OWNER mop_test_migrator;','dot_ephemeral_admin','postgres');sql('REVOKE ALL ON DATABASE mop_test FROM PUBLIC; GRANT CONNECT ON DATABASE mop_test TO mop_test_runtime;','dot_ephemeral_admin','postgres')
 env.update(SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:25432/mop_test',SPRING_DATASOURCE_USERNAME='mop_test_runtime',SPRING_DATASOURCE_PASSWORD=runtime,MOP_MIGRATION_USERNAME='mop_test_migrator',MOP_MIGRATION_PASSWORD=migrator,MOP_TEST_ENVIRONMENT_NAME='dot-integration-fresh-pg17-11')
 write('environment.json',{'server_version':sql('SHOW server_version'),'icu_collation_version':sql("SELECT collversion FROM pg_collation WHERE collname='und-x-icu'"),'runtime_role':json.loads(sql("SELECT row_to_json(x) FROM (SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolbypassrls FROM pg_roles WHERE rolname='mop_test_runtime') x")),**cleanup})
 print('POSTGRES_READY loopback_only version=17.11 disposable=true',flush=True)
 stage='bootstrap';r=subprocess.run([java,'-cp',cp,'NikoSchemaBootstrap','--initialize-new-niko-database'],env=env,text=True,capture_output=True);write('bootstrap.json',{'exit':r.returncode,'summary':r.stdout.strip()});assert r.returncode==0,'bootstrap failed'
 src=(root/'server/persistence/src/main/java/cn/mop/persistence/TestSchemaMigration.java').read_text();versions={n:int(v) for n,v in re.findall(r'(APPLY_[A-Z_]+)\((\d+)\)',src)};flags=[(versions[n],f) for f,n in re.findall(r'"(--apply-[a-z-]+)"\.equals\(args\[0\]\)\)return Mode\.(APPLY_[A-Z_]+)',src)];assert sorted(v for v,f in flags)==list(range(5,46))
 cli=[java,'-cp',cp,'cn.mop.persistence.TestSchemaMigration']
 for version,flag in sorted(flags):
  stage='migration_V'+str(version);before=snapshot() if version>=42 else None
  r=subprocess.run(cli+[flag],env=env,text=True,capture_output=True)
  current=history();item={'version':version,'mode':flag,'exit':r.returncode,'summary':r.stdout.strip(),'history':current};results.append(item);write('migration-results.json',results)
  if version>=42:
   after=snapshot();applied=any(h['version']==str(version) and h['success'] for h in current)
   write(f'v{version}-application.json',{'applied':applied,'sql_sha256':manifest['migrations'][next(n for n in manifest['migrations'] if n.startswith('V'+str(version)+'__'))],'before':before,'after':after,'new_tables':sorted(set(after)-set(before)),'changed_existing_tables':sorted(t for t in before if before[t]!=after.get(t)),'cli_exit':r.returncode,'cli_summary':r.stdout.strip()})
  assert r.returncode==0,stage+' verification failed'
  print('MIGRATION_PASS V'+str(version),flush=True)
 stage='repeat_and_readonly';before=snapshot();checks=[]
 for flag in ['--apply-sla-reminders','--check']:
  r=subprocess.run(cli+[flag],env=env,text=True,capture_output=True);checks.append({'mode':flag,'exit':r.returncode,'summary':r.stdout.strip()});assert r.returncode==0
 assert snapshot()==before,'Repeat/check changed rows'
 write('repeat-and-check.json',{'checks':checks,'all_table_fingerprints_unchanged':True,'table_count':len(before)})
 stage='business_and_http';import business_checks;business_checks.run(root,evidence,env,sql,snapshot,private,jar)
 status='PASS';write('summary.json',{'result':status,'versions_applied':list(range(1,46)),'source_snapshot':manifest,'api_http':'PASS','production_approved':False})
except BaseException as error:
 write('failure.json',{'stage':stage,'error_type':type(error).__name__,'detail':str(error) if isinstance(error,AssertionError) else 'REDACTED','production_approved':False});print('VALIDATION_FAILED stage='+stage+' type='+type(error).__name__,flush=True)
finally:
 if started:
  r=subprocess.run([str(pg/'pg_ctl'),'-D',str(private/'data'),'-m','fast','-w','stop'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);cleanup['postgresql_stopped']=r.returncode==0
 else:cleanup['postgresql_stopped']=True
 if cleanup['postgresql_stopped']:shutil.rmtree(private)
 cleanup['temporary_database_and_credentials_removed']=not private.exists();cleanup['result']=status;write('cleanup.json',cleanup);print('POSTGRES_CLEANUP '+json.dumps(cleanup),flush=True)
sys.exit(0 if status=='PASS' else 1)

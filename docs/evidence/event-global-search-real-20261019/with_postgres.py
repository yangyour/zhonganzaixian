#!/usr/bin/env python3
"""Fresh loopback PG lifecycle; requires root's exact approved snapshot JAR hash."""
import os,pathlib,tempfile,secrets,subprocess,sys,shutil,json,hashlib,datetime,signal
signal.signal(signal.SIGTERM,lambda *_:sys.exit(143))
base=pathlib.Path(__file__).parent;manifest=json.loads((base/'snapshot.json').read_text());snap=pathlib.Path(manifest['snapshot_path']);jar=snap/'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'
if os.environ.get('DOT_EVENT_SEARCH_SNAPSHOT_GO')!=manifest['api_jar_sha256']:raise SystemExit('No exact snapshot GO; services not started')
for name,expected in manifest['files'].items():assert hashlib.sha256((snap/name).read_bytes()).hexdigest()==expected,('snapshot changed before startup',name)
pg=pathlib.Path('/tmp/dot-test-services/pg-icu/bin');private=pathlib.Path(tempfile.mkdtemp(prefix='session-',dir=base));private.chmod(0o700)
env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','JAVA_HOME','LD_LIBRARY_PATH'}}
env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false')
admin=secrets.token_urlsafe(32);migrator=secrets.token_urlsafe(32);runtime=secrets.token_urlsafe(32);pw=private/'init-password';pw.write_text(admin);pw.chmod(0o600);env['PGPASSWORD']=admin
started=False;result=None;child=None;cleanup={'started_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'dot-event-search-fresh-pg','loopback_only':True,'inherited_provider_settings_stripped':True,'email_enabled':False,'wecom_enabled':False}
try:
 subprocess.run([str(pg/'initdb'),'-D',str(private/'data'),'-U','dot_ephemeral_admin','--auth=scram-sha-256','--encoding=UTF8','--locale=C','--pwfile='+str(pw)],env=env,stdout=subprocess.DEVNULL,check=True)
 with (private/'data/postgresql.conf').open('a') as f:f.write("\nlisten_addresses='127.0.0.1'\nport=25433\nunix_socket_directories=''\nlog_statement='none'\nlog_min_error_statement='panic'\n")
 subprocess.run([str(pg/'pg_ctl'),'-D',str(private/'data'),'-l',str(private/'postgres.log'),'-w','start'],env=env,check=True,stdout=subprocess.DEVNULL);started=True
 cmd=[str(pg/'psql'),'-X','-h','127.0.0.1','-p','25433','-U','dot_ephemeral_admin','-d','postgres','-v','ON_ERROR_STOP=1','-At']
 sql=f"CREATE ROLE mop_test_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{migrator}';\nCREATE ROLE mop_test_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{runtime}';\nCREATE DATABASE mop_test OWNER mop_test_migrator;\nREVOKE ALL ON DATABASE mop_test FROM PUBLIC;\nGRANT CONNECT ON DATABASE mop_test TO mop_test_runtime;\n"
 subprocess.run(cmd,input=sql,text=True,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
 del env['PGPASSWORD'];env.update(SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:25433/mop_test',SPRING_DATASOURCE_USERNAME='mop_test_runtime',SPRING_DATASOURCE_PASSWORD=runtime,MOP_MIGRATION_USERNAME='mop_test_migrator',MOP_MIGRATION_PASSWORD=migrator,MOP_RUN_REMOTE_TESTS='true',MOP_RUN_EVENT_GLOBAL_SEARCH_TESTS='true',MOP_TEST_ENVIRONMENT_NAME='dot-event-search-fresh-pg')
 print('POSTGRES_READY version=17.9 endpoint=loopback_only:25433 database=mop_test disposable=true',flush=True)
 child=subprocess.Popen(sys.argv[1:],env=env);code=child.wait();result=code;cleanup['validation_exit']=code
finally:
 if child is not None and child.poll() is None:
  child.terminate()
  try:child.wait(timeout=30)
  except subprocess.TimeoutExpired:child.kill();child.wait()
 if started:
  stopped=subprocess.run([str(pg/'pg_ctl'),'-D',str(private/'data'),'-m','fast','-w','stop'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);cleanup['postgresql_stopped']=stopped.returncode==0
 else:cleanup['postgresql_stopped']=True
 if cleanup['postgresql_stopped']:shutil.rmtree(private)
 cleanup['temporary_database_and_credentials_removed']=not private.exists();cleanup['finished_at_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat();(base/'lifecycle.json').write_text(json.dumps(cleanup,indent=2)+'\n');print('POSTGRES_CLEANUP '+json.dumps(cleanup),flush=True)
sys.exit(result if result is not None else 1)

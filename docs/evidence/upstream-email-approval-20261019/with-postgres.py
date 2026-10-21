#!/usr/bin/env python3
"""Disposable loopback PostgreSQL harness. No original server or repo files touched."""
import os,pathlib,tempfile,secrets,subprocess,sys,shutil,json
if os.environ.get('DOT_FRESH_LINEAGE_GO')!='b72c3cf-v40':raise SystemExit('Fresh lineage run not released; no services started')
base=pathlib.Path('/tmp/dot-test-services-upstream-b72c3cf'); pg=pathlib.Path('/tmp/dot-test-services/pg-icu/bin')
private=pathlib.Path(tempfile.mkdtemp(prefix='session-',dir=base)); private.chmod(0o700)
env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','JAVA_HOME','LD_LIBRARY_PATH','DOT_API_APPROVAL_JAR','DOT_FRESH_LINEAGE_GO'}};env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false'); admin=secrets.token_urlsafe(32); migrator=secrets.token_urlsafe(32); runtime=secrets.token_urlsafe(32)
pw=private/'init-password'; pw.write_text(admin); pw.chmod(0o600)
env['PGPASSWORD']=admin
started=False
try:
 subprocess.run([str(pg/'initdb'),'-D',str(private/'data'),'-U','dot_ephemeral_admin','--auth=scram-sha-256','--encoding=UTF8','--locale=C','--pwfile='+str(pw)],env=env,stdout=subprocess.DEVNULL,check=True)
 with (private/'data/postgresql.conf').open('a') as f:f.write("\nlisten_addresses='127.0.0.1'\nport=25432\nunix_socket_directories=''\nlog_statement='none'\nlog_min_error_statement='panic'\n")
 subprocess.run([str(pg/'pg_ctl'),'-D',str(private/'data'),'-l',str(private/'postgres.log'),'-w','start'],env=env,check=True,stdout=subprocess.DEVNULL); started=True
 cmd=[str(pg/'psql'),'-X','-h','127.0.0.1','-p','25432','-U','dot_ephemeral_admin','-d','postgres','-v','ON_ERROR_STOP=1','-At']
 sql=f"CREATE ROLE mop_test_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{migrator}';\nCREATE ROLE mop_test_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{runtime}';\nCREATE DATABASE mop_test OWNER mop_test_migrator;\nREVOKE ALL ON DATABASE mop_test FROM PUBLIC;\nGRANT CONNECT ON DATABASE mop_test TO mop_test_runtime;\n"
 subprocess.run(cmd,input=sql,text=True,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True)
 del env['PGPASSWORD'];env.update(SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:25432/mop_test',SPRING_DATASOURCE_USERNAME='mop_test_runtime',SPRING_DATASOURCE_PASSWORD=runtime,MOP_MIGRATION_USERNAME='mop_test_migrator',MOP_MIGRATION_PASSWORD=migrator,MOP_RUN_REMOTE_TESTS='true',MOP_TEST_ENVIRONMENT_NAME='dot-upstream-b72c3cf-fresh-pg')
 for key in ['MOP_RUN_MONITORING_EVENT_CENTER_TESTS','MOP_RUN_ONCALL_ESCALATION_TESTS','MOP_RUN_SERVICE_TIER_TESTS','MOP_RUN_RUNTIME_CONFIG_TESTS','MOP_RUN_TICKET_NOTIFICATION_TESTS','MOP_RUN_TICKET_WORKFLOW_TESTS']:env[key]='true'
 print('POSTGRES_READY version=17.9 endpoint=loopback_only:25432 database=mop_test disposable=true',flush=True)
 result=subprocess.run(sys.argv[1:],env=env)
 sys.exit(result.returncode)
finally:
 if started:subprocess.run([str(pg/'pg_ctl'),'-D',str(private/'data'),'-m','fast','-w','stop'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 shutil.rmtree(private)
 print('POSTGRES_CLEANUP complete=true temporary_credentials_removed=true',flush=True)

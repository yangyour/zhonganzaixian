from pathlib import Path
import json,os,subprocess
root=Path.cwd(); config=json.loads((root/'.local/niko-test/private/bootstrap.json').read_text())['component_credentials'];pg=root.parent/'mop-toolchain/components/pg'
env=os.environ.copy();env.update(PGPASSWORD=config['pg_admin_password'],LD_LIBRARY_PATH=str(pg/'usr/lib/x86_64-linux-gnu'))
psql=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-h','127.0.0.1','-p','25432','-U','mop_niko_admin','-d','postgres','-v','ON_ERROR_STOP=1','-At']
def sql(s):
 r=subprocess.run(psql,input=s,text=True,env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if r.returncode:raise RuntimeError('Local PostgreSQL statement failed; details withheld')
 return r.stdout.strip()
roles={'mop_test_migrator':'pg_migrator_password','mop_test_runtime':'pg_runtime_password','mop_keycloak':'kc_db_password'}
for role,key in roles.items():
 if sql("SELECT count(*) FROM pg_roles WHERE rolname='"+role+"';")=='0':
  sql('CREATE ROLE '+role+' LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '+"'"+config[key].replace("'","''")+"';")
for database,owner in [('mop_test','mop_test_migrator'),('mop_keycloak','mop_keycloak')]:
 if sql("SELECT count(*) FROM pg_database WHERE datname='"+database+"';")=='0':sql('CREATE DATABASE '+database+' OWNER '+owner+';')
 sql('REVOKE ALL ON DATABASE '+database+' FROM PUBLIC;')
sql('GRANT CONNECT ON DATABASE mop_test TO mop_test_runtime;')
psql[psql.index('postgres')]='mop_test'
sql('REVOKE CREATE ON SCHEMA public FROM PUBLIC; GRANT USAGE ON SCHEMA public TO mop_test_runtime;')
print(json.dumps({'postgres_version':sql('SHOW server_version;'),'roles_ready':list(roles),'runtime_privileges_safe':sql("SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication AND NOT rolbypassrls FROM pg_roles WHERE rolname='mop_test_runtime';")=='t','secrets_printed':False,'application_migrations_executed':0}))

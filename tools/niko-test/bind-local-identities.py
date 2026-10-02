from pathlib import Path
import json,os,subprocess,hashlib
root=Path.cwd();local=root/'.local/niko-test';runtime=json.loads((local/'private/runtime.json').read_text());pg=root.parent/'mop-toolchain/components/pg';env=os.environ.copy();env.update(PGPASSWORD=runtime['environment']['MOP_MIGRATION_PASSWORD'],LD_LIBRARY_PATH=str(pg/'usr/lib/x86_64-linux-gnu'))
marker=local/'private/identity-bindings.json';wanted={u['role']:u['identity_subject'] for u in runtime['test_users']}
if marker.exists():
 if json.loads(marker.read_text())!=wanted:raise RuntimeError('Identity inputs changed; explicit review required')
 print('identity_binding_already_recorded');raise SystemExit(0)
# This command is only for the just-created niko database and synthetic users, never the original server.
assert runtime['environment']['SPRING_DATASOURCE_URL']=='jdbc:postgresql://127.0.0.1:25432/mop_test'
lines=['BEGIN;',"DO $$ BEGIN IF current_database()<>'mop_test' OR (SELECT count(*) FROM public.mop_user)<>6 OR EXISTS(SELECT 1 FROM public.mop_ticket) THEN RAISE EXCEPTION 'Fresh test database required'; END IF; END $$;"]
for role,subject in wanted.items():
 assert role in ['padmin','reviewer','msp','tadmin','viewerA','viewerB']
 uid=hashlib.md5(('mop-fixture-user-'+role).encode()).hexdigest()
 # Values are verified local OIDC identities, not user-supplied arbitrary subjects.
 lines.append("UPDATE public.mop_user SET subject='"+subject.replace("'","''")+"' WHERE id='"+uid+"'::uuid AND identity_source='test_oidc';")
lines.append('COMMIT;')
args=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-h','127.0.0.1','-p','25432','-U','mop_test_migrator','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
r=subprocess.run(args,input='\n'.join(lines),env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
if r.returncode:raise RuntimeError('Local synthetic identity binding failed; SQL details omitted')
fd=os.open(marker,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:json.dump(wanted,f)
print('new_environment_identity_bindings=6; original_server_untouched=true; migration_files_unchanged=true')

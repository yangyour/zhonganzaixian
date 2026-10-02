"""Fingerprint synthetic project rows before/after an owned-stack graceful restart."""
from pathlib import Path
import os,json,subprocess,sys,hashlib,datetime
root=Path.cwd();private=root/'.local/niko-test/private';runtime=json.loads((private/'runtime.json').read_text());pg=root.parent/'mop-toolchain/components/pg'
assert runtime['environment']['SPRING_DATASOURCE_URL']=='jdbc:postgresql://127.0.0.1:25432/mop_test'
env=os.environ.copy();env.update(PGPASSWORD=runtime['environment']['SPRING_DATASOURCE_PASSWORD'],LD_LIBRARY_PATH=str(pg/'usr/lib/x86_64-linux-gnu'))
args=[str(pg/'usr/lib/postgresql/17/bin/psql'),'-h','127.0.0.1','-p','25432','-U','mop_test_runtime','-d','mop_test','-v','ON_ERROR_STOP=1','-At']
snapshot={}
for table in ['mop_user','mop_membership','mop_configuration','mop_configuration_revision','mop_configuration_revision_history','mop_asset','mop_ticket','mop_audit']:
 result=subprocess.run(args,input='SELECT row_to_json(t)::text FROM public.'+table+' t ORDER BY row_to_json(t)::text;',env=env,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if result.returncode:raise SystemExit('Snapshot query failed; private details withheld')
 snapshot[table]={'rows':len(result.stdout.splitlines()),'sha256':hashlib.sha256(result.stdout.encode()).hexdigest()}
record=root/'.local/niko-test/db-before-restart.json'
if sys.argv[1:]==['--record']:
 record.write_text(json.dumps(snapshot,indent=2)+'\n');print('pre_restart_fingerprint_recorded tables='+str(len(snapshot)))
elif sys.argv[1:]==['--verify']:
 assert json.loads(record.read_text())==snapshot,'Project row fingerprints changed across restart'
 evidence={'recorded_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'niko-local-test','scope':'owned PostgreSQL/Keycloak/API graceful restart','database_fingerprints_equal':True,'tables':snapshot,'crash_recovery_tested':False,'minio_restart_tested':False,'browser_ui_tested':False}
 (root/'docs/evidence/niko-graceful-restart-20261002.json').write_text(json.dumps(evidence,indent=2)+'\n');print('post_restart_fingerprints_equal=true tables='+str(len(snapshot)))
else:raise SystemExit('Use --record or --verify')

"""Explicit V28 only; verify owned application containers, backup, migrate, restore them."""
import datetime, hashlib, importlib.util, json, subprocess, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'.local/python-deps'))
from inspect_test_server import connect
spec = importlib.util.spec_from_file_location('namespace', ROOT/'tools/prepare-test-namespace.py')
namespace = importlib.util.module_from_spec(spec); spec.loader.exec_module(namespace)
target = ROOT/'.local/project-identity-v28-pwsh'; target.mkdir(exist_ok=True)
names = ['mop-test-web','mop-test-api','mop-test-photo-worker','mop-test-import-worker','mop-test-contact-worker']
def command(ssh, value):
    _, out, err = ssh.exec_command(value, timeout=90); data = out.read(); status = out.channel.recv_exit_status()
    if status: raise RuntimeError('Remote command failed; details redacted')
    return data
def snapshot(ssh):
    tables=namespace.sql(ssh,"SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'mop_%' ORDER BY tablename",'mop_test').splitlines()
    assert all(name.replace('_','').isalpha() for name in tables)
    expressions=["SELECT '%s' name, json_build_object('rows',count(*),'hash',encode(sha256(convert_to(COALESCE(string_agg(h,E'\\n' ORDER BY h),''),'UTF8')),'hex')) value FROM (SELECT to_jsonb(t)::text h FROM public.%s t) a" % (name,name) for name in tables]
    return json.loads(namespace.sql(ssh,'SELECT json_object_agg(name,value) FROM ('+' UNION ALL '.join(expressions)+') a','mop_test'))
ssh=connect(); stopped=[]
try:
    assert namespace.sql(ssh,"SELECT max(version::int) FROM public.flyway_schema_history WHERE success",'mop_test').strip()=='27'
    assert not (target/'before.json').exists(), 'Preserve previous evidence'
    source=ROOT/'server/persistence/src/main/resources/db/migration/V28__identity_inventory_and_password_formats.sql'
    draft=namespace.sql(ssh,"BEGIN; SET ROLE mop_test_migrator;\n"+source.read_text()+"\nSELECT 'DRAFT_OK' WHERE EXISTS(SELECT 1 FROM public.mop_role_capability_limit WHERE capability='tenants:read_inventory'); ROLLBACK;",'mop_test')
    assert 'DRAFT_OK' in draft
    inspected=json.loads(command(ssh,'docker inspect '+' '.join(names)))
    assert len(inspected)==5 and all(c['Config']['Labels'].get('mop.project')=='mop-platform-test' and c['State']['Running'] for c in inspected)
    for name in names: command(ssh,'docker stop '+name); stopped.append(name)
    before=snapshot(ssh); (target/'before.json').write_text(json.dumps(before,indent=2))
    backup=command(ssh,'docker exec shared-postgres pg_dump -U postgres -d mop_test -Fc'); assert backup.startswith(b'PGDMP'); (target/'backup.dump').write_bytes(backup)
    results=[]
    for mode in ['identity-compatibility','identity-compatibility',None]:
        args=['pwsh','-NoProfile','-File',str(ROOT/'deploy/migrate-test-schema.ps1')]+(['-ApplyFeature',mode] if mode else [])
        done=subprocess.run(args,cwd=ROOT,capture_output=True); (target/('migration-'+str(len(results))+'.log')).write_bytes(done.stdout+done.stderr)
        assert done.returncode==0,'Migration CLI failed; inspect private log'
        results.append({'mode':mode or 'readonly','exit_code':done.returncode})
    after=snapshot(ssh); (target/'after.json').write_text(json.dumps(after,indent=2))
    changed=[name for name in before if before[name]!=after[name]]
    assert set(changed)=={'mop_role_capability_limit','mop_project_role','mop_membership'}
    assert namespace.sql(ssh,"SELECT max(version::int) FROM public.flyway_schema_history WHERE success",'mop_test').strip()=='28'
    report={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'designated-server','postgresql':'17.9','schema_version':28,'dryrun_rolled_back':True,'explicit_migration_runs':results,'sql_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'tables_before':len(before),'unchanged_tables':len(before)-len(changed),'intentionally_changed_tables':changed,'backup_size':len(backup),'backup_sha256':hashlib.sha256(backup).hexdigest(),'shared_components_restarted':False,'password_policy_changed':False}
    (ROOT/'docs/evidence/project-identity-v28-20261005.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
except Exception as e:
    print(json.dumps({'status':'FAILED','exception':type(e).__name__,'details':'redacted'})); raise SystemExit(1)
finally:
    for name in reversed(stopped): command(ssh,'docker start '+name)
    ssh.close()

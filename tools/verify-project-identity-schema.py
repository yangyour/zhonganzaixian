"""Real designated PostgreSQL transactional schema probe. Always rolls back; never migrates history."""
import datetime
import hashlib
import importlib.util
import json
import uuid
from inspect_test_server import ROOT, connect

spec=importlib.util.spec_from_file_location('namespace',ROOT/'tools/prepare-test-namespace.py')
namespace=importlib.util.module_from_spec(spec);spec.loader.exec_module(namespace)
source=ROOT/'server/persistence/src/main/resources/db/migration/V27__project_identity_and_roles.sql'
sql=source.read_text(encoding='utf8')
user,role=str(uuid.uuid4()),str(uuid.uuid4())
tenant='55000000-0000-4000-8000-000000000001'
other='55000000-0000-4000-8000-000000000002'
statements=['BEGIN;','SET ROLE mop_test_migrator;',sql,'SET ROLE mop_test_runtime;']
checks=[]
def check(name,condition):
    checks.append(name)
    statements.append("DO $$ BEGIN IF NOT ("+condition+") THEN RAISE EXCEPTION 'Identity schema check failed'; END IF; END $$;")
def rejects(name,command,condition):
    checks.append(name)
    statements.append("DO $$ DECLARE rejected boolean=false; BEGIN BEGIN "+command+"; EXCEPTION WHEN "+condition+" THEN rejected=true; END; IF NOT rejected THEN RAISE EXCEPTION 'Expected guarded rejection'; END IF; END $$;")
check('all_deferred_password_policy_values_null',"SELECT minimum_password_length IS NULL AND password_history_count IS NULL AND failed_attempt_limit IS NULL AND lock_minutes IS NULL AND idle_minutes IS NULL AND force_change_on_reset IS NULL FROM public.mop_identity_policy WHERE singleton")
rejects('base_category_cannot_escalate',f"INSERT INTO public.mop_project_role(id,tenant_id,name,base_role,permissions) VALUES('{role}','{tenant}','SYNTHETIC_INVALID_ROLE','viewer',ARRAY['users:write'])",'raise_exception')
rejects('builtin_role_is_protected',f"UPDATE public.mop_project_role SET permissions=ARRAY[]::text[],version=version+1 WHERE tenant_id='{tenant}' AND base_role='padmin' AND builtin",'raise_exception')
rejects('capability_limits_are_readonly',"INSERT INTO public.mop_role_capability_limit(base_role,capability) VALUES('viewer','invented:all')",'insufficient_privilege')
statements += [f"INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) VALUES('{user}','project_local','{user}','SYNTHETIC_IDENTITY_SCHEMA','msp');",
 f"INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES('{tenant}','{user}','msp');",
 f"INSERT INTO public.mop_project_role(id,tenant_id,name,base_role,permissions) VALUES('{role}','{tenant}','SYNTHETIC_EXPLICIT_ROLE','msp',ARRAY['assets:read']);",
 f"INSERT INTO public.mop_member_role_assignment(tenant_id,user_id,role_id) VALUES('{tenant}','{user}','{role}');"]
check('explicit_role_has_no_implicit_write',f"SELECT public.mop_effective_capabilities('{user}','{tenant}')=ARRAY['assets:read']")
check('assignment_advances_permission_epoch',f"SELECT permission_version=2 FROM public.mop_membership WHERE tenant_id='{tenant}' AND user_id='{user}'")
statements.append(f"UPDATE public.mop_project_role SET permissions=ARRAY['assets:read','assets:write'],version=2 WHERE id='{role}';")
check('role_edit_advances_member_epoch',f"SELECT permission_version=3 AND public.mop_effective_capabilities('{user}','{tenant}') @> ARRAY['assets:write'] FROM public.mop_membership WHERE tenant_id='{tenant}' AND user_id='{user}'")
statements.append(f"UPDATE public.mop_project_role SET enabled=false,version=3 WHERE id='{role}';")
check('disabled_role_has_no_grants',f"SELECT cardinality(public.mop_effective_capabilities('{user}','{tenant}'))=0")
rejects('cross_tenant_assignment_rejected',f"INSERT INTO public.mop_member_role_assignment(tenant_id,user_id,role_id) VALUES('{other}','{user}','{role}')",'foreign_key_violation')
rejects('plaintext_credential_rejected',f"INSERT INTO public.mop_local_account(user_id,username,password_hash) VALUES('{user}','schema-{user[:8]}','synthetic-not-a-hash')",'check_violation')
statements.append(f"INSERT INTO public.mop_local_account(user_id,username,password_hash) VALUES('{user}','schema-{user[:8]}','{{pbkdf2-sha256-v1}}{'0'*96}');")
rejects('username_immutable_for_runtime',f"UPDATE public.mop_local_account SET username='rewritten' WHERE user_id='{user}'",'insufficient_privilege')
rejects('password_revision_required',f"UPDATE public.mop_local_account SET password_hash='{{pbkdf2-sha256-v1}}{'1'*96}' WHERE user_id='{user}'",'raise_exception')
statements.append(f"UPDATE public.mop_local_account SET password_hash='{{pbkdf2-sha256-v1}}{'1'*96}',version=2 WHERE user_id='{user}';")
check('password_change_revokes_old_authentication_revision',f"SELECT revision=2 FROM public.mop_identity_security WHERE user_id='{user}'")
statements += ['SET CONSTRAINTS ALL IMMEDIATE;',"SELECT 'PROJECT_IDENTITY_SCHEMA_PROBE_OK';",'ROLLBACK;']
client=connect()
try:
    before=namespace.sql(client,"SELECT max(version::integer) FROM public.flyway_schema_history WHERE success;",'mop_test').strip()
    assert before=='26','Use this dry-run only before the explicit V27 migration'
    result=namespace.sql(client,'\n'.join(statements),'mop_test')
    assert 'PROJECT_IDENTITY_SCHEMA_PROBE_OK' in result
    after=namespace.sql(client,"SELECT max(version::integer)=26 AND to_regclass('public.mop_local_account') IS NULL FROM public.flyway_schema_history WHERE success;",'mop_test').strip()
    assert after=='t'
    report={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'designated-server','postgresql':'17.9','schema_migration_applied':False,'transaction_rolled_back':True,'migration_history_still':26,'checks':checks,'passed':True,'draft_sql_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'shared_components_changed':False}
    (ROOT/'docs/evidence/project-identity-schema-dryrun-20261005.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps({'passed':True,'checks':len(checks),'migration_applied':False,'rolled_back':True}))
except Exception as failure:
    print(json.dumps({'passed':False,'error_type':type(failure).__name__,'transaction_must_rollback':True,'details':'redacted'}));raise SystemExit(1)
finally: client.close()

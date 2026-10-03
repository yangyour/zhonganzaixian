"""Explicit local-only credential creation; run only with owner approval for this environment."""
from pathlib import Path
import json,os,secrets,sys
if sys.argv[1:] not in [['--create-approved-local-test-credentials'],['--create-approved-database-identity-credentials']]:
    raise SystemExit('Explicit approved credential-creation flag required')
root=Path.cwd();local=root/'.local/niko-test';local.mkdir(mode=0o700,parents=True,exist_ok=True)
for name in ('private','data','logs','run'):(local/name).mkdir(mode=0o700,exist_ok=True)
p=local/'private/bootstrap.json'
if p.exists():raise SystemExit('Private configuration already exists; refusing regeneration or rotation')
credential_names=['pg_admin_password','pg_migrator_password','pg_runtime_password','kc_db_password','kc_admin_password','oidc_client_secret']
if sys.argv[1:] == ['--create-approved-local-test-credentials']:credential_names += ['minio_root_password','photo_secret','import_secret']
data={'environment_name':'niko-local-test','component_credentials':{k:secrets.token_urlsafe(32) for k in credential_names},'test_users':[{'username':'mopfixture-'+role,'role':role,'password':secrets.token_urlsafe(24)} for role in ['padmin','reviewer','msp','tadmin','viewerA','viewerB']]}
fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:json.dump(data,f,indent=2)
fd=os.open(local/'private/pg-init-password',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,'w') as f:f.write(data['component_credentials']['pg_admin_password'])
print('niko_local_private_configuration_created; secrets_omitted=true')

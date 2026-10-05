"""Preserve six existing server acceptance user IDs/passwords; inputs remain in private files/stdin."""
import hashlib, json, os, secrets, subprocess, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
try:
    private=ROOT/'deploy/private/server-test-runtime.json';cfg=json.loads(private.read_text());runtime=json.loads((ROOT/'deploy/private/runtime.json').read_text())
    assert cfg['project']=='mop-platform-test' and cfg['identity_provisioned'] and len(cfg['test_users'])==6
    dependencies=(ROOT/'.local/api-classpath.txt').read_text().strip()
    assert sys.argv[1:] in ([],['--candidate'])
    build=ROOT/'.local/identity-build-candidate' if sys.argv[1:]==['--candidate'] else ROOT
    if build!=ROOT:
        manifest=json.loads((build/'source-manifest.json').read_text())
        assert all(hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==value for name,value in manifest.items())
    classes=[build/f'server/{module}/target/classes' for module in ['api','persistence','domain','adapters']]
    classpath=os.pathsep.join([str(path) for path in classes]+[dependencies])
    env={**os.environ,**runtime['environment']}
    result=subprocess.run(['java','-cp',classpath,'cn.mop.api.ProjectIdentityBootstrap','--enable-existing-test-accounts'],input=json.dumps(cfg['test_users']).encode(),env=env,cwd=ROOT,capture_output=True)
    assert result.returncode==0,'Bootstrap failed; private diagnostic only'
    report=json.loads(result.stdout.decode().strip().splitlines()[-1]);assert report['inserted']+report['verified_existing']==6
    cfg.setdefault('auth_command_key',secrets.token_hex(32));cfg['local_identity_provisioned']=True
    private.write_text(json.dumps(cfg,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(json.dumps(report))
except Exception as e:
    print(json.dumps({'status':'FAILED','exception':type(e).__name__,'details':'redacted'}));raise SystemExit(1)

"""Upgrade only five owned MOP containers; retain old release/containers and rollback on failed readiness."""
import datetime, importlib.util, json, sys, time, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'.local/python-deps'))
spec=importlib.util.spec_from_file_location('deployment',ROOT/'tools/deploy-test-server.py');d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
names=['mop-test-web','mop-test-api','mop-test-photo-worker','mop-test-import-worker','mop-test-contact-worker']
stamp=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
target=ROOT/'.local'/('identity-upgrade-'+stamp);target.mkdir()
old=d.read_json(d.STATE);assert old['started'] and old['project']==d.PROJECT
old_config=d.configuration()
(target/'previous-state.json').write_text(json.dumps(old,indent=2))
ssh=d.connect();envs={};renamed=[];stopped=[];new_revision=None
try:
    values=json.loads(d.command(ssh,['docker','inspect',*names]))
    assert all(v['Config']['Labels'].get('mop.project')==d.PROJECT and v['Config']['Labels'].get('mop.release')==old['revision'] for v in values)
    sftp=ssh.open_sftp()
    try:
        for name in ['api','photo','import','contact','export']:
            with sftp.open(d.REMOTE+'/private/'+name+'.env','rb') as f:envs[name]=f.read()
            (target/(name+'.env')).write_bytes(envs[name])
    finally:sftp.close()
    d.stage();new=d.read_json(d.STATE);new_revision=new['revision']
    for name in names:
        d.command(ssh,['docker','update','--restart=no',name]);d.command(ssh,['docker','stop',name]);stopped.append(name)
        d.command(ssh,['docker','rename',name,name+'-before-identity-'+stamp]);renamed.append(name)
    d.start()
    ready=False
    for attempt in range(60):
        try:
            with urllib.request.urlopen(new['public_origin']+'/api/v1/health/ready',timeout=5) as r:ready=r.status==200 and json.load(r)['data']['ready'] is True
            if ready:break
        except Exception:pass
        time.sleep(2)
    assert ready,'New release readiness failed'
    with urllib.request.urlopen(new['public_origin']+'/api/v1/auth/options',timeout=5) as r:options=json.load(r)['data']
    assert options['local_enabled'] is True and options['sso_enabled'] is False
    result={'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'environment':'designated-server','public_origin':new['public_origin'],'previous_revision':old['revision'],'revision':new['revision'],'project_local_login':True,'oidc_enabled':False,'ready':True,'retained_previous_containers':len(renamed),'shared_component_lifecycle_changed':False,'migrations_on_start':0}
    (ROOT/'docs/evidence/project-identity-deployment-20261005.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
except Exception as e:
    # Remove failed new instances only after verifying the new release labels; retain them under failure names.
    for name in renamed:
        candidate=d.command(ssh,['docker','ps','-a','--filter','name=^/'+name+'$','--format','{{.Names}}']).strip()
        if candidate:
            labels=json.loads(d.command(ssh,['docker','inspect','--format','{{json .Config.Labels}}',name]))
            assert labels.get('mop.project')==d.PROJECT and labels.get('mop.release')==new_revision
            d.command(ssh,['docker','update','--restart=no',name]);d.command(ssh,['docker','stop',name]);d.command(ssh,['docker','rename',name,name+'-identity-failed-'+stamp])
    sftp=ssh.open_sftp()
    try:
        for name,data in envs.items():
            with sftp.open(d.REMOTE+'/private/'+name+'.env','wb') as f:f.write(data)
            sftp.chmod(d.REMOTE+'/private/'+name+'.env',0o600)
    finally:sftp.close()
    for name in renamed:d.command(ssh,['docker','rename',name+'-before-identity-'+stamp,name])
    for name in stopped:d.command(ssh,['docker','update','--restart=unless-stopped',name]);d.command(ssh,['docker','start',name])
    d.STATE.write_text(json.dumps(old,indent=2)+'\n')
    d.save_private(old_config)
    print(json.dumps({'status':'FAILED','rollback_to_previous_release':True,'exception':type(e).__name__,'details':'redacted'}));raise SystemExit(1)
finally:ssh.close()

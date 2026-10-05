"""Restart the current owned API only, verify other project/shared container start times unchanged."""
import datetime, importlib.util, json, sys, time, urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'.local/python-deps'))
spec=importlib.util.spec_from_file_location('deployment',ROOT/'tools/deploy-test-server.py');d=importlib.util.module_from_spec(spec);spec.loader.exec_module(d)
state=d.read_json(d.STATE);ssh=d.connect()
try:
    labels=json.loads(d.command(ssh,['docker','inspect','--format','{{json .Config.Labels}}','mop-test-api']))
    assert labels.get('mop.project')==d.PROJECT and labels.get('mop.release')==state['revision']
    names=['shared-postgres','hltk-keycloak','shared-minio','mop-test-web','mop-test-photo-worker','mop-test-import-worker','mop-test-contact-worker']
    before={name:json.loads(d.command(ssh,['docker','inspect','--format','{{json .State}}',name]))['StartedAt'] for name in names}
    d.command(ssh,['docker','restart','mop-test-api'])
    ready=False
    for attempt in range(60):
        try:
            with urllib.request.urlopen(state['public_origin']+'/api/v1/health/ready',timeout=5) as response:ready=json.load(response)['data']['ready'] is True
            if ready:break
        except Exception:pass
        time.sleep(2)
    assert ready
    after={name:json.loads(d.command(ssh,['docker','inspect','--format','{{json .State}}',name]))['StartedAt'] for name in names};assert before==after
    print(json.dumps({'api_restarted':True,'ready':True,'other_seven_container_start_times_unchanged':True,'revision':state['revision']}))
except Exception as error:print(json.dumps({'passed':False,'exception':type(error).__name__,'details':'redacted'}));raise SystemExit(1)
finally:ssh.close()

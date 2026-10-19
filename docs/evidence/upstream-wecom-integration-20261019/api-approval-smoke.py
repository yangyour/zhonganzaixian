#!/usr/bin/env python3
import os,sys,pathlib,subprocess,secrets,uuid,hashlib,json,time,urllib.request,urllib.error,http.cookiejar,zipfile,tempfile,shutil
base=pathlib.Path('/tmp/dot-test-services-upstream-e739027');jar=pathlib.Path(sys.argv[1]);private=pathlib.Path(tempfile.mkdtemp(prefix='api-',dir=base));private.chmod(0o700)
java='/tmp/dot-tools/jdk/bin/java';env={k:v for k,v in os.environ.items() if not k.startswith(('MOP_MIGRATION_','MOP_WECOM_'))}
env.update(MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN='http://127.0.0.1:28080',SERVER_PORT='28080',SERVER_ADDRESS='127.0.0.1')
report={'environment':'dot-upstream-e739027-fresh-pg','api_jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'real_postgresql':True,'project_local_auth':True,'external_services_configured':False,'checks':[]};api=None
try:
 with zipfile.ZipFile(jar) as z:
  for n in z.namelist():
   if n.startswith('BOOT-INF/classes/') or n.startswith('BOOT-INF/lib/'):z.extract(n,private)
 cp=str(private/'BOOT-INF/classes')+':'+str(private/'BOOT-INF/lib/*')
 users=[{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+seed).encode()).hexdigest())),'username':'dot_'+seed.lower(),'password':secrets.token_urlsafe(24)} for seed in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']]
 r=subprocess.run([java,'-cp',cp,'cn.mop.api.ProjectIdentityBootstrap','--enable-existing-test-accounts'],input=json.dumps(users),env=env,text=True,capture_output=True)
 if r.returncode:raise RuntimeError('existing_synthetic_local_identity_bootstrap_failed')
 report['identity_bootstrap']=json.loads(r.stdout.strip().splitlines()[-1]);log=(base/'approval-api-startup.log').open('w')
 api=subprocess.Popen([java,'-jar',str(jar)],env=env,stdout=log,stderr=log)
 origin='http://127.0.0.1:28080';cookies=http.cookiejar.CookieJar();http=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
 def request(path,method='GET',body=None,csrf=None):
  headers={'Accept':'application/json','Origin':origin}
  if body is not None:headers.update({'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
  if csrf:headers['X-CSRF-Token']=csrf
  req=urllib.request.Request(origin+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
  try:r=http.open(req,timeout=10)
  except urllib.error.HTTPError as e:r=e
  data=json.loads(r.read());return r.status,data
 for i in range(90):
  if api.poll() is not None:raise RuntimeError('api_exited_during_startup')
  try:
   status,body=request('/health/live')
   if status==200:break
  except (OSError,ValueError):pass
  time.sleep(1)
 else:raise RuntimeError('api_startup_timeout')
 for path in ['/health/live','/health/ready','/auth/options']:
  status,data=request(path);report['checks'].append({'path':path,'status':status,'body':data});assert status==200,(path,status)
 status,data=request('/session');report['checks'].append({'path':'/session','case':'unauthenticated','status':status});assert status==401
 status,data=request('/auth/csrf');assert status==200;csrf=data['data']['csrf_token']
 status,data=request('/auth/local/login','POST',{'username':users[0]['username'],'password':users[0]['password']},csrf);report['checks'].append({'path':'/auth/local/login','case':'valid_ephemeral_synthetic_account','status':status});assert status==200;assert data['data']['identity_source']=='project_local'
 for path in ['/session','/identity/policy','/assets']:
  status,data=request(path);report['checks'].append({'path':path,'case':'authenticated','status':status});assert status==200,(path,status)
 readiness=next(c['body']['data'] for c in report['checks'] if c['path']=='/health/ready')
 assert readiness['change_notice_approvals']['schema_access_ready'] is True
 options=next(c['body']['data'] for c in report['checks'] if c['path']=='/auth/options')
 assert options['wecom_enabled'] is False and options['wecom_start_url'] is None
 sys.path.insert(0,str(base));import approval_http_checks
 approval_http_checks.run(origin,users,report)
 report['result']='PASS'
except Exception as e:
 report['result']='FAIL';report['failure_type']=type(e).__name__;report['failure_stage']=str(e) if isinstance(e,(RuntimeError,AssertionError)) else 'details_withheld'
finally:
 if api is not None:
  api.terminate()
  try:api.wait(timeout=20)
  except subprocess.TimeoutExpired:api.kill();api.wait()
 shutil.rmtree(private)
 report['api_cleanup']='complete';(base/'approval-api-http-evidence.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'api_smoke':report['result'],'checks':len(report['checks']),'failure_stage':report.get('failure_stage'),'api_cleanup':'complete'}),flush=True)
 sys.exit(0 if report['result']=='PASS' else 1)

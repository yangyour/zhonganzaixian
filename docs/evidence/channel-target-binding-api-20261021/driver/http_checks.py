import os,pathlib,subprocess,json,secrets,uuid,hashlib,time,urllib.request,urllib.error,http.cookiejar
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java';TENANT='55000000-0000-4000-8000-000000000001'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 api_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 origin='http://127.0.0.1:28129'
 api_env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',MOP_TICKET_LIFECYCLE_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN=origin,SERVER_PORT='28129',SERVER_ADDRESS='127.0.0.1')
 email_config=private/'synthetic-email.json';email_config.write_text(json.dumps({'host':'smtp.example.test','port':465,'username':'target-lookup@example.test','sender':'target-lookup@example.test','sender_name':'Synthetic only','test_recipient':'recipient@example.test','allowed_recipients':['recipient@example.test']}));email_config.chmod(0o600)
 wecom_config=private/'synthetic-wecom.json';wecom_config.write_text(json.dumps({'corp_id':'wwsynthetic12345678','agent_id':1000001,'secret':secrets.token_urlsafe(24)}));wecom_config.chmod(0o600)
 api_env.update(MOP_EMAIL_ENABLED='true',MOP_EMAIL_CONFIG_FILE=str(email_config),MOP_EMAIL_PASSWORD=secrets.token_urlsafe(24),MOP_EMAIL_TENANT_ID=TENANT,MOP_WECOM_ENABLED='true',MOP_WECOM_CONFIG_FILE=str(wecom_config),MOP_WECOM_TENANT_ID=TENANT)
 users={name:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+name).encode()).hexdigest())),'username':'dot_target_'+name.lower(),'password':secrets.token_urlsafe(24)} for name in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
 cp=pathlib.Path('/tmp/za-target-binding-harness/classpath.txt').read_text()
 r=subprocess.run([JAVA,'-cp',cp,'cn.mop.api.ProjectIdentityBootstrap','--enable-existing-test-accounts'],env=api_env,input=json.dumps(list(users.values())),capture_output=True,text=True)
 assert r.returncode==0,'Synthetic local account bootstrap failed'
 report={'scope':'REAL_LOOPBACK_HTTP_SYNTHETIC_ACCOUNTS','checks':[],'result':'FAILED','provider_sends':False};exchanges=[]
 class NoRedirect(urllib.request.HTTPRedirectHandler):
  def redirect_request(self,*args,**kwargs):return None
 class Client:
  def __init__(self,name):self.name=name;self.csrf=None;self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
  def call(self,label,path,method='GET',body=None,status=200,key=None,record=True):
   h={'Accept':'application/json','Origin':origin}
   if body is not None:h.update({'Content-Type':'application/json','Idempotency-Key':key or str(uuid.uuid4())})
   if self.csrf:h['X-CSRF-Token']=self.csrf
   request=urllib.request.Request(origin+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=h,method=method)
   try:response=self.http.open(request,timeout=15)
   except urllib.error.HTTPError as e:response=e
   with response:actual=response.status;raw=response.read();cache=response.headers.get('Cache-Control')
   data=json.loads(raw)
   if record:report['checks'].append({'case':label,'actor':self.name,'status':actual,'expected':status,'code':(data.get('error') or {}).get('code'),'cache_control':cache})
   assert actual==status,(label,actual,status,(data.get('error') or {}).get('code'))
   return data.get('data',data)
  def login(self):
   self.csrf=self.call(self.name+'_csrf','/auth/csrf')['csrf_token'];result=self.call(self.name+'_login','/auth/local/login','POST',{'username':users[self.name]['username'],'password':users[self.name]['password']});self.csrf=result['csrf_token']
   result=self.call(self.name+'_switch','/session/tenant','POST',{'tenant_id':TENANT});self.csrf=result['csrf_token'];return result
 api=None
 try:
  with (private/'api.log').open('w') as log:api=subprocess.Popen([JAVA,'-jar',str(jar)],env=api_env,stdout=log,stderr=log)
  anon=Client('anonymous')
  for _ in range(70):
   if api.poll() is not None:raise AssertionError('API startup exited')
   try:anon.call('startup','/health/live',record=False);break
   except (OSError,ValueError):time.sleep(.5)
  else:raise AssertionError('API startup timeout')
  ids={row.split('|')[0]:row.split('|')[1] for row in sql("SELECT subject,id FROM public.mop_user WHERE identity_source='synthetic-target-lookup' AND subject IN ('201','202','203')").splitlines()}
  admin=Client('padmin');admin.login();viewer=Client('viewerA');viewer.login();captures=[]
  for channel in ['email','wecom']:
   path='/'+channel+'/bindings/'
   anon.call(channel+'_anonymous',path+ids['201'],status=401);viewer.call(channel+'_viewer_forbidden',path+ids['201'],status=403)
   before=snapshot();overview=admin.call(channel+'_bounded_overview','/'+channel+'/bindings');assert len(overview['items'])==200 and all(row['user_id']!=ids['201'] for row in overview['items'])
   for label,subject in [('beyond_200','201'),('unbound','202')]:
    result=admin.call(channel+'_'+label,path+ids[subject]);assert result['tenant_id']==TENANT and result['user_id']==ids[subject] and result['source']=='postgresql'
    if subject=='201':assert result['binding']['user_id']==ids[subject] and result['binding']['version']==1
    else:assert result['binding'] is None
    captures.append({'case':channel+'_'+label,'channel':channel,'response':result})
   assert snapshot()==before,'Target binding GET changed database rows'
   before=snapshot();admin.call(channel+'_foreign_target',path+ids['203'],status=404);admin.call(channel+'_missing_target',path+'99000000-0000-4000-8000-000000000099',status=404);admin.call(channel+'_malformed_target',path+'invalid',status=400);admin.call(channel+'_query_override',path+ids['201']+'?tenant_id='+TENANT,status=400)
   after=snapshot();assert all(before[k]==after.get(k) for k in before if k!='mop_audit'),'Rejected reads changed non-audit rows'
  other='55000000-0000-4000-8000-000000000002';switched=admin.call('switch_other_tenant','/session/tenant','POST',{'tenant_id':other});admin.csrf=switched['csrf_token']
  for channel in ['email','wecom']:admin.call(channel+'_foreign_channel_tenant','/'+channel+'/bindings/'+ids['201'],status=404)
  write('synthetic-http-capture.json',{'captures':captures})
  report['result']='PASS';report['successful_target_reads_fingerprints_unchanged']=True;report['rejected_reads_non_audit_tables_unchanged']=True;report['real_bindings_changed']=0

 finally:
  if api and api.poll() is None:
   api.terminate()
   try:api.wait(timeout=15)
   except subprocess.TimeoutExpired:api.kill();api.wait(timeout=5)
  report['api_stopped']=api is None or api.poll() is not None;write('http-validation.json',report)
 print('CHANNEL_TARGET_BINDING_HTTP_PASS checks='+str(len(report['checks'])),flush=True)

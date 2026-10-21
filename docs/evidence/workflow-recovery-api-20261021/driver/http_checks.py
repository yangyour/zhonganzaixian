import os,pathlib,subprocess,json,secrets,uuid,hashlib,time,urllib.request,urllib.error,http.cookiejar,urllib.parse
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java';TENANT='55000000-0000-4000-8000-000000000001'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 api_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 origin='http://127.0.0.1:28129'
 api_env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',MOP_TICKET_LIFECYCLE_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN=origin,SERVER_PORT='28129',SERVER_ADDRESS='127.0.0.1')
 users={name:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+name).encode()).hexdigest())),'username':'dot_workflowrecover_'+name.lower(),'password':secrets.token_urlsafe(24)} for name in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
 cp=pathlib.Path('/tmp/za-workflow-recovery-harness/classpath.txt').read_text()
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
  admin=Client('padmin');admin_session=admin.login();customer=Client('tadmin');customer_session=customer.login();viewer=Client('viewerA');viewer.login();captures=[]
  opened=admin.call('open_fixture_candidates','/ticket-workflows?state=OPEN&limit=25')['items'];target=next(row for row in opened if row['title']=='SYNTHETIC dashboard workflow 6');ticket=target['ticket_id']
  viewer.call('unrelated_customer_cannot_read_target','/tickets/'+ticket+'/workflow',status=404)
  def step(action,client,fields):
   before=client.call(action+'_before','/tickets/'+ticket+'/workflow');body={**fields,'expected_version':before['version']};key=str(uuid.uuid4())
   receipt=client.call(action+'_command','/tickets/'+ticket+'/'+action,'POST',body,key=key);assert receipt['version']==before['version']+1
   replay=client.call(action+'_same_key_replay','/tickets/'+ticket+'/'+action,'POST',body,key=key);assert replay==receipt
   current=client.call(action+'_independent_readback','/tickets/'+ticket+'/workflow');assert current['version']==receipt['version'] and current['state']==receipt['state']
   auth=customer_session if client.name=='tadmin' else admin_session
   captures.append({'case':action,'context':{'tenantId':TENANT,'actorId':users[client.name]['platform_user_id'],'permissionVersion':auth['permission_version'],'ticketId':ticket,'customer':client.name=='tadmin'},'action':action,'fields':fields,'before':before,'receipt':receipt,'current':current})
  step('accept',admin,{})
  step('start',admin,{})
  step('resolve',admin,{'note':'SYNTHETIC resolution with more than twenty Unicode characters; no real customer work.'})
  viewer.call('unrelated_customer_cannot_confirm','/tickets/'+ticket+'/close','POST',{'expected_version':4,'note':'SYNTHETIC unrelated customer'},status=404)
  step('close',customer,{'note':'SYNTHETIC explicit customer confirmation'})
  step('reopen',customer,{'note':'SYNTHETIC explicit unresolved reason'})
  step('reassign',admin,{'assignee_id':users['msp']['platform_user_id'],'note':'SYNTHETIC reassignment reason','reason_code':'SYNTHETIC_REASSIGN','retain_previous_as_collaborator':False})
  step('cancel',admin,{'note':'SYNTHETIC cancellation after receipt tests'})
  before=snapshot();admin.call('stale_original_version','/tickets/'+ticket+'/start','POST',{'expected_version':1},status=409);viewer.call('customer_internal_action_forbidden','/tickets/'+ticket+'/accept','POST',{'expected_version':8},status=403)
  after=snapshot();assert all(before[k]==after.get(k) for k in before if k!='mop_audit'),'Rejected writes changed non-audit rows'
  report['synthetic_confirmed_commands']=7;report['same_key_replays_equal']=True;report['real_customer_commands']=0
  histories=[]
  for client,auth in [(admin,admin_session),(customer,customer_session)]:
   history=client.call(client.name+'_history','/tickets/'+ticket+'/events?limit=25');assert len(history['items'])==8 and not history['has_more']
   if client.name=='tadmin':assert all(e['actor_id'] is None and e['details'] is None for e in history['items'])
   histories.append({'case':client.name+'_history','context':{'tenantId':TENANT,'actorId':users[client.name]['platform_user_id'],'permissionVersion':auth['permission_version'],'ticketId':ticket,'customer':client.name=='tadmin'},'workflow':client.call(client.name+'_final_workflow','/tickets/'+ticket+'/workflow'),'response':history})
  write('synthetic-http-capture.json',{'captures':captures,'history_captures':histories})
  report['result']='PASS';report['rejected_reads_non_audit_tables_unchanged']=True;report['real_workflow_commands']=0

 finally:
  if api and api.poll() is None:
   api.terminate()
   try:api.wait(timeout=15)
   except subprocess.TimeoutExpired:api.kill();api.wait(timeout=5)
  report['api_stopped']=api is None or api.poll() is not None;write('http-validation.json',report)
 print('WORKFLOW_RECOVERY_HTTP_PASS checks='+str(len(report['checks'])),flush=True)

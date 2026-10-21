import os,pathlib,subprocess,json,secrets,uuid,hashlib,time,urllib.request,urllib.error,http.cookiejar,urllib.parse
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java';TENANT='55000000-0000-4000-8000-000000000001'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 api_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 origin='http://127.0.0.1:28129'
 api_env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',MOP_TICKET_LIFECYCLE_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN=origin,SERVER_PORT='28129',SERVER_ADDRESS='127.0.0.1')
 users={name:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+name).encode()).hexdigest())),'username':'dot_workflowdash_'+name.lower(),'password':secrets.token_urlsafe(24)} for name in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
 cp=pathlib.Path('/tmp/za-dashboard-workflows-harness/classpath.txt').read_text()
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
  path='/ticket-workflows?state=OPEN&limit=5';anon.call('anonymous_workflows',path,status=401)
  admin=Client('padmin');admin_session=admin.login();viewer=Client('viewerA');viewer_session=viewer.login();captures=[];before=snapshot()
  def capture(label,client,state,cursor=None):
   url='/ticket-workflows?state='+state+'&limit=5'+('&cursor='+urllib.parse.quote(cursor,safe='') if cursor else '')
   result=client.call(label,url);assert all(row['state']==state and row['tenant_id']==TENANT and row['version']>0 for row in result['items'])
   assert all(row['scope']==('REPORTER_ONLY' if client.name=='viewerA' else 'CURRENT_TENANT_INTERNAL') for row in result['items'])
   if client.name=='viewerA':assert all(row['assignee_id'] is None and row['collaborator_ids']==[] and row['sla_references']==[] for row in result['items'])
   auth=viewer_session if client.name=='viewerA' else admin_session
   captures.append({'case':label,'filter':state,'context':{'tenantId':TENANT,'actorId':users[client.name]['platform_user_id'],'permissionVersion':auth['permission_version'],'role':'viewer' if client.name=='viewerA' else 'padmin'},'response':result});return result
  first=capture('admin_open_first',admin,'OPEN');assert len(first['items'])==5 and first['has_more'];second=capture('admin_open_second',admin,'OPEN',first['next_cursor']);assert len(second['items'])==2 and not second['has_more']
  own=capture('customer_open_first',viewer,'OPEN');assert len(own['items'])==5 and own['has_more'];own_second=capture('customer_open_second',viewer,'OPEN',own['next_cursor']);assert len(own_second['items'])==1 and not own_second['has_more']
  for state in ['ACCEPTED','IN_PROGRESS','RESOLVED','CLOSED','CANCELLED']:assert len(capture('admin_'+state.lower(),admin,state)['items'])==1
  capture('customer_resolved_redacted',viewer,'RESOLVED');assert snapshot()==before,'Workflow GET changed database rows'
  before=snapshot();admin.call('wrong_state','/ticket-workflows?state=INVALID&limit=5',status=400);admin.call('tenant_override',path+'&tenant_id='+TENANT,status=400);viewer.call('internal_assignee_filter_forbidden',path+'&assignee='+users['padmin']['platform_user_id'],status=403);viewer.call('another_actor_cursor_rejected',path+'&cursor='+urllib.parse.quote(first['next_cursor'],safe=''),status=400);admin.call('another_filter_cursor_rejected','/ticket-workflows?state=CLOSED&limit=5&cursor='+urllib.parse.quote(first['next_cursor'],safe=''),status=400)
  after=snapshot();assert all(before[k]==after.get(k) for k in before if k!='mop_audit'),'Rejected reads changed non-audit rows'
  other='55000000-0000-4000-8000-000000000002';switched=admin.call('switch_authorized_tenant_b','/session/tenant','POST',{'tenant_id':other});admin.csrf=switched['csrf_token'];before=snapshot();empty=admin.call('tenant_b_empty_open',path);assert empty['items']==[] and not empty['has_more'];assert snapshot()==before;captures.append({'case':'tenant_b_empty','filter':'OPEN','context':{'tenantId':other,'actorId':users['padmin']['platform_user_id'],'permissionVersion':switched['permission_version'],'role':'padmin'},'response':empty})
  write('synthetic-http-capture.json',{'captures':captures})
  report['result']='PASS';report['successful_workflow_reads_fingerprints_unchanged']=True;report['rejected_reads_non_audit_tables_unchanged']=True;report['real_workflow_commands']=0

 finally:
  if api and api.poll() is None:
   api.terminate()
   try:api.wait(timeout=15)
   except subprocess.TimeoutExpired:api.kill();api.wait(timeout=5)
  report['api_stopped']=api is None or api.poll() is not None;write('http-validation.json',report)
 print('DASHBOARD_WORKFLOWS_HTTP_PASS checks='+str(len(report['checks'])),flush=True)

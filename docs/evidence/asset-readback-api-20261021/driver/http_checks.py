import os,pathlib,subprocess,json,secrets,uuid,hashlib,time,urllib.request,urllib.error,http.cookiejar,urllib.parse
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java';TENANT='55000000-0000-4000-8000-000000000001'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 api_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 origin='http://127.0.0.1:28129'
 api_env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',MOP_TICKET_LIFECYCLE_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN=origin,SERVER_PORT='28129',SERVER_ADDRESS='127.0.0.1')
 users={name:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+name).encode()).hexdigest())),'username':'dot_assetread_'+name.lower(),'password':secrets.token_urlsafe(24)} for name in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
 cp=pathlib.Path('/tmp/za-assets-read-harness/classpath.txt').read_text()
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
  anon.call('anonymous_assets','/assets?page_size=10',status=401)
  admin=Client('padmin');admin.login();viewer=Client('viewerA');viewer.login();captures=[];before=snapshot()
  first=viewer.call('tenant_a_first_page','/assets?page_size=10');assert len(first['items'])==10 and first['has_more'];assert all(row['tenant_id']==TENANT for row in first['items']);captures.append({'case':'tenant_a_first','kind':'list','tenant_id':TENANT,'page_size':10,'response':first})
  second=viewer.call('tenant_a_next_page','/assets?page_size=10&cursor='+urllib.parse.quote(first['next_cursor'],safe=''));assert len(second['items'])==8 and not second['has_more'];assert len({row['id'] for row in first['items']+second['items']})==18;captures.append({'case':'tenant_a_second','kind':'list','tenant_id':TENANT,'page_size':10,'response':second})
  asset=first['items'][0];detail=viewer.call('exact_asset_detail','/assets/'+asset['id']);assert detail==asset;captures.append({'case':'exact_detail','kind':'detail','tenant_id':TENANT,'asset_id':asset['id'],'response':detail})
  empty=viewer.call('no_matching_assets','/assets?page_size=15&q=synthetic-NO-MATCH');assert empty['items']==[] and not empty['has_more'];captures.append({'case':'empty','kind':'list','tenant_id':TENANT,'page_size':15,'response':empty})
  assert snapshot()==before,'Successful asset reads changed rows'
  other='55000000-0000-4000-8000-000000000002';switched=admin.call('switch_authorized_tenant_b','/session/tenant','POST',{'tenant_id':other});admin.csrf=switched['csrf_token'];before=snapshot();foreign=admin.call('tenant_b_first_page','/assets?page_size=10');assert all(row['tenant_id']==other for row in foreign['items']);assert snapshot()==before;captures.append({'case':'tenant_b_first','kind':'list','tenant_id':other,'page_size':10,'response':foreign})
  before=snapshot();viewer.call('foreign_detail_forbidden','/assets/'+foreign['items'][0]['id'],status=404);viewer.call('tenant_query_override','/assets?tenant_id='+other,status=400);viewer.call('invalid_page_size','/assets?page_size=11',status=400);admin.call('foreign_cursor_rejected','/assets?page_size=10&cursor='+urllib.parse.quote(first['next_cursor'],safe=''),status=400)
  after=snapshot();assert all(before[k]==after.get(k) for k in before if k!='mop_audit'),'Rejected reads changed non-audit rows'
  write('synthetic-http-capture.json',{'captures':captures})
  report['result']='PASS';report['successful_asset_reads_fingerprints_unchanged']=True;report['rejected_reads_non_audit_tables_unchanged']=True;report['real_asset_writes']=0

 finally:
  if api and api.poll() is None:
   api.terminate()
   try:api.wait(timeout=15)
   except subprocess.TimeoutExpired:api.kill();api.wait(timeout=5)
  report['api_stopped']=api is None or api.poll() is not None;write('http-validation.json',report)
 print('ASSET_READBACK_HTTP_PASS checks='+str(len(report['checks'])),flush=True)

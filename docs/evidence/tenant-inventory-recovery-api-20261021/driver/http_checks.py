import os,pathlib,subprocess,json,secrets,uuid,hashlib,time,urllib.request,urllib.error,http.cookiejar,urllib.parse
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java';TENANT='55000000-0000-4000-8000-000000000001'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 api_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 origin='http://127.0.0.1:28129'
 api_env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',MOP_TICKET_LIFECYCLE_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN=origin,SERVER_PORT='28129',SERVER_ADDRESS='127.0.0.1')
 users={name:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+name).encode()).hexdigest())),'username':'dot_tenantread_'+name.lower(),'password':secrets.token_urlsafe(24)} for name in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
 cp=pathlib.Path('/tmp/za-tenant-recovery-harness/classpath.txt').read_text()
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
  actor=users['padmin']['platform_user_id']
  sql("INSERT INTO public.mop_tenant(id,name) SELECT md5('synthetic-inventory-tenant-'||i)::uuid,'Synthetic inventory tenant '||i FROM generate_series(1,18) i; INSERT INTO public.mop_membership(tenant_id,user_id,role_code) SELECT id,'"+actor+"'::uuid,'padmin' FROM public.mop_tenant WHERE name LIKE 'Synthetic inventory tenant %' AND name<>'Synthetic inventory tenant 18';")
  path='/tenants/inventory?page_size=15';anon.call('anonymous_inventory',path,status=401)
  admin=Client('padmin');admin.login();viewer=Client('viewerA');viewer.login();viewer.call('viewer_inventory_forbidden',path,status=403);captures=[];before=snapshot()
  first=admin.call('inventory_first_page',path);assert len(first['items'])==15 and first['has_more'];captures.append({'case':'first','after_id':None,'response':first})
  second_path=path+'&after_id='+first['next_after_id'];second=admin.call('inventory_next_page',second_path);assert len(second['items'])==4 and not second['has_more'];captures.append({'case':'second','after_id':first['next_after_id'],'response':second})
  all_rows=first['items']+second['items'];assert len({row['id'] for row in all_rows})==19 and all(row['name']!='Synthetic inventory tenant 18' for row in all_rows)
  assert all(row['asset_quota'] is None and row['isolation_mode'] is None and isinstance(row['registered_asset_count'],str) for row in all_rows)
  again=admin.call('repeat_same_page',second_path);assert again==second;assert snapshot()==before,'Inventory GET changed database rows'
  revoked=next(row['id'] for row in second['items'] if row['name'].startswith('Synthetic inventory tenant '))
  sql("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id='"+revoked+"'::uuid AND user_id='"+actor+"'::uuid;")
  before=snapshot();fresh=admin.call('target_membership_revoked_without_selected_tenant_version_change',second_path);assert all(row['id']!=revoked for row in fresh['items']) and len(fresh['items'])==3;captures.append({'case':'revoked_target','after_id':first['next_after_id'],'response':fresh});assert snapshot()==before
  before=snapshot();admin.call('tenant_query_override',path+'&tenant_id='+TENANT,status=400);admin.call('invalid_boundary',path+'&after_id=invalid',status=400);admin.call('invalid_page_size','/tenants/inventory?page_size=11',status=400)
  after=snapshot();assert all(before[k]==after.get(k) for k in before if k!='mop_audit'),'Rejected reads changed non-audit rows'
  write('synthetic-http-capture.json',{'captures':captures})
  report['result']='PASS';report['successful_inventory_reads_fingerprints_unchanged']=True;report['rejected_reads_non_audit_tables_unchanged']=True;report['real_tenant_changes']=0

 finally:
  if api and api.poll() is None:
   api.terminate()
   try:api.wait(timeout=15)
   except subprocess.TimeoutExpired:api.kill();api.wait(timeout=5)
  report['api_stopped']=api is None or api.poll() is not None;write('http-validation.json',report)
 print('TENANT_INVENTORY_RECOVERY_HTTP_PASS checks='+str(len(report['checks'])),flush=True)

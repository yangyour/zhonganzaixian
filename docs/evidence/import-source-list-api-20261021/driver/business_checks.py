"""Real PostgreSQL and loopback HTTP checks; all principals and passwords are disposable synthetic data."""
import os,pathlib,subprocess,json,re,xml.etree.ElementTree as ET,secrets,uuid,hashlib,time,urllib.request,urllib.error,http.cookiejar
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java'
TENANT='55000000-0000-4000-8000-000000000001';OTHER='55000000-0000-4000-8000-000000000002'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 env=env.copy();env.update(MOP_RUN_REMOTE_TESTS='true')
 classes=['PostgresAssetImportSourceListIntegrationTest']
 expected={n:len(re.findall(r'@Test\b',(root/'server/persistence/src/test/java/cn/mop/persistence'/f'{n}.java').read_text())) for n in classes}
 cp=str(root/'server/persistence/target/test-classes')+':'+pathlib.Path('/tmp/za-import-source-list-harness/classpath.txt').read_text()
 args=[JAVA,'-jar','/tmp/za-java/junit.jar','execute','--disable-ansi-colors','--class-path',cp,'--reports-dir',str(evidence/'junit')]
 for name in classes:args+=['--select-class','cn.mop.persistence.'+name]
 with (evidence/'real-pg-tests.log').open('w') as log:r=subprocess.run(args,env=env,stdout=log,stderr=log)
 counts={};cases=[]
 for path in (evidence/'junit').glob('TEST-*.xml'):
  for case in ET.parse(path).getroot().findall('testcase'):
   name=case.get('classname','').split('.')[-1]
   if name not in classes:continue
   row=counts.setdefault(name,{'tests':0,'failures':0,'errors':0,'skipped':0});row['tests']+=1
   for tag,key in [('failure','failures'),('error','errors'),('skipped','skipped')]:row[key]+=int(case.find(tag) is not None)
   cases.append({'class':name,'test':case.get('name'),'passed':all(case.find(t) is None for t in ['failure','error','skipped'])})
 write('real-pg-tests.json',{'exit':r.returncode,'expected':expected,'results':counts,'cases':cases})
 assert r.returncode==0,'Real PostgreSQL business tests failed'
 for name,count in expected.items():assert counts.get(name)=={'tests':count,'failures':0,'errors':0,'skipped':0},name+' missing/skipped/failing tests'
 print('REAL_PG_BUSINESS_PASS '+json.dumps(counts),flush=True)
 # Schema must remain valid after actual repository transactions.
 cli=[JAVA,'-cp',pathlib.Path('/tmp/za-import-source-list-harness/classpath.txt').read_text(),'cn.mop.persistence.TestSchemaMigration','--check']
 r=subprocess.run(cli,env=env,capture_output=True,text=True);write('post-business-schema-check.json',{'exit':r.returncode,'summary':r.stdout.strip()});assert r.returncode==0
 api_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 origin='http://127.0.0.1:28129'
 api_env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',MOP_TICKET_LIFECYCLE_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN=origin,SERVER_PORT='28129',SERVER_ADDRESS='127.0.0.1')
 users={name:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+name).encode()).hexdigest())),'username':'dot_cmdb_'+name.lower(),'password':secrets.token_urlsafe(24)} for name in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
 cp=pathlib.Path('/tmp/za-import-source-list-harness/classpath.txt').read_text()
 r=subprocess.run([JAVA,'-cp',cp,'cn.mop.api.ProjectIdentityBootstrap','--enable-existing-test-accounts'],env=api_env,input=json.dumps(list(users.values())),capture_output=True,text=True)
 assert r.returncode==0,'Synthetic local account bootstrap failed'
 report={'scope':'REAL_LOOPBACK_API_SYNTHETIC_IDENTITIES','checks':[],'asset_writes_during_check':False,'synthetic_foreign_asset_fixture_inserts':0,'provider_sends':False,'result':'FAILED'}
 class NoRedirect(urllib.request.HTTPRedirectHandler):
  def redirect_request(self,*args,**kwargs):return None
 class Client:
  def __init__(self,name):self.name=name;self.csrf=None;self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
  def call(self,label,path,method='GET',body=None,status=200,headers=None,record=True):
   h={'Accept':'application/json','Origin':origin};h.update(headers or {})
   if body is not None:h.update({'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
   if self.csrf:h['X-CSRF-Token']=self.csrf
   request=urllib.request.Request(origin+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=h,method=method)
   try:response=self.http.open(request,timeout=10)
   except urllib.error.HTTPError as e:response=e
   with response:actual=response.status;raw=response.read();cache=response.headers.get('Cache-Control');referrer=response.headers.get('Referrer-Policy')
   data=json.loads(raw)
   if record:report['checks'].append({'case':label,'actor':self.name,'status':actual,'expected':status,'code':(data.get('error') or {}).get('code'),'cache_control':cache,'referrer_policy':referrer})
   assert actual==status,(label,actual,status)
   return data.get('data',data)
  def login(self):
   self.csrf=self.call(self.name+'_csrf','/auth/csrf')['csrf_token'];result=self.call(self.name+'_login','/auth/local/login','POST',{'username':users[self.name]['username'],'password':users[self.name]['password']});self.csrf=result['csrf_token'];return result
  def switch(self,tenant):
   result=self.call(self.name+'_switch_'+tenant[-1],'/session/tenant','POST',{'tenant_id':tenant});self.csrf=result['csrf_token'];return result
 api=None
 try:
  with (private/'api.log').open('w') as log:api=subprocess.Popen([JAVA,'-jar',str(jar)],env=api_env,stdout=log,stderr=log)
  anon=Client('anonymous')
  for _ in range(60):
   if api.poll() is not None:raise AssertionError('API startup exited')
   try:anon.call('startup','/health/live',record=False);break
   except (OSError,ValueError):time.sleep(.5)
  else:raise AssertionError('API startup timeout')
  anon.call('anonymous_denied','/cmdb/type-catalog',status=401)
  clients={name:Client(name) for name in ['padmin','msp','viewerA','viewerB']}
  for client in clients.values():client.login()
  msp=clients['msp'];msp.switch(TENANT)
  seed_cp=cp+':'+str(root/'server/import-parser/target/mop-import-parser-0.1.0-SNAPSHOT.jar')
  seeded=subprocess.run([JAVA,'-cp',seed_cp,'cn.mop.persistence.SeedCmdbSource'],env=env,capture_output=True,text=True)
  write('source-seed-process.json',{'exit':seeded.returncode,'store_adapter':'ISOLATED_MEMORY_TEST_DOUBLE','parser':'REAL_IMPORT_FILE_PARSER'})
  if seeded.returncode:raise AssertionError('Synthetic source setup failed: '+seeded.stderr[-1600:])
  source=json.loads(next(line.split('=',1)[1] for line in seeded.stdout.splitlines() if line.startswith('SEED_RESULT=')));write('source-seed.json',source)
  before=snapshot();first=msp.call('source_list_first','/assets/import-sources?limit=20')
  assert len(first['items'])==20 and first['scope']=='CREATOR_ONLY' and first['created_by']==users['msp']['platform_user_id'] and first['can_import'] is False
  second=msp.call('source_list_second','/assets/import-sources?limit=20&cursor='+first['next_cursor'])
  assert len(second['items'])==3 and second['next_cursor'] is None
  ids=[item['id'] for item in first['items']+second['items']];assert len(set(ids))==23 and source['source_id'] in ids
  assert snapshot()==before,'Successful owner-list GET changed database rows'
  write('source-list-readonly.json',{'result':'PASS','tables':len(before),'before':before,'after':snapshot(),'page_sizes':[20,3],'unique_sources':23})
  source_data=msp.call('source_to_resume','/assets/import-sources/'+source['source_id'])
  policy=msp.call('source_policy','/assets/import-policy')
  (root/'fixtures/import-source-list-http-synthetic-20261021.json').write_text(json.dumps({'scope':'DISPOSABLE_SYNTHETIC_ONLY','first':first,'second':second,'source':source_data,'policy':policy},ensure_ascii=False,indent=2)+'\n')
  clients['padmin'].call('other_owner_cursor_rejected','/assets/import-sources?cursor='+first['next_cursor'],status=404)
  clients['viewerA'].call('viewer_list_denied','/assets/import-sources',status=403)
  anon.call('anonymous_list_denied','/assets/import-sources',status=401)
  for suffix in ['?limit=0','?limit=51','?limit=01','?limit=1&limit=2','?cursor=bad','?tenant_id='+OTHER,'?created_by='+users['padmin']['platform_user_id']]:msp.call('invalid_list_query_'+suffix.split('=')[0],'/assets/import-sources'+suffix,status=400)
  msp.switch(OTHER);foreign=msp.call('other_tenant_list_empty','/assets/import-sources');assert foreign['items']==[] and foreign['next_cursor'] is None
  msp.call('other_tenant_cursor_rejected','/assets/import-sources?cursor='+first['next_cursor'],status=404);msp.switch(TENANT)
  uid=users['msp']['platform_user_id'];sql("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id='"+TENANT+"'::uuid AND user_id='"+uid+"'::uuid")
  msp.call('revoked_owner_list_denied','/assets/import-sources',status=401)
  report.update(result='PASS',readonly_table_fingerprints_unchanged=True,synthetic_accounts=6,temporary_command_key_only=True)
 finally:
  if api is not None:
   api.terminate()
   try:api.wait(timeout=15)
   except subprocess.TimeoutExpired:api.kill();api.wait()
  report['api_stopped']=api is None or api.poll() is not None;write('real-cmdb-http.json',report)
 print('REAL_CMDB_HTTP_PASS checks='+str(len(report['checks'])),flush=True)

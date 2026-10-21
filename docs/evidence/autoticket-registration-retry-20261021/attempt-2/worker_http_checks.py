import os,pathlib,subprocess,json,secrets,uuid,hashlib,time,urllib.request,urllib.error,http.cookiejar
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java'
TENANT='55000000-0000-4000-8000-000000000001'
def run_http(root,evidence,env,sql,snapshot,private,jar,seed):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 cp=pathlib.Path('/tmp/za-retry-harness/classpath-before-history.txt').read_text()
 legacy_process=subprocess.run([JAVA,'-cp',cp,'cn.mop.persistence.SeedLegacyMonitoringTicket'],env=env,capture_output=True,text=True,timeout=30)
 write('legacy-seed-process.json',{'exit':legacy_process.returncode,'producer':'PRE_HISTORY_FIX_RUNTIME'})
 assert legacy_process.returncode==0,'Legacy producer seed failed'
 legacy=json.loads(next(line.split('=',1)[1] for line in legacy_process.stdout.splitlines() if line.startswith('SEED_RESULT=')));write('legacy-seed.json',legacy)
 for key in ['legacy_ticket_id','legacy_operation_id','customer_ticket_id']:assert str(uuid.UUID(legacy[key]))==legacy[key]
 api_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 origin='http://127.0.0.1:28129'
 api_env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',MOP_TICKET_LIFECYCLE_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN=origin,SERVER_PORT='28129',SERVER_ADDRESS='127.0.0.1')
 users={name:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+name).encode()).hexdigest())),'username':'dot_cmdb_'+name.lower(),'password':secrets.token_urlsafe(24)} for name in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
 cp=pathlib.Path('/tmp/za-retry-harness/classpath.txt').read_text()
 r=subprocess.run([JAVA,'-cp',cp,'cn.mop.api.ProjectIdentityBootstrap','--enable-existing-test-accounts'],env=api_env,input=json.dumps(list(users.values())),capture_output=True,text=True)
 assert r.returncode==0,'Synthetic local account bootstrap failed'
 report={'scope':'REAL_LOOPBACK_API_SYNTHETIC_IDENTITIES','checks':[],'asset_writes':False,'provider_sends':False,'result':'FAILED'}
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
  ticket_id=sql("SELECT ticket_id::text FROM public.mop_autoticket_attempt WHERE id='"+seed['attempt_id']+"'::uuid");assert str(uuid.UUID(ticket_id))==ticket_id
  anon.call('anonymous_ticket','/tickets/'+ticket_id,status=401)
  client=Client('msp');client.login();client.switch(TENANT);before=snapshot()
  current=client.call('new_monitoring_ticket','/tickets/'+ticket_id)
  historical=client.call('legacy_monitoring_ticket','/tickets/'+legacy['legacy_ticket_id'])
  customer=client.call('customer_control_ticket','/tickets/'+legacy['customer_ticket_id'])
  old_operation=client.call('legacy_operation','/operations/'+legacy['legacy_operation_id'])
  new_operation=client.call('new_operation','/operations/'+current['operation_id'])
  assert current['contact_phone']=='' and current['history'][0]['action']=='MONITORING_REGISTER'
  assert historical['contact_phone']=='' and historical['history'][0]['action']=='LEGACY_MONITORING_REGISTER'
  assert customer['contact_phone']=='13800138000' and customer['history'][0]['action']=='REGISTER'
  for operation in [old_operation,new_operation]:assert operation['status']=='PENDING' and set(operation['error'])=={'code','message','retryable','question_ids'} and operation['error']['retryable'] is False
  assert snapshot()==before,'Read compatibility changed persisted rows'
  raw=json.loads(sql("SELECT jsonb_build_object('raw_history',h.payload,'historical_placeholder_retained',t.contact_phone='13800138000') FROM public.mop_ticket t JOIN public.mop_ticket_history h ON h.tenant_id=t.tenant_id AND h.ticket_id=t.id AND h.version=1 WHERE t.id='"+legacy['legacy_ticket_id']+"'::uuid"));assert raw=={'raw_history':{'action':'AUTO_CREATE_FROM_MONITORING_EVENT'},'historical_placeholder_retained':True}
  version=int(sql("SELECT permission_version FROM public.mop_membership WHERE tenant_id='"+TENANT+"'::uuid AND user_id='"+seed['actor_id']+"'::uuid"))
  readback={'context':{'tenantId':TENANT,'actorId':seed['actor_id'],'role':'msp','permissionVersion':version},'cases':[{'data':current,'expected_action':'MONITORING_REGISTER','expected_unknown_contact':True},{'data':historical,'expected_action':'LEGACY_MONITORING_REGISTER','expected_unknown_contact':True},{'data':customer,'expected_action':'REGISTER','expected_unknown_contact':False}]}
  input_file=private/'readback-input.json';input_file.write_text(json.dumps(readback));input_file.chmod(0o600)
  checked=subprocess.run(['/tmp/za-toolchain/node_modules/.bin/node','/tmp/za-retry-harness/readback.mjs',str(input_file)],env=env,capture_output=True,text=True,timeout=20)
  write('typescript-reader-process.json',{'exit':checked.returncode,'scope':'ACTUAL_HTTP_RESPONSES_PRODUCTION_TS_READER'})
  assert checked.returncode==0,'Actual TypeScript reader rejected HTTP projection'
  write('typescript-reader-validation.json',json.loads(checked.stdout));report.update(result='PASS',read_rows_unchanged=True,legacy_raw_unchanged=True,customer_control_contact_preserved=True,unknown_contact_is_blank=True)
 finally:
  if api and api.poll() is None:
   api.terminate()
   try:api.wait(timeout=15)
   except subprocess.TimeoutExpired:api.kill();api.wait(timeout=5)
  report['api_stopped']=api is None or api.poll() is not None
  write('ticket-readback-http.json',report)
 print('TICKET_READBACK_HTTP_PASS checks='+str(len(report['checks'])),flush=True)

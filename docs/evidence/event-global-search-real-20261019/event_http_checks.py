"""Real loopback API checks against a new synthetic PostgreSQL database.
Passwords/cookies remain only in memory. No external providers or devices are used.
"""
import datetime,hashlib,http.cookiejar,json,os,pathlib,secrets,subprocess,time,urllib.error,urllib.parse,urllib.request,uuid,traceback
BASE=pathlib.Path(__file__).parent
TENANT='55000000-0000-4000-8000-000000000001';OTHER='55000000-0000-4000-8000-000000000002'
FIELDS={'id','tenant_id','rule_code','rule_name','version','severity','monitor_state','handling_state','occurrence_count','last_occurred_at','resource_kind'}
def run(jar,private,sql,snapshot):
 java='/tmp/dot-tools/jdk/bin/java';env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','JAVA_HOME','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN='http://127.0.0.1:28081',SERVER_PORT='28081',SERVER_ADDRESS='127.0.0.1')
 report={'result':'ABORTED','environment':'dot-event-search-fresh-pg','api_jar_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'real_postgresql':True,'project_local_auth':True,'external_services_configured':False,'checks':[],'assertions':{}};api=None;origin='http://127.0.0.1:28081';cases=set()
 class NoRedirect(urllib.request.HTTPRedirectHandler):
  def redirect_request(self,*args,**kwargs):return None
 class Client:
  def __init__(self,name):self.name=name;self.csrf=None;self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
  def call(self,case,path,method='GET',body=None,status=200,csrf=True,record=True):
   if record:assert case not in cases;cases.add(case)
   headers={'Accept':'application/json','Origin':origin}
   if body is not None:headers.update({'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
   if csrf and self.csrf:headers['X-CSRF-Token']=self.csrf
   req=urllib.request.Request(origin+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
   try:response=self.http.open(req,timeout=15)
   except urllib.error.HTTPError as e:response=e
   with response:actual=response.status;content=response.read();cache=response.headers.get('Cache-Control')
   data=json.loads(content);code=(data.get('error') or {}).get('code')
   if record:report['checks'].append({'case':case,'actor':self.name,'path':path,'method':method,'status':actual,'expected_status':status,'error_code':code,'cache_control':cache})
   assert actual==status,(case,actual,status,code)
   return data.get('data') if actual<400 else data.get('error')
  def login(self,user,label=None):
   tag=label or self.name;self.csrf=self.call(tag+'_csrf','/auth/csrf')['csrf_token'];session=self.call(tag+'_login','/auth/local/login','POST',{'username':user['username'],'password':user['password']});self.csrf=session['csrf_token'];assert session['identity_source']=='project_local';return session
  def switch(self,case,tenant):
   result=self.call(case,'/session/tenant','POST',{'tenant_id':tenant});self.csrf=result['csrf_token'];assert result['tenant']['id']==tenant
  def search(self,case,q,authorized=True,tenant=TENANT):
   d=self.call(case,'/search?q='+urllib.parse.quote(q,safe=''));assert d['tenant_id']==tenant and d['query']==q
   assert d['scope']=='CURRENT_TENANT_AUTHORIZED_GROUPED_SEARCH' and d['coverage']=='PARTIAL'
   assert d['query_semantics']=='LITERAL_UNICODE_CASE_INSENSITIVE' and d['snapshot_consistency']=='ONE_READONLY_TRANSACTION'
   groups=d['groups'];assert [g['kind'] for g in groups]==['assets','events','tickets','automation_scripts'];e=groups[1]
   assert [g['limit'] for g in groups]==[5,4,3,3]
   assert groups[0]['status']=='AVAILABLE' and groups[2]['status']=='AVAILABLE'
   assert groups[3]['status']=='NOT_IMPLEMENTED' and all(groups[3][k] is None for k in ['returned','has_more','items'])
   assert e['limit']==4
   if authorized:
    assert e['status']=='AVAILABLE' and e['reason'] is None and e['source_scope']=='CURRENT_TENANT_INTERNAL'
    assert e['matched_fields']==['rule_name','rule_code'] and e['returned']==len(e['items'])<=4
    assert not e['has_more'] or e['returned']==4
    assert d['coverage_reason']=='AUTOMATION_SOURCE_NOT_IMPLEMENTED'
    for item in e['items']:
     assert set(item)==FIELDS and item['resource_kind']=='MONITORING_EVENT' and item['tenant_id']==tenant
     assert item['version']>=1 and item['occurrence_count']>=1
   else:
    assert e['status']=='NOT_AUTHORIZED' and e['reason']=='INTERNAL_EVENT_READ_REQUIRED'
    assert all(e[k] is None for k in ['returned','has_more','items','source_scope','matched_fields'])
    assert d['coverage_reason']=='AUTOMATION_SOURCE_NOT_IMPLEMENTED_AND_EVENT_NOT_AUTHORIZED'
   report['checks'][-1]['event_result']={'status':e['status'],'returned':e['returned'],'has_more':e['has_more'],'ids':None if e['items'] is None else [i['id'] for i in e['items']]}
   return e
 try:
  users={seed:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+seed).encode()).hexdigest())),'username':'dot_es_'+seed.lower(),'password':secrets.token_urlsafe(24)} for seed in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
  cp=str(private/'BOOT-INF/classes')+':'+str(private/'BOOT-INF/lib/*');r=subprocess.run([java,'-cp',cp,'cn.mop.api.ProjectIdentityBootstrap','--enable-existing-test-accounts'],input=json.dumps(list(users.values())),env=env,text=True,capture_output=True)
  assert r.returncode==0,'synthetic project identity bootstrap failed';report['identity_bootstrap']=json.loads(r.stdout.strip().splitlines()[-1])
  q=lambda x:"'"+str(x).replace("'","''")+"'"
  assets={t:sql('SELECT id::text FROM mop_asset WHERE tenant_id='+q(t)+'::uuid ORDER BY id LIMIT 1') for t in [TENANT,OTHER]}
  marker='ESHTTP-'+uuid.uuid4().hex[:10];privateMarker='PRIVATE-'+uuid.uuid4().hex;now=datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
  fixtures=[]
  def event(tenant,code,name,offset,private_text=privateMarker):
   ident=str(uuid.uuid4());at=(now+datetime.timedelta(seconds=offset)).isoformat();actor=users['padmin']['platform_user_id']
   columns=['id','tenant_id','asset_id','version','rule_code','rule_name','severity','severity_rank','fingerprint','occurrence_count','monitor_state','handling_state','suppression_state','notification_empty_state','problem_at','last_occurred_at','root_cause_note','updated_by','updated_at']
   values=[q(ident)+'::uuid',q(tenant)+'::uuid',q(assets[tenant])+'::uuid','1',q(code),q(name),q('WARNING'),'2',q(private_text+'-'+ident),'1',q('PROBLEM'),q('NEW'),q('NONE'),q('NORMAL_DISPATCH_ELIGIBLE'),q(at)+'::timestamptz',q(at)+'::timestamptz',q(private_text),q(actor)+'::uuid',q(at)+'::timestamptz']
   sql('INSERT INTO mop_monitoring_event('+','.join(columns)+') VALUES('+','.join(values)+');')
   fixtures.append({'id':ident,'tenant':tenant,'code':code,'name':name,'offset':offset});return ident
  cap=[event(TENANT,marker+'-CAP-'+str(i),marker+'-Caps 名称 '+str(i),-60) for i in range(6)]
  foreign=event(OTHER,marker+'-CAP-FOREIGN',marker+'-Caps foreign',10)
  old=event(TENANT,marker+'-ARCHIVE',marker+'-Archive beyond latest25',-864000)
  for i in range(30):event(TENANT,marker+'-FILL-'+str(i),marker+'-Filler '+str(i),-30+i)
  literal_name=marker+'-事件Ä%_\\';literal=event(TENANT,marker+'-LITERAL',literal_name,-90)
  decoy=event(TENANT,marker+'-DECOY',marker+'-事件ÄXY\\',-91)
  code_literal=marker+'-规则Ä%_\\';bycode=event(TENANT,code_literal,marker+'-Code literal title',-92)
  exact=[event(TENANT,marker+'-EXACT-'+str(i),marker+'-ExactFour '+str(i),-100-i) for i in range(4)]
  sql('INSERT INTO mop_monitoring_event_member(id,tenant_id,event_id,sequence_no,source_event_id,source_phase,occurred_at,merged_within_window,recorded_at) VALUES('+q(str(uuid.uuid4()))+'::uuid,'+q(TENANT)+'::uuid,'+q(old)+'::uuid,1,'+q(privateMarker)+' ,\'PROBLEM\',now(),false,now());')
  sql('INSERT INTO mop_event_noise_record(id,tenant_id,event_id,layer,detail,recorded_at) VALUES('+q(str(uuid.uuid4()))+'::uuid,'+q(TENANT)+'::uuid,'+q(old)+'::uuid,\'RAW_INTAKE\','+q(privateMarker)+',now());')
  report['synthetic_fixtures']={'events':len(fixtures),'tenants':2,'fixed_timestamp_tie_count':6,'newer_rows_than_archive':sum(f['tenant']==TENANT and f['id']!=old for f in fixtures),'private_sentinels':['fingerprint','root_cause_note','source_event_id','noise_detail'],'source':'disposable synthetic SQL fixtures; no device polling'}
  with (BASE/'api-startup.log').open('w') as log:api=subprocess.Popen([java,'-jar',str(jar)],env=env,stdout=log,stderr=log)
  anon=Client('anonymous')
  for i in range(90):
   if api.poll() is not None:raise RuntimeError('api exited during startup')
   try:anon.call('startup','/health/live',record=False);break
   except (OSError,ValueError):time.sleep(1)
  else:raise RuntimeError('api startup timeout')
  for endpoint in ['/health/live','/health/ready']:anon.call(endpoint,endpoint)
  options=anon.call('project_auth_options','/auth/options');assert options['wecom_enabled'] is False and options['wecom_start_url'] is None
  anon.call('unauthenticated_search','/search?q='+marker,status=401)
  anon.call('unauthenticated_detail','/events/'+old,status=401)
  anon.call('login_without_csrf','/auth/local/login','POST',{'username':users['msp']['username'],'password':users['msp']['password']},status=403,csrf=False)
  clients={name:Client(name) for name in users if name!='reviewer'}
  for name,c in clients.items():c.login(users[name])
  admin=clients['padmin'];msp=clients['msp'];viewer=clients['viewerA'];tadmin=clients['tadmin'];viewerB=clients['viewerB']
  admin.call('tenant_switch_without_csrf','/session/tenant','POST',{'tenant_id':OTHER},status=403,csrf=False)
  before=snapshot()
  for c in [admin,msp]:
   e=c.search(c.name+'_cap_overflow',marker+'-CAP');assert [i['id'] for i in e['items']]==sorted(cap,reverse=True)[:4] and e['has_more'] is True
   e2=c.search(c.name+'_deterministic_repeat',marker+'-CAP');assert e['items']==e2['items']
   e=c.search(c.name+'_exact_four',marker+'-ExactFour');assert [i['id'] for i in e['items']]==exact and e['has_more'] is False
   e=c.search(c.name+'_literal_name',literal_name.lower());assert [i['id'] for i in e['items']]==[literal]
   e=c.search(c.name+'_literal_rule_code',code_literal.lower());assert [i['id'] for i in e['items']]==[bycode]
   for label,needle in [('percent',marker+'-事件Ä%'),('underscore',marker+'-事件Ä%_'),('backslash',literal_name)]:
    e=c.search(c.name+'_literal_'+label,needle);assert [i['id'] for i in e['items']]==[literal]
   for label,needle in [('injection',marker+"' OR '1'='1"),('private_fields',privateMarker),('missing',marker+'-ABSENT')]:
    e=c.search(c.name+'_'+label,needle);assert e['items']==[] and e['has_more'] is False
   e=c.search(c.name+'_archive_search',marker+'-ARCHIVE');assert [i['id'] for i in e['items']]==[old]
   workspace=c.call(c.name+'_latest25','/events');assert len(workspace['events'])==25 and old not in [e['id'] for e in workspace['events']]
   detail=c.call(c.name+'_direct_archive_detail','/events/'+old);assert detail['event']['id']==old and detail['event']['tenant_id']==TENANT and detail['event']['root_cause_note']==privateMarker
  for c in [viewer,tadmin]:c.search(c.name+'_event_group_authoritatively_denied',marker,authorized=False)
  viewerB.search('foreign_customer_event_group_denied',marker,authorized=False,tenant=OTHER)
  after=snapshot();assert before==after;report['assertions']['successful_search_and_detail_read_only']={'all_tables_compared':len(before),'changed_tables':[],'before':before,'after':after}
  for c in [viewer,tadmin,viewerB]:c.call(c.name+'_direct_event_detail_denied','/events/'+old,status=403)
  admin.call('cross_tenant_event_detail_is_404','/events/'+foreign,status=404)
  admin.switch('authorized_switch_to_tenant_b',OTHER);e=admin.search('tenant_b_only',marker+'-CAP',tenant=OTHER);assert [i['id'] for i in e['items']]==[foreign] and e['has_more'] is False
  admin.call('tenant_b_cannot_open_tenant_a_detail','/events/'+old,status=404);d=admin.call('tenant_b_direct_own_detail','/events/'+foreign);assert d['event']['tenant_id']==OTHER
  admin.switch('switch_back_to_tenant_a',TENANT)
  for label,query in [('duplicate','q=x&q=y'),('limit_override','q=x&limit=100'),('tenant_override','q=x&tenant_id='+OTHER),('empty','q='),('blank','q=%20%20'),('control','q=%0A'),('long','q='+('x'*101)),('missing','')]:admin.call('query_reject_'+label,'/search'+('?' if query else '')+query,status=400)
  admin.call('detail_reject_query_override','/events/'+old+'?tenant_id='+OTHER,status=400)
  role=str(uuid.uuid4());msp_id=users['msp']['platform_user_id']
  sql('INSERT INTO mop_project_role(id,tenant_id,name,base_role,permissions,created_by) VALUES('+q(role)+'::uuid,'+q(TENANT)+'::uuid,'+q(marker+' custom role')+",'msp',ARRAY['search:read','assets:read','tickets:read'],"+q(users['padmin']['platform_user_id'])+'::uuid); INSERT INTO mop_member_role_assignment(tenant_id,user_id,role_id) VALUES('+q(TENANT)+'::uuid,'+q(msp_id)+'::uuid,'+q(role)+'::uuid);')
  e=msp.search('current_custom_role_grants',marker+'-CAP');assert e['returned']==4
  sql('UPDATE mop_project_role SET permissions=ARRAY[\'search:read\',\'assets:read\'],version=version+1,updated_at=now() WHERE id='+q(role)+'::uuid;')
  msp.call('same_session_tickets_read_revoked_search','/search?q='+marker,status=403);msp.call('same_session_tickets_read_revoked_detail','/events/'+old,status=403)
  sql('UPDATE mop_project_role SET permissions=ARRAY[\'search:read\',\'assets:read\',\'tickets:read\'],version=version+1,updated_at=now() WHERE id='+q(role)+'::uuid;')
  msp.search('same_session_permission_restored',marker+'-CAP')
  sql('UPDATE mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id='+q(TENANT)+'::uuid AND user_id='+q(msp_id)+'::uuid;')
  msp.call('same_session_membership_revoked_search','/search?q='+marker,status=401);msp.call('same_session_membership_revoked_detail','/events/'+old,status=401)
  sql('UPDATE mop_membership SET active=true,permission_version=permission_version+1 WHERE tenant_id='+q(TENANT)+'::uuid AND user_id='+q(msp_id)+'::uuid; DELETE FROM mop_member_role_assignment WHERE tenant_id='+q(TENANT)+'::uuid AND user_id='+q(msp_id)+'::uuid; DELETE FROM mop_project_role WHERE id='+q(role)+'::uuid;')
  report['assertions'].update({'minimal_event_projection_exact_fields':sorted(FIELDS),'tenant_isolation':True,'custom_current_capabilities_and_immediate_revocation':True,'direct_detail_outside_latest25':True,'literal_unicode_case_insensitive_and_no_wildcard_expansion':True,'four_hit_limit_overflow_and_tie_order':True,'no_search_private_field_match_or_projection':True,'denied_request_audits_not_in_readonly_window':True})
  assert len(report['checks'])==72,('unexpected_http_check_count',len(report['checks']))
  report['result']='PASS'
 except Exception as e:
  report['result']='FAIL';report['failure_type']=type(e).__name__;report['failure_stage']=str(e) if isinstance(e,(RuntimeError,AssertionError)) else 'details_withheld';report['failure_frames']=[{'file':pathlib.Path(x.filename).name,'function':x.name,'line':x.lineno} for x in traceback.extract_tb(e.__traceback__)]
 finally:
  if api is not None:
   api.terminate()
   try:api.wait(timeout=20)
   except subprocess.TimeoutExpired:api.kill();api.wait()
  report['api_cleanup']='complete';report['http_check_count']=len(report['checks']);(BASE/'real-http.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'http':report['result'],'checks':len(report['checks']),'failure_stage':report.get('failure_stage'),'api_cleanup':'complete'}),flush=True)
 if report['result']!='PASS':raise RuntimeError('real HTTP validation failed; see redacted evidence')

"""Real loopback PDF reads over disposable synthetic reports. No providers or publication."""
import os,pathlib,subprocess,json,re,xml.etree.ElementTree as ET,secrets,uuid,hashlib,time,urllib.request,urllib.error,http.cookiejar,base64
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java';A='55000000-0000-4000-8000-000000000001';B='55000000-0000-4000-8000-000000000002'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 caps=['inspection_templates:read','inspection_templates:write','inspection_worksheets:read','inspection_worksheets:write','inspection_source_collections:read','inspection_source_collections:write','inspection_quarterly_reports:read','inspection_quarterly_reports:write']
 sql("UPDATE public.mop_project_role r SET permissions=ARRAY(SELECT DISTINCT p FROM unnest(r.permissions||ARRAY["+','.join("'"+c+"'" for c in caps)+"]::text[]) p ORDER BY p),version=version+1,updated_at=statement_timestamp() WHERE builtin AND base_role='padmin';")
 env=env.copy();env['MOP_RUN_REMOTE_TESTS']='true';cp=pathlib.Path('/tmp/za-report-pdf-harness/classpath.txt').read_text();testcp=str(root/'server/persistence/target/test-classes')+':'+cp
 classes=['PostgresInspectionQuarterlyReportIntegrationTest'];expected={n:len(re.findall(r'@Test\b',(root/'server/persistence/src/test/java/cn/mop/persistence'/f'{n}.java').read_text())) for n in classes}
 args=[JAVA,'-jar','/tmp/za-java/junit.jar','execute','--disable-ansi-colors','--class-path',testcp,'--reports-dir',str(evidence/'junit')]
 for n in classes:args+=['--select-class','cn.mop.persistence.'+n]
 with (evidence/'pg-tests.log').open('w') as log:p=subprocess.run(args,env=env,stdout=log,stderr=log)
 counts={};cases=[]
 for path in (evidence/'junit').glob('TEST-*.xml'):
  for case in ET.parse(path).getroot().findall('testcase'):
   n=case.get('classname','').split('.')[-1]
   if n not in classes:continue
   row=counts.setdefault(n,{'tests':0,'failures':0,'errors':0,'skipped':0});row['tests']+=1
   for tag in ['failure','error','skipped']:row[{'failure':'failures','error':'errors','skipped':'skipped'}[tag]]+=int(case.find(tag) is not None)
   cases.append({'class':n,'test':case.get('name'),'passed':all(case.find(t) is None for t in ['failure','error','skipped'])})
 write('real-pg-tests.json',{'exit':p.returncode,'expected':expected,'results':counts,'cases':cases});assert p.returncode==0,'PG report tests failed'
 for n,c in expected.items():assert counts[n]=={'tests':c,'failures':0,'errors':0,'skipped':0}
 print('REPORT_PG_PASS '+json.dumps(counts),flush=True)
 seeded=subprocess.run([JAVA,'-cp',testcp+':/tmp/za-java/junit.jar','cn.mop.persistence.SeedPdfReport'],env=env,capture_output=True,text=True);assert seeded.returncode==0,'Synthetic report seed failed: '+seeded.stderr[-1000:]
 seed=json.loads(next(l.split('=',1)[1] for l in seeded.stdout.splitlines() if l.startswith('SEED_REPORT=')));write('seed.json',seed)
 origin='http://127.0.0.1:28129';users={n:{'platform_user_id':str(uuid.UUID(hashlib.md5(('mop-fixture-user-'+n).encode()).hexdigest())),'username':'dot_pdf_'+n.lower(),'password':secrets.token_urlsafe(24)} for n in ['padmin','msp','tadmin','viewerA','viewerB','reviewer']}
 api_env={k:v for k,v in env.items() if k in {'PATH','HOME','TMPDIR','LANG','LC_ALL','LD_LIBRARY_PATH','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','MOP_TEST_ENVIRONMENT_NAME'}}
 api_env.update(MOP_EMAIL_ENABLED='false',MOP_EMAIL_DISPATCH_ENABLED='false',MOP_WECOM_ENABLED='false',MOP_WECOM_LOGIN_ENABLED='false',MOP_WECOM_DISPATCH_ENABLED='false',MOP_TICKET_LIFECYCLE_ENABLED='false',SPRING_PROFILES_ACTIVE='remote_test',MOP_AUTH_MODE='project_local',MOP_OIDC_ENABLED='false',MOP_AUTH_COMMAND_KEY=secrets.token_hex(32),MOP_TEST_PUBLIC_ORIGIN=origin,SERVER_PORT='28129',SERVER_ADDRESS='127.0.0.1',MOP_REPORT_PDF_PREVIEW_ENABLED='false')
 boot=subprocess.run([JAVA,'-cp',cp,'cn.mop.api.ProjectIdentityBootstrap','--enable-existing-test-accounts'],env=api_env,input=json.dumps(list(users.values())),text=True,capture_output=True);assert boot.returncode==0,'Synthetic account bootstrap failed'
 report={'result':'FAILED','scope':'LOOPBACK_SYNTHETIC_PDF_ONLY','checks':[],'provider_sends':False,'report_publication':False};api=None
 class NoRedirect(urllib.request.HTTPRedirectHandler):
  def redirect_request(self,*a,**kw):return None
 class Client:
  def __init__(self,name):self.name=name;self.csrf=None;self.http=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
  def raw(self,label,path,method='GET',body=None,status=200,headers=None,record=True):
   h={'Accept':'application/json','Origin':origin};h.update(headers or {})
   if body is not None:h.update({'Content-Type':'application/json','Idempotency-Key':str(uuid.uuid4())})
   if self.csrf:h['X-CSRF-Token']=self.csrf
   req=urllib.request.Request(origin+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=h,method=method)
   try:r=self.http.open(req,timeout=40)
   except urllib.error.HTTPError as e:r=e
   with r:actual=r.status;data=r.read();response_headers=r.headers
   if record:report['checks'].append({'case':label,'actor':self.name,'status':actual,'expected':status,'content_type':response_headers.get('Content-Type'),'cache_control':response_headers.get('Cache-Control')})
   if actual>=400:
    try:
     failure=json.loads(data);report['checks'][-1]['error_code']=failure.get('error',{}).get('code');report['checks'][-1]['error_message']=failure.get('error',{}).get('message')
    except Exception:pass
   assert actual==status,(label,actual,status)
   return data,response_headers
  def call(self,label,path,method='GET',body=None,status=200,headers=None,record=True):
   data,h=self.raw(label,path,method,body,status,headers,record);value=json.loads(data);return value.get('data',value)
  def login(self):self.csrf=self.call(self.name+'_csrf','/auth/csrf')['csrf_token'];self.csrf=self.call(self.name+'_login','/auth/local/login','POST',{'username':users[self.name]['username'],'password':users[self.name]['password']})['csrf_token']
  def switch(self,tenant):self.csrf=self.call(self.name+'_switch_'+tenant[-1],'/session/tenant','POST',{'tenant_id':tenant})['csrf_token']
  def pdf(self,label,item,status=200,match=...,suffix=''):
   h={'Accept':'application/pdf'}
   if match is ...:h['If-Match']='"'+item['document_sha256']+'"'
   elif match is not None:h['If-Match']=match
   return self.raw(label,'/inspection-quarterly-reports/'+item['id']+'/preview.pdf'+suffix,status=status,headers=h)
 def start(name):
  nonlocal api
  with (private/name).open('w') as log:api=subprocess.Popen([JAVA,'-Xmx512m','-jar',str(jar)],env=api_env,stdout=log,stderr=log)
  anonymous=Client('anonymous')
  for _ in range(90):
   if api.poll() is not None:raise AssertionError('API startup exited')
   try:anonymous.call('startup','/health/live',record=False);return anonymous
   except (OSError,ValueError):time.sleep(.5)
  raise AssertionError('API startup timeout')
 def stop():
  nonlocal api
  if api is not None:
   api.terminate()
   try:api.wait(timeout=15)
   except subprocess.TimeoutExpired:api.kill();api.wait()
   api=None
 try:
  anon=start('api-disabled.log');disabled=Client('padmin');disabled.login();disabled.switch(B);raw,h=disabled.pdf('disabled_font_config',seed['selected'],503);assert json.loads(raw)['error']['code']=='REPORT_PDF_UNCONFIGURED';assert h.get_content_type()=='application/json';stop()
  primary=pathlib.Path('/tmp/za-pdf-deps/NotoSansCJKsc-Regular.otf');fallback=pathlib.Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
  api_env.update(MOP_REPORT_PDF_PREVIEW_ENABLED='true',MOP_REPORT_PDF_FONT_PATH=str(primary),MOP_REPORT_PDF_FONT_SHA256=hashlib.sha256(primary.read_bytes()).hexdigest(),MOP_REPORT_PDF_FALLBACK_FONT_PATH=str(fallback),MOP_REPORT_PDF_FALLBACK_FONT_SHA256=hashlib.sha256(fallback.read_bytes()).hexdigest())
  anon=start('api-enabled.log');admin=Client('padmin');admin.login();admin.switch(B);msp=Client('msp');msp.login();msp.switch(B)
  detail=admin.call('fresh_report_detail','/inspection-quarterly-reports/'+seed['selected']['id']);write('fresh-detail.json',detail);before=snapshot();raw,h=admin.pdf('bound_pdf_download',seed['selected']);again,again_h=admin.pdf('repeat_read_no_write',seed['selected']);after=snapshot();assert after==before,'PDF reads changed database rows'
  assert raw.startswith(b'%PDF-') and raw.rstrip().endswith(b'%%EOF') and 100<len(raw)<=8388608
  assert h.get_content_type()=='application/pdf' and h.get('X-MOP-Report-ID')==seed['selected']['id'] and h.get('X-MOP-Tenant-ID')==B and h.get('X-MOP-Document-SHA256')==seed['selected']['document_sha256'] and h.get('X-MOP-Preview-Status')=='DRAFT'
  assert h.get('X-MOP-PDF-SHA256')==hashlib.sha256(raw).hexdigest() and int(h.get('Content-Length'))==len(raw) and 1<=int(h.get('X-MOP-PDF-Pages'))<=200
  assert again_h.get('X-MOP-Document-SHA256')==seed['selected']['document_sha256'] and again_h.get('X-MOP-PDF-SHA256')==hashlib.sha256(again).hexdigest()
  write('pdf-readonly.json',{'result':'PASS','tables':len(before),'before':before,'after':after,'pages':int(h.get('X-MOP-PDF-Pages')),'bytes':len(raw),'pdf_sha256':hashlib.sha256(raw).hexdigest()})
  capture={'scope':'DISPOSABLE_SYNTHETIC_HTTP_ONLY','detail':detail,'headers':{k:h.get(k) for k in ['Content-Type','Content-Disposition','Content-Length','Cache-Control','Referrer-Policy','X-Content-Type-Options','X-MOP-Report-ID','X-MOP-Tenant-ID','X-MOP-Document-SHA256','X-MOP-Preview-Status','X-MOP-PDF-SHA256','X-MOP-PDF-Pages']},'pdf_base64':base64.b64encode(raw).decode()};(root/'fixtures/quarterly-pdf-http-synthetic-20261021.json').write_text(json.dumps(capture,ensure_ascii=False,indent=2)+'\n');(evidence/'selected-http.pdf').write_bytes(raw)
  for label,match,status,code in [('missing_precondition',None,428,'REPORT_PREVIEW_PRECONDITION_REQUIRED'),('wrong_precondition','"'+'0'*64+'"',412,'REPORT_PREVIEW_CHANGED'),('weak_precondition','W/"'+seed['selected']['document_sha256']+'"',400,'VALIDATION_ERROR')]:
   body,headers=admin.pdf(label,seed['selected'],status,match);assert headers.get_content_type()=='application/json' and json.loads(body)['error']['code']==code
  raw_error,error_h=admin.pdf('missing_glyph_no_partial_pdf',seed['missing_glyph'],503);assert json.loads(raw_error)['error']['code']=='REPORT_PDF_RENDER_FAILED' and error_h.get_content_type()=='application/json'
  admin.pdf('query_override_rejected',seed['selected'],400,suffix='?tenant_id='+A);anon.pdf('anonymous_denied',seed['selected'],401);msp.pdf('non_admin_denied',seed['selected'],403)
  admin.switch(A);admin.pdf('foreign_tenant_not_found',seed['selected'],404);admin.switch(B)
  unknown={**seed['selected'],'id':str(uuid.uuid4())};admin.pdf('missing_report_not_found',unknown,404)
  admin.call('no_pdf_post','/inspection-quarterly-reports/'+seed['selected']['id']+'/preview.pdf','POST',{},405)
  uid=users['padmin']['platform_user_id'];sql("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 WHERE tenant_id='"+B+"'::uuid AND user_id='"+uid+"'::uuid");admin.pdf('revoked_current_access_denied',seed['selected'],401)
  report.update(result='PASS',successful_pdf_read_tables_unchanged=len(before),font_config_disabled_checked=True,missing_glyph_refused=True,font_sha256=api_env['MOP_REPORT_PDF_FONT_SHA256'],fallback_sha256=api_env['MOP_REPORT_PDF_FALLBACK_FONT_SHA256'])
 finally:
  stop();report['api_stopped']=api is None
  safe=[]
  for path in private.glob('api-*.log'):
   for line in path.read_text(errors='replace').splitlines():
    if 'PDF preview rejected' in line:safe.append(line.split('PDF preview rejected',1)[1].strip())
  report['safe_renderer_diagnostics']=safe;write('http-validation.json',report)
 print('REPORT_PDF_HTTP_PASS checks='+str(len(report['checks'])),flush=True)

import urllib.request,urllib.error,http.cookiejar,json,uuid

def run(origin,users,report):
 class Client:
  def __init__(self):self.http=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()));self.csrf=None
  def call(self,path,method='GET',body=None,status=200,key=None,send_csrf=True):
   headers={'Accept':'application/json','Origin':origin}
   if body is not None:headers.update({'Content-Type':'application/json','Idempotency-Key':key or str(uuid.uuid4())})
   if self.csrf and send_csrf:headers['X-CSRF-Token']=self.csrf
   req=urllib.request.Request(origin+'/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
   try:r=self.http.open(req,timeout=15)
   except urllib.error.HTTPError as e:r=e
   value=json.loads(r.read());report['checks'].append({'path':path,'method':method,'status':r.status,'expected_status':status,'error_code':value.get('error',{}).get('code')})
   assert r.status==status,(path,r.status,value.get('error',{}).get('code'))
   return value.get('data',value.get('error'))
  def login(self,user):
   self.csrf=self.call('/auth/csrf')['csrf_token'];session=self.call('/auth/local/login','POST',{'username':user['username'],'password':user['password']});self.csrf=session['csrf_token'];return session
 a,b=Client(),Client();sa=a.login(users[0]);sb=b.login(users[5]);tenant=sa['tenant']['id'];assert tenant==sb['tenant']['id']
 assets=a.call('/assets');asset=assets['items'][0]['id'];marker='DOT_DISPOSABLE_SYNTHETIC_HTTP_APPROVAL';policy_key='change_notice.approver_policy'
 draft_body={'expected_version':0,'change_summary':marker,'asset_ids':[asset],'planned_start_at':'2026-10-20T00:00:00Z','planned_end_at':'2026-10-20T01:00:00Z','ticket_id':None}
 draft=a.call('/change-notice-drafts','POST',draft_body);notice=draft['id'];route='/change-notice-drafts/'+notice+'/revisions/1/approval'
 view=a.call(route);assert view['state']=='DRAFT' and 'SUBMIT' in view['allowed_actions']
 a.call(route+'/actions','POST',{'expected_version':1,'action':'SUBMIT','note':None},status=403,send_csrf=False)
 key=str(uuid.uuid4());submit={'expected_version':1,'action':'SUBMIT','note':None};submitted=a.call(route+'/actions','POST',submit,key=key);assert submitted['state']=='IN_REVIEW';assert submitted==a.call(route+'/actions','POST',submit,key=key)
 a.call(route+'/actions','POST',{'expected_version':1,'action':'WITHDRAW','note':None},status=409)
 # Only real HTTP review/publish creates the explicit synthetic policy.
 revision=a.call('/configurations/'+policy_key+'/revisions','POST',{'proposed_value':{'reviewer_user_ids':[users[5]['platform_user_id']]},'reason':marker,'evidence_refs':[{'reference':'document-ref:dot-http-approval','title':marker,'sha256':'a'*64}]})
 rid=revision['id'];a.call('/configuration-revisions/'+rid+'/submit','POST',{'expected_version':1});b.call('/configuration-revisions/'+rid+'/review','POST',{'expected_version':2,'decision':'approve','comment':marker})
 current=a.call('/runtime-configurations/'+policy_key)
 release={'expected_version':current['latest_version'],'expected_definition_hash':current['definition_hash'],'action':'PUBLISH','revision_id':rid,'revision_version':3,'target_release_id':None,'activation':'IMMEDIATE','effective_at':None,'expires_at':None,'reason':marker}
 a.call('/runtime-configurations/'+policy_key+'/releases','POST',release)
 allowed=b.call(route);assert set(allowed['allowed_actions'])=={'APPROVE','REJECT'}
 a.call(route+'/actions','POST',{'expected_version':2,'action':'APPROVE','note':None},status=403)
 approve={'expected_version':2,'action':'APPROVE','note':marker};approve_key=str(uuid.uuid4());approved=b.call(route+'/actions','POST',approve,key=approve_key);assert approved['state']=='APPROVED';assert approved==b.call(route+'/actions','POST',approve,key=approve_key)
 for field in ['effective','device_execution_allowed','alert_suppression_allowed']:assert approved[field] is False
 assert approved['automatic_p3_decision'] is None and approved['content_sha256']==draft['content_sha256']
 history=a.call(route+'/history?page_size=10');assert [x['version'] for x in history['items']]==[3,2]
 older=a.call(route+'/history?page_size=10&before_version=3');assert [x['version'] for x in older['items']]==[2]
 other='55000000-0000-4000-8000-000000000002';b.csrf=b.call('/session/tenant','POST',{'tenant_id':other})['csrf_token'];b.call(route,status=404);b.csrf=b.call('/session/tenant','POST',{'tenant_id':tenant})['csrf_token']
 updated_body=dict(draft_body,expected_version=1,change_summary=marker+' REVISION2');updated=a.call('/change-notice-drafts/'+notice,'PATCH',updated_body);assert updated['version']==2
 assert a.call(route)['state']=='APPROVED';new_view=a.call('/change-notice-drafts/'+notice+'/revisions/2/approval');assert new_view['state']=='DRAFT'
 # Separate receipt verifies rejection and rejection-note validation.
 rejected_draft=a.call('/change-notice-drafts','POST',dict(draft_body,change_summary=marker+' REJECT'));reject_route='/change-notice-drafts/'+rejected_draft['id']+'/revisions/1/approval';a.call(reject_route+'/actions','POST',submit)
 b.call(reject_route+'/actions','POST',{'expected_version':2,'action':'REJECT','note':None},status=400)
 rejected=b.call(reject_route+'/actions','POST',{'expected_version':2,'action':'REJECT','note':'Synthetic test rejection'});assert rejected['state']=='REJECTED'
 # A third receipt verifies submit/withdraw path with no activation.
 wd=a.call('/change-notice-drafts','POST',dict(draft_body,change_summary=marker+' WITHDRAW'));wr='/change-notice-drafts/'+wd['id']+'/revisions/1/approval';a.call(wr+'/actions','POST',submit);withdrawn=a.call(wr+'/actions','POST',{'expected_version':2,'action':'WITHDRAW','note':None});assert withdrawn['state']=='WITHDRAWN'
 report['approval_flow']={'source':'real_http_and_postgresql','two_person_policy':'created_reviewed_published_via_http','approved':True,'rejected':True,'withdrawn':True,'csrf_rejection':True,'self_review_rejection':True,'cas_conflict':True,'idempotent_replay':True,'cross_tenant_404':True,'history_pagination':True,'new_revision_resets_review':True,'all_execution_flags_false':True,'external_notifications_sent':False}

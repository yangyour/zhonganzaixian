from pathlib import Path
import json,os,urllib.request,urllib.parse,urllib.error,http.cookiejar
from html.parser import HTMLParser
root=Path.cwd();private=root/'.local/niko-test/private';runtime=json.loads((private/'runtime.json').read_text());base='http://127.0.0.1:8080';issuer=runtime['environment']['MOP_OIDC_ISSUER'];kc=issuer.split('/realms/')[0];allowed={base,kc}
class LoopbackCookiePolicy(http.cookiejar.DefaultCookiePolicy):
 # Keycloak marks loopback as a trustworthy context. Scope this HTTP test-client
 # exception to the two explicitly owned loopback origins; never clear Secure flags.
 def return_ok_secure(self,cookie,request):
  u=urllib.parse.urlsplit(request.full_url)
  if u.scheme+'://'+u.netloc in allowed and u.hostname=='127.0.0.1':return True
  return super().return_ok_secure(cookie,request)
class Redirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):
  u=urllib.parse.urlsplit(newurl)
  if u.scheme+'://'+u.netloc not in allowed:raise RuntimeError('Unexpected redirect destination')
  return super().redirect_request(req,fp,code,msg,headers,newurl)
class Form(HTMLParser):
 def __init__(self):super().__init__();self.action=None;self.hidden={};self.inside=False
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=='form' and a.get('id')=='kc-form-login':self.action=a.get('action');self.inside=True
  if self.inside and tag=='input' and a.get('type')=='hidden' and a.get('name'):self.hidden[a['name']]=a.get('value','')
 def handle_endtag(self,tag):
  if tag=='form':self.inside=False
out=private/'sessions';out.mkdir(mode=0o700,exist_ok=True);checks=[]
try:
 for user in runtime['test_users']:
  jar=http.cookiejar.CookieJar(policy=LoopbackCookiePolicy());opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),Redirect(),urllib.request.HTTPCookieProcessor(jar))
  with opener.open(base+'/oauth2/authorization/mop',timeout=20) as r:formurl=r.url;body=r.read().decode()
  u=urllib.parse.urlsplit(formurl);q=urllib.parse.parse_qs(u.query)
  assert u.scheme+'://'+u.netloc==kc and u.path=='/realms/mop-test/protocol/openid-connect/auth'
  assert q.get('code_challenge_method')==['S256'] and q.get('state') and q.get('nonce')
  form=Form();form.feed(body);assert form.action
  u=urllib.parse.urlsplit(form.action);assert u.scheme+'://'+u.netloc==kc and u.path.startswith('/realms/mop-test/login-actions/')
  data=dict(form.hidden);data.update(username=user['username'],password=user['password'],credentialId='')
  try:
   with opener.open(urllib.request.Request(form.action,data=urllib.parse.urlencode(data).encode(),headers={'Content-Type':'application/x-www-form-urlencoded'},method='POST'),timeout=20) as r:r.read()
  except urllib.error.HTTPError as e:
   if e.code!=404 or e.url!=base+'/assets':
    error_body=e.read().decode();print(json.dumps({'error_markers':[t for t in ['Cookie not found','Invalid code','expired','HTTPS required','Unexpected error','Restart login','restart login','Invalid username or password'] if t.lower() in error_body.lower()],'cookie_metadata':[{'name':c.name,'domain':c.domain,'path':c.path,'secure':c.secure} for c in jar]}));print(json.dumps({'login_followup_status':e.code,'path':urllib.parse.urlsplit(e.url).path,'auth_error':urllib.parse.parse_qs(urllib.parse.urlsplit(e.url).query).get('auth_error',['none'])[0]}));raise RuntimeError('OIDC redirect did not finish') from None
  with opener.open(base+'/api/v1/session',timeout=20) as r:session=json.load(r)['data']
  expected='padmin' if user['role']=='reviewer' else 'viewer' if user['role'].startswith('viewer') else user['role']
  assert session['user']['role']==expected and session['mode']=='remote_test' and session['identity_source']=='test_oidc'
  cookie=next(c for c in jar if c.name=='MOP_SESSION');assert cookie.has_nonstandard_attr('HttpOnly')
  value={'cookie':'MOP_SESSION='+cookie.value,'user_id':session['user']['id'],'csrf_token':session['csrf_token'],'mode':session['mode'],'environment':'niko-local-test'}
  names=['qa-'+user['role']+'.json']
  if user['role'] in ['viewerA','tadmin','msp','viewerB']:names.append('qa-ticket-'+user['role']+'.json')
  alias={'padmin':'creator','reviewer':'reviewer','viewerA':'viewer'}.get(user['role'])
  if alias:names.append('qa-revision-'+alias+'.json')
  for name in names:
   fd=os.open(out/name,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
   with os.fdopen(fd,'w') as f:json.dump(value,f)
  checks.append({'role':user['role'],'authenticated':True,'pkce':'S256','http_only_session':True})
 print(json.dumps({'environment':'niko-local-test','real_oidc_http_checks':checks,'browser_ui_tested':False,'credentials_output':False}))
except Exception as e:
 print(json.dumps({'status':'FAILED','exception_type':type(e).__name__,'reason':str(e) if str(e) in ['Unexpected redirect destination','OIDC redirect did not finish'] else 'details withheld','completed_roles':[x['role'] for x in checks],'credentials_output':False}));raise SystemExit(1)

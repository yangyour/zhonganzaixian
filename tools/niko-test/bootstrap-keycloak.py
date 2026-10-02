from pathlib import Path
import json,os,urllib.request,urllib.parse,urllib.error
root=Path.cwd(); private=root/'.local/niko-test/private'; cfg=json.loads((private/'bootstrap.json').read_text()); c=cfg['component_credentials'];base='http://127.0.0.1:28110';realm='mop-test'
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def http(method,path,payload=None,token=None,form=False,missing=False):
 h={'Content-Type':'application/x-www-form-urlencoded' if form else 'application/json'}
 if token:h['Authorization']='Bearer '+token
 data=None if payload is None else (urllib.parse.urlencode(payload).encode() if form else json.dumps(payload).encode())
 try:
  with opener.open(urllib.request.Request(base+path,data=data,headers=h,method=method),timeout=20) as r:
   b=r.read();return r.status,json.loads(b) if b else None
 except urllib.error.HTTPError as e:
  if missing and e.code==404:return 404,None
  raise RuntimeError('Local Keycloak request failed HTTP '+str(e.code)+'; private payload omitted') from None
_,result=http('POST','/realms/master/protocol/openid-connect/token',{'client_id':'admin-cli','grant_type':'password','username':'mop-niko-admin','password':c['kc_admin_password']},form=True);token=result['access_token']
status,existing=http('GET','/admin/realms/'+realm,token=token,missing=True)
if status==404:http('POST','/admin/realms',{'realm':realm,'enabled':True,'displayName':'MOP niko isolated test','registrationAllowed':False,'resetPasswordAllowed':False,'sslRequired':'none','attributes':{'mop_project':'niko-local-test','frontendUrl':base}},token)
elif existing.get('attributes',{}).get('mop_project')!='niko-local-test':raise RuntimeError('Unexpected realm owner')
_,clients=http('GET','/admin/realms/'+realm+'/clients?clientId=mop-web',token=token)
if not clients:http('POST','/admin/realms/'+realm+'/clients',{'clientId':'mop-web','name':'MOP niko local test','enabled':True,'publicClient':False,'secret':c['oidc_client_secret'],'standardFlowEnabled':True,'directAccessGrantsEnabled':False,'serviceAccountsEnabled':False,'redirectUris':['http://127.0.0.1:5173/login/oauth2/code/mop','http://127.0.0.1:8080/login/oauth2/code/mop'],'webOrigins':['http://127.0.0.1:5173'],'attributes':{'pkce.code.challenge.method':'S256','post.logout.redirect.uris':'http://127.0.0.1:5173/login##http://127.0.0.1:8080/login'}},token)
for user in cfg['test_users']:
 _,users=http('GET','/admin/realms/'+realm+'/users?exact=true&username='+urllib.parse.quote(user['username']),token=token)
 if not users:
  if user.get('id'):raise RuntimeError('Bound identity disappeared; will not recreate')
  http('POST','/admin/realms/'+realm+'/users',{'username':user['username'],'enabled':True,'emailVerified':True,'firstName':'MOP','lastName':user['role'],'email':user['username']+'@mop-test.invalid','credentials':[{'type':'password','value':user['password'],'temporary':False}]},token)
  _,users=http('GET','/admin/realms/'+realm+'/users?exact=true&username='+urllib.parse.quote(user['username']),token=token)
 if len(users)!=1 or user.get('id') and user['id']!=users[0]['id']:raise RuntimeError('Identity changed')
 user['id']=users[0]['id'];user['identity_subject']=base+'/realms/'+realm+'|'+user['id']
runtime={'environment':{'SPRING_DATASOURCE_URL':'jdbc:postgresql://127.0.0.1:25432/mop_test','SPRING_DATASOURCE_USERNAME':'mop_test_runtime','SPRING_DATASOURCE_PASSWORD':c['pg_runtime_password'],'MOP_MIGRATION_USERNAME':'mop_test_migrator','MOP_MIGRATION_PASSWORD':c['pg_migrator_password'],'MOP_OIDC_ISSUER':base+'/realms/'+realm,'MOP_OIDC_CLIENT_ID':'mop-web','MOP_OIDC_CLIENT_SECRET':c['oidc_client_secret'],'MOP_MODE':'remote_test'},'test_users':cfg['test_users'],'status':'niko_local_test_only_not_original_server'}
for name,value in [('bootstrap.json',cfg),('runtime.json',runtime)]:
 p=private/name
 fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
 with os.fdopen(fd,'w') as f:json.dump(value,f,indent=2)
_,discovery=http('GET','/realms/'+realm+'/.well-known/openid-configuration')
print(json.dumps({'oidc_discovery':discovery['issuer']==base+'/realms/'+realm,'test_user_count':len(cfg['test_users']),'pkce':'S256','password_grant_for_application':False,'credentials_printed':False}))

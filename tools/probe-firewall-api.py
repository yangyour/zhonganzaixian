"""Credential-free AF API entry probe on the one user-approved firewall address.

The optional TLS exception is local to this probe's HTTPS connections; it does
not change shared trust, log in, send credentials, or mutate firewall resources.
"""
import argparse
import datetime
import json
from pathlib import Path
import re
import shlex
from lan_monitoring_zabbix import LanZabbix

REMOTE = r'''
import datetime,json,ssl,urllib.request,urllib.error,sys
p=json.load(sys.stdin)
assert p['host']=='192.169.11.1'
import re
assert re.fullmatch(r'[A-Za-z0-9_-]{1,15}',p['namespace'])
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
def request(path,verify):
 context=ssl.create_default_context()
 if not verify:context.check_hostname=False;context.verify_mode=ssl.CERT_NONE
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect(),urllib.request.HTTPSHandler(context=context))
 try:
  try:r=opener.open(urllib.request.Request('https://'+p['host']+path,headers={'Accept':'application/json','Connection':'close'},method='GET'),timeout=8)
  except urllib.error.HTTPError as e:r=e
  with r:
   raw=r.read(65537)
   if len(raw)>65536:return {'status':'RESPONSE_TOO_LARGE','tls_verified':verify}
   result={'status':'HTTP_RESPONSE','http_status':r.status,'content_type':r.headers.get('Content-Type'),'tls_verified':verify,'provider_codes':[]}
   try:
    body=json.loads(raw)
    for _ in range(3):
     if not isinstance(body,dict) or 'code' not in body:break
     code=body['code'];result['provider_codes'].append(code if type(code) is int else 'INVALID_CODE_TYPE')
     body=body.get('data')
    result['json_object']=isinstance(json.loads(raw),dict)
   except Exception:result['json_object']=False
   return result
 except urllib.error.URLError as e:
  if isinstance(e.reason,ssl.SSLCertVerificationError):return {'status':'TLS_NOT_VERIFIED','verify_code':e.reason.verify_code,'tls_verified':False}
  return {'status':'TRANSPORT_UNAVAILABLE','tls_verified':False}
 except Exception:return {'status':'TRANSPORT_UNAVAILABLE','tls_verified':False}
results=[]
for suffix in ('systemversion','keepalive'):
 path='/api/v1/namespaces/'+p['namespace']+'/'+suffix
 strict=request(path,True);row={'path':path,'strict':strict}
 if strict['status']=='TLS_NOT_VERIFIED' and p['allow_unverified_tls']:row['temporary_unverified']=request(path,False)
 results.append(row)
print(json.dumps({'verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'source':'designated_test_server','target':p['host'],'namespace':p['namespace'],'checks':results,'credentials_sent':False,'authentication_attempted':False,'resource_writes':0,'tls_exception_scope':'THIS_CREDENTIAL_FREE_PROBE_ONLY' if p['allow_unverified_tls'] else None}))
'''


def classify(result):
    if result['status'] != 'HTTP_RESPONSE':
        return result['status']
    codes = result.get('provider_codes', [])
    if any(code in (1003, 1012) for code in codes) or result['http_status'] == 401:
        return 'AUTHENTICATION_REQUIRED'
    if 13 in codes or result['http_status'] == 403:
        return 'ACCESS_DENIED'
    if 1002 in codes or result['http_status'] == 404:
        return 'API_ROUTE_NOT_CONFIRMED'
    if any(code != 0 for code in codes):
        return 'PROVIDER_REJECTED'
    if result['http_status'] == 200 and codes and result.get('json_object'):
        return 'UNAUTHENTICATED_RESPONSE_ONLY'
    return 'API_RESPONSE_NOT_CONFIRMED'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--namespace', default='public')
    parser.add_argument('--allow-unverified-tls', action='store_true')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,15}', args.namespace):
        raise ValueError('Invalid namespace')
    client = LanZabbix()
    try:
        stdin, stdout, stderr = client.ssh.exec_command(shlex.join(['python3','-c',REMOTE]),timeout=45)
        stdin.write(json.dumps({'host':'192.169.11.1','namespace':args.namespace,'allow_unverified_tls':args.allow_unverified_tls}));stdin.flush();stdin.channel.shutdown_write()
        raw=stdout.read(65537);stderr.read(4096)
        if len(raw)>65536 or stdout.channel.recv_exit_status():
            raise RuntimeError('Read-only firewall entry probe failed')
        report=json.loads(raw)
        for row in report['checks']:
            row['assessment']=classify(row.get('temporary_unverified',row['strict']))
        report['environment']='lan-test-20261008'
        report['firewall_api_ready']=False
        args.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(report,ensure_ascii=True))
    finally:
        client.close()


if __name__ == '__main__':
    try:main()
    except Exception as error:
        print(json.dumps({'status':'FAILED','error_class':type(error).__name__,'details':'omitted'}))
        raise SystemExit(1)

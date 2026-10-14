"""Plan/apply a dedicated AF API trapper host and restricted reader/writer identities."""
import argparse, datetime, ipaddress, json, secrets
from pathlib import Path
from lan_monitoring_zabbix import LanZabbix, ROOT

GROUP='MOP AF API metrics test 20261014'
HOST='mop-lan-af-api-192169111'
OWNER='MOP AF API read-only device metrics; project test scope; no alert rules.'

def plan(allowed):
    return [{'metric':m,'key_':f'mop.af.{m}[192.169.11.1]','type':'2','value_type':'1' if m=='state' else '0','units':'' if m=='state' else '%','history':'7d','trends':'0','delay':'0','status':'0','trapper_hosts':allowed} for m in ['cpu','memory','state']]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--allowed-hosts',required=True);p.add_argument('--apply',action='store_true');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    ips=a.allowed_hosts.split(',')
    if len(ips)>4 or len(ips)!=len(set(ips)) or any(not ipaddress.ip_address(ip).is_private or ipaddress.ip_address(ip).is_unspecified for ip in ips):raise ValueError('Expected explicit private sender addresses')
    allowed=','.join(sorted(ips));private=ROOT/'deploy/private/af-resource-access.json'
    state=json.loads(private.read_text(encoding='utf-8')) if private.exists() else {}
    def save():private.write_text(json.dumps(state,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    api=LanZabbix();api.login_admin()
    try:
        assert api.rpc('apiinfo.version',{}).startswith('7.0.')
        groups=api.rpc('hostgroup.get',{'filter':{'name':[GROUP]},'output':['groupid']});assert len(groups)<=1
        gid=groups[0]['groupid'] if groups else api.rpc('hostgroup.create',{'name':GROUP})['groupids'][0] if a.apply else None
        hosts=api.rpc('host.get',{'filter':{'host':[HOST]},'output':['hostid','description','status'],'selectHostGroups':['groupid']});assert len(hosts)<=1
        if hosts:assert hosts[0]['description']==OWNER and hosts[0]['status']=='0' and {g['groupid'] for g in hosts[0]['hostgroups']}=={gid}
        hid=hosts[0]['hostid'] if hosts else api.rpc('host.create',{'host':HOST,'name':'MOP AF CPU memory test','description':OWNER,'groups':[{'groupid':gid}],'status':0})['hostids'][0] if a.apply else None
        rows=[]
        for item in plan(allowed):
            desired={k:v for k,v in item.items() if k!='metric'}
            old=api.rpc('item.get',{'hostids':[hid],'filter':{'key_':[item['key_']]},'output':['itemid',*desired]}) if hid else []
            if len(old)>1 or old and any(old[0][k]!=v for k,v in desired.items()):raise ValueError('Owned item drift; no overwrite')
            iid=old[0]['itemid'] if old else api.rpc('item.create',{'hostid':hid,'name':'AF API '+item['metric'],'description':OWNER,**desired})['itemids'][0] if a.apply else None
            rows.append({**item,'itemid':iid})
        if a.apply:
            state.update(group_id=gid,host_id=hid,items={r['metric']:r['itemid'] for r in rows},allowed_hosts=allowed)
            save()
            for kind,methods in [('reader',['host.get','item.get','history.get']),('writer',['history.push'])]:
                s=state.setdefault(kind,{});name='mop-af-resource-'+kind;role_name='MOP AF API '+kind+' 20261014'
                if 'role_id' not in s:
                    old=api.rpc('role.get',{'filter':{'name':[role_name]}})
                    if old:raise ValueError('Unknown existing role requires ownership review')
                    s['role_id']=api.rpc('role.create',{'name':role_name,'type':1,'rules':{'ui.default_access':0,'ui':[{'name':'monitoring.latest_data','status':1}],'modules.default_access':0,'actions.default_access':0,'services.read.mode':0,'services.write.mode':0,'api.access':1,'api.mode':1,'api':methods}})['roleids'][0];save()
                if 'usergroup_id' not in s:
                    s['usergroup_id']=api.rpc('usergroup.create',{'name':'MOP AF API '+kind+' 20261014','hostgroup_rights':[{'id':gid,'permission':2}]})['usrgrpids'][0];save()
                if 'user_id' not in s:
                    s.setdefault('password',secrets.token_urlsafe(36)+'aA7!');save()
                    s['user_id']=api.rpc('user.create',{'username':name,'passwd':s['password'],'roleid':s['role_id'],'usrgrps':[{'usrgrpid':s['usergroup_id']}]})['userids'][0];save()
                if 'token_id' not in s:s['token_id']=api.rpc('token.create',{'name':name+'-20261014','userid':s['user_id'],'expires_at':0})['tokenids'][0];save()
                if 'api_token' not in s:s['api_token']=api.rpc('token.generate',[s['token_id']])[0]['token'];save()
            basic=json.loads((ROOT/'deploy/private/basic-monitoring-bindings.json').read_text(encoding='utf-8'))['bindings']
            asset=next(b for b in basic if b['ip']=='192.169.11.1')
            state['binding']={'tenant_id':asset['tenant_id'],'asset_id':asset['asset_id'],'ip':asset['ip'],'host_id':hid,'items':state['items'],'allowed_hosts':allowed,'tls_mode':'TEST_INSECURE'};save()
            admin=api.token;api.token=state['reader']['api_token'];visible=api.rpc('host.get',{'output':['hostid']});assert visible==[{'hostid':hid}];api.token=admin
        report={'environment':'lan-test-20261008','verified_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'apply':a.apply,'group_id':gid,'host_id':hid,'items':rows,'reader_methods':['host.get','item.get','history.get'],'writer_methods':['history.push'],'device_writes':0,'alert_rules_created':False,'credentials_output':False}
        a.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({'apply':a.apply,'items':len(rows),'isolated_host':hid,'credentials_output':False}))
    finally:
        try:api.rpc('user.logout',[])
        finally:api.close()

if __name__=='__main__':
    try:main()
    except Exception as e:print(json.dumps({'state':'FAILED','error_class':type(e).__name__,'details':'omitted; inspect state before retry'}));raise SystemExit(1)

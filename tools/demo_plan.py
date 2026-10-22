#!/usr/bin/env python3
"""Offline demo scope/data preparation. No network, credentials, SQL or apply command."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import uuid

ROOT = Path(__file__).resolve().parents[1]
CONTEXTS = ('padmin', 'msp', 'tadmin')
RESERVED_USERS = {'jiangfaming', 'padmin', 'msp', 'tadmin', 'viewera', 'viewerb', 'admin', 'root'}

class Invalid(ValueError):
    pass

def reject(code):
    raise Invalid(code)

def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))

def digest(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()

def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result: reject('DUPLICATE_JSON_KEY')
        result[key] = value
    return result

def read_json(path, limit=2*1024*1024):
    raw = Path(path).read_bytes()
    if len(raw) > limit: reject('INPUT_TOO_LARGE')
    try: return json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
    except Invalid: raise
    except Exception: reject('INVALID_JSON')

def catalog(root=ROOT):
    value = read_json(root/'config/demo/catalog.v1.json')
    for path, expected in value['source_sha256'].items():
        if hashlib.sha256((root/path).read_bytes()).hexdigest() != expected: reject('SOURCE_CHANGED_REVIEW_COVERAGE')
    if value['scope'] != 'DEMO_PREPARATION_ONLY' or value['activation_status'] != 'NOT_APPROVED_NOT_APPLIED': reject('INVALID_CATALOG_STATUS')
    paths = [f['path'] for f in value['features']]
    if len(paths) != len(set(paths)) or any(f['acceptance_status'] != 'NOT_RUN' or not f['scenarios'] for f in value['features']): reject('INVALID_FEATURE_COVERAGE')
    return value

def capabilities(root=ROOT):
    source = (root/'server/domain/src/main/java/cn/mop/domain/Capabilities.java').read_text()
    result = {role: re.findall(r'"([a-z_]+:[a-z_]+)"', body) for role, body in re.findall(r'case "([a-z]+)" -> List.of\((.*?)\);', source)}
    if set(result) != {'padmin', 'msp', 'tadmin', 'viewer'} or any(not v or len(v) != len(set(v)) for v in result.values()): reject('CAPABILITY_DECLARATION_CHANGED')
    return result

def utc(value):
    return value.astimezone(timezone.utc).isoformat(timespec='seconds').replace('+00:00','Z')

def prepare(config, root=ROOT):
    if not isinstance(config, dict) or set(config) != {'version','namespace_id','username','anchor_time','deployment_mode'} or type(config['version']) is not int or config['version'] != 1: reject('INVALID_PLAN_INPUT')
    cat = catalog(root); caps = capabilities(root)
    try:
        ns = uuid.UUID(config['namespace_id'])
        if str(ns) != config['namespace_id'] or ns.version not in (4,5) or str(ns) in cat['protected_tenant_ids']: reject('FRESH_NAMESPACE_REQUIRED')
    except Invalid: raise
    except Exception: reject('FRESH_NAMESPACE_REQUIRED')
    name = config['username']
    if not isinstance(name,str) or not re.fullmatch(r'[a-z][a-z0-9_-]{2,31}',name) or name in RESERVED_USERS: reject('NEW_DEMO_USERNAME_REQUIRED')
    if config['deployment_mode'] not in ('dedicated','isolated_tenants'): reject('DEPLOYMENT_CHOICE_REQUIRED')
    try:
        if not isinstance(config['anchor_time'],str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z',config['anchor_time']): reject('ANCHOR_TIME_REQUIRED')
        anchor = datetime.fromisoformat(config['anchor_time'].replace('Z','+00:00'))
        if not 2000 <= anchor.year <= 2099: reject('INVALID_ANCHOR_TIME')
    except Invalid: raise
    except Exception: reject('INVALID_ANCHOR_TIME')
    identity = lambda key: str(uuid.uuid5(ns,key))
    contexts = [{'key':role,'tenant_id':identity('tenant:'+role),'name':'演示·'+{'padmin':'管理','msp':'工程师','tadmin':'客户'}[role],
        'base_role':role,'proposed_permissions':caps[role],'active':False,'binding_verified':False} for role in CONTEXTS]
    if any(c['tenant_id'] in cat['protected_tenant_ids'] for c in contexts): reject('PROTECTED_NAMESPACE')
    coverage=[]
    for feature in cat['features']:
        available=[r for r in CONTEXTS if (not feature['allowed_roles'] or r in feature['allowed_roles']) and set(feature['required_capabilities']) <= set(caps[r])]
        if not available: reject('FEATURE_HAS_NO_PROPOSED_CONTEXT')
        coverage.append({**feature,'proposed_contexts':available,'data_initialized':False,'interaction_verified':False})
    requests=[]; entities=[]
    def add(key,context,path,body,bindings=None,preconditions=None):
        requests.append({'key':key,'context':context,'method':'POST','path':path,'body':body,'bindings':bindings or [],
            'preconditions':preconditions or [],'executed':False,'references_verified':False})
    for context in contexts:
        role=context['key']; site=identity(role+':site');entities.append({'key':role+':site','id':site,'kind':'site','tenant_id':context['tenant_id'],'created':False})
        for key,kind,label,index in [('router','router','路由器',10),('switch','switch','交换机',11),('firewall','firewall','防火墙',12),('wireless','ap','无线接入点',13),('controller','wireless_controller','无线控制器',14),('linux','other','Linux服务器',15),('windows','other','Windows服务器',16),('database','other','数据库服务',17)]:
            logical=role+':asset:'+key;asset=identity(logical);entities.append({'key':logical,'id':asset,'kind':'asset','tenant_id':context['tenant_id'],'created':False})
            add(logical,role,'/assets',{'name':'[DEMO] '+label,'type':kind,'management_ip':f'192.0.2.{index}','site_id':site},[{'pointer':'/site_id','entity_key':role+':site'}],['SITE_MUST_EXIST_IN_SCENARIO_TENANT'])
        if role in ('padmin','tadmin'):
            for index,contact_role in enumerate(['REPORTER','DECISION_MAKER','TECHNICAL_CONTACT'],1):
                add(role+':contact:'+str(index),role,'/contacts',{'expected_version':0,'name':'[DEMO] '+contact_role,'roles':[contact_role],'mobile':None,'email':f'demo-{index}@example.invalid','wecom_user_id':None,'preferred_priorities':None,'quiet_window':None,'enabled':True},preconditions=['OUTBOUND_DELIVERY_MUST_REMAIN_DISABLED'])
        ticket_key=role+':ticket';ticket_id=identity(ticket_key);entities.append({'key':ticket_key,'id':ticket_id,'kind':'ticket','tenant_id':context['tenant_id'],'created':False})
        body={'title':'[DEMO] 隔离场景报障','description':'仅供演示的虚构场景，不代表真实业务故障。','source':'customer_report' if role=='tadmin' else 'manual','asset_id':identity(role+':asset:router')}
        if role=='tadmin':body.update({'category':'网络不通','impact_scope':'individual','contact_name':'[DEMO] 联系人','contact_phone':'DEMO-NO-CALL','urgency':'normal','attachment_ids':[]})
        add(ticket_key,role,'/tickets',body,[{'pointer':'/asset_id','entity_key':role+':asset:router'}],['NO_REAL_NOTIFICATION_PROVIDER'])
        add(role+':preference',role,'/my/notification-preferences',{'expected_version':0,'intent':'SAVE_REQUEST_DRAFT_ONLY','requested_level':'P1_P2','requested_channels':['EMAIL'],'requested_quiet_window':None})
    add('padmin:inspection-template','padmin','/inspection-templates',{'expected_version':0,'device_type':'switch','name':'[DEMO] 交换机巡检草稿','items':[{'key':'ports','section':'[DEMO] 接口','label':'记录接口状态','instruction':'仅演示，不读取真实设备。'},{'key':'backup','section':'[DEMO] 备份','label':'检查备份记录','instruction':None}]})
    asset=identity('padmin:asset:switch');ref={'pointer':'/target_asset_ids/0','entity_key':'padmin:asset:switch'}
    add('padmin:backup','padmin','/backup-plan-drafts',{'expected_version':0,'name':'[DEMO] 停用备份计划','target_asset_ids':[asset],'daily_local_time':'02:00','credential_ref':None,'requested_enabled':False,'note':'仅演示计划，不连接或操作真实设备。'},[ref])
    add('padmin:change','padmin','/change-notice-drafts',{'expected_version':0,'change_summary':'[DEMO] 隔离演示维护窗口','asset_ids':[asset],'planned_start_at':utc(anchor+timedelta(days=1)),'planned_end_at':utc(anchor+timedelta(days=1,hours=1)),'ticket_id':None},[{'pointer':'/asset_ids/0','entity_key':'padmin:asset:switch'}])
    add('msp:worklog','msp','/tickets/{ticketId}/worklogs',{'expected_ticket_version':1,'kind':'REMOTE','started_at':utc(anchor-timedelta(hours=1)),'ended_at':utc(anchor),'note':'[DEMO] 虚构工时，不用于实际结算。'},[{'pointer':'path:ticketId','entity_key':'msp:ticket'}],['TICKET_MUST_BE_ASSIGNED_TO_SCENARIO_ENGINEER','CURRENT_TICKET_VERSION_REQUIRED'])
    result={'version':1,'status':'PREPARATION_ONLY','deployment_mode':config['deployment_mode'],'namespace_id':str(ns),'username':name,'anchor_time':config['anchor_time'],
        'catalog_sha256':digest(cat),'source_sha256':cat['source_sha256'],'contexts':contexts,'features':coverage,'entities':entities,'request_fixtures':requests,
        'coverage':{'pages_declared':len(coverage),'capability_union_count':len(set().union(*(set(caps[r]) for r in CONTEXTS))),'pages_accepted':0,'all_functions_ready':False},
        'account_created':False,'credentials_created':False,'permissions_granted':False,'business_data_written':False,'outbound_enabled':False,
        'global_operation_constraints':cat['global_operation_constraints'],'blockers':['DEPLOYMENT_DESTINATION_NOT_CONFIRMED','ACCOUNT_CREATION_AND_GRANTS_NOT_APPROVED','ROLE_SCENARIO_MODEL_NOT_CONFIRMED','RUNTIME_REFERENCES_NOT_VERIFIED','ADVANCED_SCENARIOS_NOT_INITIALIZED','GLOBAL_POLICY_SCOPE_UNRESOLVED']}
    result['plan_sha256']=digest(result)
    return result

def write_bundle(plan, output):
    expected=prepare({key:plan[key] for key in ('version','namespace_id','username','anchor_time','deployment_mode')})
    if plan != expected: reject('PLAN_CHANGED')
    if plan.get('status')!='PREPARATION_ONLY' or plan.get('plan_sha256')!=digest({k:v for k,v in plan.items() if k!='plan_sha256'}):reject('PLAN_CHANGED')
    if any(plan.get(k) is not False for k in ['account_created','credentials_created','permissions_granted','business_data_written','outbound_enabled']):reject('APPLIED_PLAN_FORBIDDEN')
    destination=Path(output).absolute()
    if '..' in Path(output).parts or any(p.is_symlink() for p in [destination,*destination.parents]):reject('OUTPUT_SYMLINK_FORBIDDEN')
    destination.mkdir(parents=False,exist_ok=False)
    for name,value in [('request-fixtures.json',plan['request_fixtures']),('feature-coverage.json',plan['features']),('plan.json',plan)]:
        with (destination/name).open('x',encoding='utf-8') as stream:stream.write(json.dumps(value,ensure_ascii=False,indent=2)+'\n')

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--input',required=True);parser.add_argument('--output',required=True)
    args=parser.parse_args(argv)
    try:
        plan=prepare(read_json(args.input,65536));write_bundle(plan,args.output)
        print(json.dumps({'status':plan['status'],'plan_sha256':plan['plan_sha256'],'pages_declared':plan['coverage']['pages_declared'],'request_fixtures':len(plan['request_fixtures']),'account_created':False,'applied':False}))
        return 0
    except Exception as failure:
        print(json.dumps({'error':str(failure) if isinstance(failure,Invalid) else 'PREPARATION_FAILED','applied':False}));return 2

if __name__=='__main__':raise SystemExit(main())

"""Explicit monitoring configuration, deterministic rendering and bounded single probes.

No scan, automatic deployment, credential creation, global defaults or hidden retries.
Network probes require exact endpoint and downstream-target allowlists from the caller.
"""
from __future__ import annotations
import argparse
import hashlib
import http.client
import ipaddress
import json
import math
import re
import socket
import ssl
import threading
import sys
import time
from pathlib import Path
from urllib.parse import urlsplit, urlencode

ROOT = Path(__file__).resolve().parents[1]
CATALOG = json.loads((ROOT / 'config/monitoring/presets.v1.json').read_text())
PRESETS = {p['id']: p for p in CATALOG['presets']}
MAX_BYTES = 2 * 1024 * 1024
FIELDS = {'id', 'tenant_id', 'asset_id', 'preset_id', 'enabled', 'endpoint', 'target', 'auth_profile', 'interval_seconds', 'timeout_seconds'}
UUID = re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z')
SYMBOL = re.compile(r'[a-zA-Z_][a-zA-Z0-9_-]{0,63}\Z')

class Invalid(ValueError):
    """Intentionally contains no input values, endpoints, credentials or response data."""

def fail(code):
    raise Invalid(code)

def unique_object(pairs):
    out = {}
    for k, v in pairs:
        if k in out: fail('DUPLICATE_JSON_KEY')
        out[k] = v
    return out

def load_json(path):
    raw = Path(path).read_bytes()
    if len(raw) > MAX_BYTES: fail('CONFIG_TOO_LARGE')
    try:
        return json.loads(raw, object_pairs_hook=unique_object, parse_constant=lambda _: fail('NONFINITE_JSON'))
    except (UnicodeError, json.JSONDecodeError):
        fail('INVALID_JSON')

def ip_literal(value):
    try:
        if not isinstance(value, str) or '%' in value: fail('INVALID_IP')
        ip = ipaddress.ip_address(value)
        if ip.is_unspecified or ip.is_multicast or ip.is_link_local: fail('UNSAFE_IP')
        if str(ip) != value: fail('NONCANONICAL_IP')
        return ip
    except ValueError:
        fail('INVALID_IP')

def endpoint(value, kind, expected_path):
    if not isinstance(value, str) or len(value) > 512 or any(ord(c) <= 32 or ord(c) == 127 for c in value): fail('INVALID_ENDPOINT')
    try:
        p = urlsplit(value)
        if p.scheme not in ({'tcp', 'udp'} if kind in {'tcp', 'configuration_only'} else {'http', 'https'}): fail('ENDPOINT_SCHEME')
        if p.username is not None or p.password is not None or p.query or p.fragment or p.port is None: fail('ENDPOINT_COMPONENTS')
        ip_literal(p.hostname)
        if not 1 <= p.port <= 65535: fail('ENDPOINT_PORT')
        if p.path != (expected_path or ''): fail('ENDPOINT_PATH')
        if kind == 'tcp' and p.scheme != 'tcp' or kind == 'configuration_only' and p.scheme != 'udp': fail('ENDPOINT_SCHEME')
        host = f'[{p.hostname}]' if ':' in p.hostname else p.hostname
        if value != f'{p.scheme}://{host}:{p.port}{p.path}': fail('NONCANONICAL_ENDPOINT')
        return p
    except (ValueError, TypeError):
        fail('INVALID_ENDPOINT')

def downstream(value, preset):
    if preset['test_kind'] not in {'blackbox', 'snmp'}:
        if value is not None: fail('UNEXPECTED_TARGET')
        return
    if not isinstance(value, str) or len(value) > 512: fail('TARGET_REQUIRED')
    if preset['id'] == 'http-service':
        # Exporter resolves no arbitrary hostname: configuration pins a literal IP.
        p = urlsplit(value)
        if p.scheme not in {'http', 'https'} or p.username is not None or p.password is not None or p.query or p.fragment: fail('INVALID_HTTP_TARGET')
        ip_literal(p.hostname)
        try: port = p.port
        except ValueError: fail('INVALID_HTTP_TARGET')
        if port is not None and not 1 <= port <= 65535: fail('INVALID_HTTP_TARGET')
        if any(ord(c) <= 32 or ord(c) == 127 for c in value) or '\\' in value or '%' in value: fail('INVALID_HTTP_TARGET')
    elif preset['id'] == 'tcp-service':
        try:
            p = urlsplit('tcp://' + value)
            ip_literal(p.hostname)
            if p.username is not None or p.password is not None or p.path or p.query or p.fragment or p.port is None or not 1 <= p.port <= 65535: fail('INVALID_TCP_TARGET')
        except ValueError: fail('INVALID_TCP_TARGET')
    else: ip_literal(value)

def validate(config):
    if not isinstance(config, dict) or set(config) != {'version', 'targets'} or type(config['version']) is not int or config['version'] != 1: fail('CONFIG_SHAPE')
    if not isinstance(config['targets'], list) or not 1 <= len(config['targets']) <= 64: fail('TARGET_COUNT')
    ids, bindings = set(), set()
    for t in config['targets']:
        if not isinstance(t, dict) or set(t) != FIELDS: fail('TARGET_SHAPE')
        for k in ('id', 'tenant_id', 'asset_id'):
            if not isinstance(t[k], str) or not UUID.fullmatch(t[k]): fail('INVALID_ID')
        if not isinstance(t['preset_id'], str) or t['preset_id'] not in PRESETS: fail('UNKNOWN_PRESET')
        p = PRESETS[t['preset_id']]
        if t['id'] in ids: fail('DUPLICATE_TARGET_ID')
        ids.add(t['id']); binding = (t['tenant_id'], t['asset_id'], t['preset_id'])
        if binding in bindings: fail('DUPLICATE_ASSET_PRESET')
        bindings.add(binding)
        if type(t['enabled']) is not bool: fail('INVALID_ENABLED')
        if t['interval_seconds'] is not None and (type(t['interval_seconds']) is not int or not 5 <= t['interval_seconds'] <= 3600): fail('INVALID_INTERVAL')
        if t['enabled'] and t['interval_seconds'] is None: fail('INTERVAL_REQUIRED')
        if type(t['timeout_seconds']) is not int or not 1 <= t['timeout_seconds'] <= 10: fail('INVALID_TIMEOUT')
        if t['interval_seconds'] is not None and t['timeout_seconds'] >= t['interval_seconds']: fail('TIMEOUT_MUST_BE_SHORTER')
        endpoint(t['endpoint'], p['test_kind'], p['path']); downstream(t['target'], p)
        if p['test_kind'] == 'snmp':
            if not isinstance(t['auth_profile'], str) or not SYMBOL.fullmatch(t['auth_profile']): fail('SNMP_AUTH_PROFILE_REQUIRED')
        elif t['auth_profile'] is not None: fail('UNEXPECTED_AUTH_PROFILE')
    # Detach from caller mutations before a network request or export.
    return json.loads(json.dumps(config))

def fingerprint(config):
    return hashlib.sha256(json.dumps(validate(config), sort_keys=True, separators=(',', ':')).encode()).hexdigest()

def render(config):
    config = validate(config)
    jobs, handoffs = [], []
    modules = {
        'mop_http': {'prober': 'http', 'http': {'method': 'GET', 'follow_redirects': False, 'tls_config': {'insecure_skip_verify': False}}},
        'mop_tcp': {'prober': 'tcp'}, 'mop_icmp': {'prober': 'icmp'},
    }
    for t in config['targets']:
        p = PRESETS[t['preset_id']]
        if p['engine'] == 'zabbix':
            handoffs.append({'id': t['id'], 'tenant_id': t['tenant_id'], 'asset_id': t['asset_id'], 'endpoint': t['endpoint'], 'template_name': p['template'], 'template_id': None, 'host_group_id': None, 'credential_reference': None, 'status': 'REQUIRES_EXACT_TEMPLATE_AND_SECURITY_CONFIGURATION', 'applied': False})
            continue
        if not t['enabled']: continue
        e = endpoint(t['endpoint'], p['test_kind'], p['path'])
        authority = e.netloc
        job = {'job_name': 'mop_' + t['id'], 'scrape_interval': f"{t['interval_seconds']}s", 'scrape_timeout': f"{t['timeout_seconds']}s", 'scheme': e.scheme, 'metrics_path': p['path'], 'follow_redirects': False, 'honor_labels': False, 'honor_timestamps': False, 'sample_limit': 100000, 'body_size_limit': '2MB', 'static_configs': [{'targets': [t['target'] if p['test_kind'] in {'blackbox','snmp'} else authority], 'labels': {'mop_tenant_id':t['tenant_id'],'mop_asset_id':t['asset_id'],'mop_target_id':t['id'],'mop_preset':t['preset_id']}}]}
        if e.scheme == 'https': job['tls_config'] = {'insecure_skip_verify':False}
        if p['test_kind'] in {'blackbox','snmp'}:
            job['params'] = {'module':[p['module']]}
            if p['test_kind'] == 'snmp':job['params']['auth']=[t['auth_profile']]
            job['relabel_configs']=[{'source_labels':['__address__'],'target_label':'__param_target'},{'source_labels':['__param_target'],'target_label':'instance'},{'target_label':'__address__','replacement':authority}]
        jobs.append(job)
    return {'configuration_sha256':fingerprint(config),'prometheus':{'scrape_configs':jobs},'blackbox':{'modules':modules},'zabbix_handoffs':handoffs,'applied':False,'continuous_collection_verified':False}

# Prometheus text/OpenMetrics samples; ignore labels in reports and reject duplicate series.
SAMPLE = re.compile(r'([a-zA-Z_:][a-zA-Z0-9_:]*)(\{(?:[^"\\{}]|"(?:[^"\\]|\\[\\"n])*")*\})?\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?|[-+]?Inf|NaN)(?:\s+[-+]?\d+(?:\.\d+)?)?\s*(?:#.*)?\Z')

LABEL = re.compile(r'\s*([a-zA-Z_][a-zA-Z0-9_]*)="((?:[^"\\]|\\[\\"n])*)"\s*')
def label_key(raw):
    if not raw:return ()
    text=raw[1:-1];labels={};offset=0
    if not text.strip():return ()
    while offset<len(text):
        match=LABEL.match(text,offset)
        if not match or match[1] in labels:fail('INVALID_METRIC_LABELS')
        labels[match[1]]=match[2];offset=match.end()
        if offset==len(text):break
        if text[offset]!=',':fail('INVALID_METRIC_LABELS')
        offset+=1
        if not text[offset:].strip():break
    return tuple(sorted(labels.items()))

def metrics_summary(body, preset):
    try: text = body.decode('utf-8', errors='strict')
    except UnicodeError: fail('INVALID_METRICS_ENCODING')
    seen, names, values = set(), set(), {}
    count = 0
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith('#'):continue
        if len(line) > 65536:fail('METRIC_LINE_TOO_LONG')
        m = SAMPLE.fullmatch(line)
        if not m:fail('INVALID_METRICS_FORMAT')
        key=(m[1],label_key(m[2]))
        if key in seen:fail('DUPLICATE_METRIC_SERIES')
        seen.add(key); names.add(m[1]);count+=1
        if count>100000:fail('TOO_MANY_METRICS')
        if m[1] in preset['required_any_metrics']:
            v=float(m[3])
            if not math.isfinite(v):fail('NONFINITE_REQUIRED_METRIC')
            values.setdefault(m[1],[]).append(v)
    matched=sorted(set(preset['required_any_metrics']) & names)
    if not matched:fail('EXPECTED_METRICS_MISSING')
    success=preset.get('success_metric')
    if success and (len(values.get(success,[])) != 1 or values[success][0] != 1):fail('TARGET_PROBE_FAILED')
    return {'sample_count':count,'matched_metrics':matched,'metric_values_stored':False}

def probe(config, target_id, *, allow_endpoints=(), allow_targets=()):
    config=validate(config)
    found=[t for t in config['targets'] if t['id']==target_id]
    if len(found)!=1:fail('TARGET_NOT_FOUND')
    t=found[0];p=PRESETS[t['preset_id']]
    result={'target_id':target_id,'configuration_sha256':fingerprint(config),'probe_status':'NOT_RUN','continuous_collection_verified':False,'device_identity_verified':False,'health_verified':False,'sample_count':None,'matched_metrics':[]}
    if p['test_kind']=='configuration_only':return dict(result,reason='SNMP_TEST_REQUIRES_CONFIGURED_PROVIDER')
    if t['endpoint'] not in allow_endpoints:fail('ENDPOINT_NOT_AUTHORIZED')
    if t['target'] is not None and t['target'] not in allow_targets:fail('DOWNSTREAM_TARGET_NOT_AUTHORIZED')
    e=endpoint(t['endpoint'],p['test_kind'],p['path']);started=time.monotonic();connection=None;deadline=None
    try:
        if p['test_kind']=='tcp':
            with socket.create_connection((e.hostname,e.port),t['timeout_seconds']): pass
            return dict(result,probe_status='TCP_REACHABLE_PROTOCOL_UNVERIFIED',reason='NO_AGENT_AUTHENTICATION_OR_SAMPLE_VERIFIED')
        if e.scheme=='https':connection=http.client.HTTPSConnection(e.hostname,e.port,timeout=t['timeout_seconds'],context=ssl.create_default_context())
        else:connection=http.client.HTTPConnection(e.hostname,e.port,timeout=t['timeout_seconds'])
        connection.connect()
        transport_socket=connection.sock
        def expire():
            try:
                if transport_socket is not None:transport_socket.shutdown(socket.SHUT_RDWR)
            except OSError:pass
        remaining=t['timeout_seconds']-(time.monotonic()-started)
        if remaining<=0:fail('PROBE_TIMEOUT')
        deadline=threading.Timer(remaining,expire);deadline.daemon=True;deadline.start()
        query={}
        if t['target'] is not None:query={'target':t['target'],'module':p['module']}
        if p['test_kind']=='snmp':query['auth']=t['auth_profile']
        path=e.path+('?' + urlencode(query) if query else '')
        connection.request('GET',path,headers={'Accept':'text/plain; version=0.0.4','Accept-Encoding':'identity','User-Agent':'MOP-monitoring-preflight/1'})
        response=connection.getresponse()
        if response.status!=200:fail('HTTP_STATUS_REJECTED')
        if response.getheader('Content-Encoding','identity').lower()!='identity':fail('ENCODING_REJECTED')
        ctype=response.getheader('Content-Type','').split(';')[0].strip().lower()
        if ctype not in {'text/plain','application/openmetrics-text'}:fail('CONTENT_TYPE_REJECTED')
        data=bytearray()
        while True:
            if response.isclosed():break
            remaining=t['timeout_seconds']-(time.monotonic()-started)
            if remaining<=0:fail('PROBE_TIMEOUT')
            if transport_socket is not None:transport_socket.settimeout(remaining)
            chunk=response.read1(min(65536,MAX_BYTES+1-len(data)))
            if not chunk:break
            data.extend(chunk)
            if len(data)>MAX_BYTES:fail('RESPONSE_TOO_LARGE')
        if response.length is not None and response.length>0:fail('TRUNCATED_RESPONSE')
        if time.monotonic()-started>=t['timeout_seconds']:fail('PROBE_TIMEOUT')
        summary=metrics_summary(bytes(data),p)
        return dict(result,probe_status='SAMPLE_VERIFIED_ONCE',**summary)
    except Invalid as ex:return dict(result,probe_status='FAILED',reason=str(ex))
    except ssl.SSLError:return dict(result,probe_status='FAILED',reason='TLS_VERIFICATION_OR_HANDSHAKE_FAILED')
    except (socket.timeout,TimeoutError):return dict(result,probe_status='FAILED',reason='PROBE_TIMEOUT')
    except (OSError,http.client.HTTPException):return dict(result,probe_status='FAILED',reason='CONNECTION_FAILED')
    finally:
        if deadline is not None:deadline.cancel()
        if connection is not None:connection.close()

def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['catalog','validate','render','test'])
    parser.add_argument('--config');parser.add_argument('--target-id')
    parser.add_argument('--format',choices=['bundle','prometheus','blackbox','zabbix_handoffs'],default='bundle')
    parser.add_argument('--allow-endpoint',action='append',default=[])
    parser.add_argument('--allow-target',action='append',default=[])
    args=parser.parse_args(argv)
    try:
        if args.action=='catalog':result=CATALOG
        else:
            if not args.config:fail('CONFIG_REQUIRED')
            config=validate(load_json(args.config))
            if args.action=='validate':result={'valid':True,'target_count':len(config['targets']),'configuration_sha256':fingerprint(config),'network_accessed':False}
            elif args.action=='render':
                bundle=render(config);result=bundle if args.format=='bundle' else bundle[args.format]
            else:result=probe(config,args.target_id,allow_endpoints=args.allow_endpoint,allow_targets=args.allow_target)
        print(json.dumps(result,ensure_ascii=False,indent=2))
        return 2 if isinstance(result,dict) and result.get('probe_status') in {'FAILED','NOT_RUN'} else 0
    except (Invalid,OSError) as ex:
        print(json.dumps({'error':str(ex) if isinstance(ex,Invalid) else 'CONFIG_READ_FAILED','network_success':False}))
        return 2
if __name__=='__main__':sys.exit(main())

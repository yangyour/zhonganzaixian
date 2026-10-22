import contextlib
import copy
import http.server
import io
import json
import socket
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import monitoring_presets as m

EXAMPLE=m.load_json(m.ROOT/'deploy/examples/monitoring/targets.disabled.json')
def target(preset='linux-node', **kw):
    t=copy.deepcopy(next(x for x in EXAMPLE['targets'] if x['preset_id']==preset));t.update(kw);return {'version':1,'targets':[t]}

class Configuration(unittest.TestCase):
    def test_all_catalog_presets_have_disabled_valid_examples(self):
        self.assertEqual(len(m.PRESETS),13);self.assertEqual(len(m.validate(EXAMPLE)['targets']),13)
        self.assertTrue(all(not t['enabled'] and t['interval_seconds'] is None for t in EXAMPLE['targets']))
    def test_shared_browser_golden_configurations_and_hashes(self):
        for row in m.load_json(m.ROOT/'fixtures/monitoring-onboarding-golden.json'):
            self.assertEqual(m.fingerprint(row['config']),row['sha256'])
    def test_no_network_during_validation_and_render(self):
        with patch('socket.create_connection',side_effect=AssertionError('network')):
            m.validate(EXAMPLE);out=m.render(EXAMPLE)
        self.assertEqual(out['prometheus']['scrape_configs'],[]);self.assertEqual(len(out['zabbix_handoffs']),3)
        self.assertFalse(out['applied']);self.assertFalse(out['continuous_collection_verified'])
    def test_enabled_requires_explicit_interval(self):
        with self.assertRaises(m.Invalid):m.validate(target(enabled=True))
    def test_timeout_must_be_less_than_interval(self):
        with self.assertRaises(m.Invalid):m.validate(target(interval_seconds=5,timeout_seconds=5))
    def test_disallowed_config_shapes(self):
        for bad in [None,[],{}, {'version':True,'targets':EXAMPLE['targets']},{'version':1,'targets':[]},{'version':1,'targets':EXAMPLE['targets'],'password':'secret'}]:
            with self.subTest(bad=type(bad)),self.assertRaises(m.Invalid):m.validate(bad)
    def test_strict_fields_and_types(self):
        for k,v in [('id','x'),('tenant_id',[]),('asset_id',None),('preset_id',{}),('enabled',1),('interval_seconds',True),('interval_seconds',0),('timeout_seconds',11),('timeout_seconds',0),('target','192.0.2.5'),('auth_profile','secret')]:
            with self.subTest(key=k,value=v),self.assertRaises(m.Invalid):m.validate(target(**{k:v}))
        bad=target();bad['targets'][0]['password']='never';self.assertRaises(m.Invalid,m.validate,bad)
    def test_url_credentials_queries_paths_hostnames_and_special_ips_rejected(self):
        urls=['http://user:pass@192.0.2.1:9100/metrics','http://192.0.2.1:9100/metrics?token=x','http://192.0.2.1:9100/metrics#x','http://localhost:9100/metrics','http://127.1:9100/metrics','http://0.0.0.0:9100/metrics','http://169.254.169.254:9100/metrics','http://224.0.0.1:9100/metrics','file:///etc/passwd','http://192.0.2.1/metrics','http://192.0.2.1:9100/secret','http://192.0.2.1:9100/metrics\n','http://[fe80::1%25lo]:9100/metrics']
        for url in urls:
            with self.subTest(url=url),self.assertRaises(m.Invalid):m.validate(target(endpoint=url))
    def test_ipv6_literal_is_supported(self):
        m.validate(target(endpoint='http://[::1]:9100/metrics'))
    def test_duplicates_are_not_silently_merged(self):
        c=target();c['targets']*=2;self.assertRaises(m.Invalid,m.validate,c)
        c=target();a=copy.deepcopy(c['targets'][0]);a['id']='79000000-0000-4000-8000-000000000099';c['targets'].append(a);self.assertRaises(m.Invalid,m.validate,c)
    def test_parsing_rejects_duplicate_keys_nonfinite_and_bounded_file(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'a.json'
            for raw in ['{"version":1,"version":1}', '{"x":NaN}', 'x', '\ufffd', ' '* (m.MAX_BYTES+1)]:
                p.write_text(raw)
                with self.subTest(raw=raw[:20]),self.assertRaises(m.Invalid):m.load_json(p)
    def test_exact_snapshot_and_digest(self):
        c=target();v=m.validate(c);h=m.fingerprint(c);c['targets'][0]['enabled']=True
        self.assertFalse(v['targets'][0]['enabled']);self.assertEqual(h,m.fingerprint(v))
    def test_render_enabled_prometheus_job_includes_scope_and_limits(self):
        c=target(enabled=True,interval_seconds=30);out=m.render(c);j=out['prometheus']['scrape_configs'][0]
        self.assertEqual(j['scrape_interval'],'30s');self.assertEqual(j['scrape_timeout'],'5s');self.assertFalse(j['honor_labels']);self.assertFalse(j['honor_timestamps']);self.assertFalse(j['follow_redirects'])
        self.assertEqual(j['static_configs'][0]['labels']['mop_tenant_id'],c['targets'][0]['tenant_id']);self.assertNotIn('alerting',out['prometheus'])
    def test_https_verifies_certificates(self):
        j=m.render(target(enabled=True,interval_seconds=30,endpoint='https://192.0.2.1:9100/metrics'))['prometheus']['scrape_configs'][0]
        self.assertFalse(j['tls_config']['insecure_skip_verify'])
    def test_blackbox_and_snmp_bind_explicit_downstream(self):
        for preset in ['http-service','tcp-service','icmp-device','network-snmp']:
            c=target(preset,enabled=True,interval_seconds=30);j=m.render(c)['prometheus']['scrape_configs'][0]
            self.assertEqual(j['static_configs'][0]['targets'],[c['targets'][0]['target']]);self.assertEqual(j['relabel_configs'][0]['target_label'],'__param_target')
            self.assertEqual(j['params']['module'],[m.PRESETS[preset]['module']])
        self.assertEqual(m.render(target('network-snmp',enabled=True,interval_seconds=30))['prometheus']['scrape_configs'][0]['params']['auth'],['replace_with_configured_snmp_profile'])
    def test_no_default_snmp_community(self):
        for auth in [None,'','public secret','../secret']:
            with self.subTest(auth=auth),self.assertRaises(m.Invalid):m.validate(target('network-snmp',auth_profile=auth))
    def test_http_downstream_rejects_hidden_credentials_and_unsafe_addresses(self):
        for url in ['https://user:secret@192.0.2.2/','https://192.0.2.2/?token=x','https://localhost/','https://169.254.169.254/','https://192.0.2.2/%2e','https://192.0.2.2:999999/']:
            with self.subTest(url=url),self.assertRaises(m.Invalid):m.validate(target('http-service',target=url))
    def test_blackbox_modules_never_skip_tls_or_follow_redirects(self):
        p=m.render(EXAMPLE)['blackbox']['modules']['mop_http']['http'];self.assertFalse(p['follow_redirects']);self.assertFalse(p['tls_config']['insecure_skip_verify'])
    def test_zabbix_handoff_cannot_claim_applied_or_guess_ids(self):
        for row in m.render(EXAMPLE)['zabbix_handoffs']:
            self.assertIsNone(row['template_id']);self.assertIsNone(row['host_group_id']);self.assertFalse(row['applied']);self.assertIsNone(row['credential_reference'])
    def test_unauthorized_probes_do_no_io(self):
        with patch('socket.create_connection',side_effect=AssertionError('network')):
            self.assertRaises(m.Invalid,m.probe,target(),EXAMPLE['targets'][0]['id'])
    def test_downstream_independently_authorized(self):
        c=target('icmp-device');t=c['targets'][0]
        with patch('socket.create_connection',side_effect=AssertionError('network')):
            self.assertRaises(m.Invalid,m.probe,c,t['id'],allow_endpoints=[t['endpoint']])
    def test_snmp_handoff_reports_not_run(self):
        c=target('zabbix-snmp');self.assertEqual(m.probe(c,c['targets'][0]['id'])['probe_status'],'NOT_RUN')
    def test_cli_validate_and_error_output_never_echo_secret(self):
        for argv in [['catalog'],['validate'],['test']]:
            with contextlib.redirect_stdout(io.StringIO()) as out:code=m.main(argv)
            self.assertIsInstance(json.loads(out.getvalue()),dict)
            self.assertEqual(code,0 if argv==['catalog'] else 2)

class Metrics(unittest.TestCase):
    def test_each_prometheus_preset_accepts_its_own_metric(self):
        for p in m.PRESETS.values():
            if p['engine']!='prometheus':continue
            with self.subTest(preset=p['id']):self.assertEqual(m.metrics_summary((p['required_any_metrics'][0]+' 1\n').encode(),p)['sample_count'],1)
    def test_rejects_html_empty_wrong_metric_duplicate_and_nonfinite(self):
        p=m.PRESETS['linux-node']
        for body in [b'',b'<html>login</html>',b'up 1\n',b'node_cpu_seconds_total NaN',b'node_cpu_seconds_total Inf',b'node_cpu_seconds_total 1\nnode_cpu_seconds_total 2',b'node_cpu_seconds_total 3 bad',b'\xff']:
            with self.subTest(body=body[:30]),self.assertRaises(m.Invalid):m.metrics_summary(body,p)
    def test_malformed_labels_and_reordered_duplicate_series_rejected(self):
        for body in [b'node_cpu_seconds_total{oops} 1',b'node_cpu_seconds_total{a="1",a="2"} 1',b'node_cpu_seconds_total{a="1",b="2"} 1\nnode_cpu_seconds_total{b="2",a="1"} 2']:
            self.assertRaises(m.Invalid,m.metrics_summary,body,m.PRESETS['linux-node'])
    def test_labels_are_not_returned(self):
        out=m.metrics_summary(b'node_cpu_seconds_total{cpu="0",mode="idle"} 3.12e2\n',m.PRESETS['linux-node']);self.assertNotIn('idle',json.dumps(out));self.assertEqual(out['matched_metrics'],['node_cpu_seconds_total'])
    def test_failed_or_ambiguous_probe_is_not_healthy(self):
        for body in [b'probe_success 0',b'probe_success NaN',b'probe_success{a="1"} 1\nprobe_success{a="2"} 1']:
            self.assertRaises(m.Invalid,m.metrics_summary,body,m.PRESETS['http-service'])

class LiveHTTP(unittest.TestCase):
    """Actual local TCP/HTTP, synthetic exporter responses; NOT real vendor integration."""
    def setUp(self):
        self.status=200;self.body=b'node_cpu_seconds_total{cpu="0"} 12\n';self.ctype='text/plain';self.encoding='identity';self.delay=0;self.paths=[]
        owner=self
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                owner.paths.append(self.path)
                try:
                    if owner.delay:time.sleep(owner.delay)
                    self.send_response(owner.status);self.send_header('Content-Type',owner.ctype);self.send_header('Content-Encoding',owner.encoding);self.send_header('Content-Length',str(len(owner.body)))
                    if owner.status==302:self.send_header('Location','http://192.0.2.99:1/forbidden')
                    self.end_headers();self.wfile.write(owner.body)
                except (BrokenPipeError,ConnectionResetError):pass
            def log_message(self,*args):pass
        self.server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join()
    def run_probe(self,preset='linux-node',**kw):
        p=m.PRESETS[preset];ep=f'http://127.0.0.1:{self.server.server_port}{p["path"]}';c=target(preset,endpoint=ep,**kw);t=c['targets'][0]
        return m.probe(c,t['id'],allow_endpoints=[ep],allow_targets=[t['target']])
    def test_actual_http_reads_once_and_does_not_claim_health(self):
        r=self.run_probe();self.assertEqual(r['probe_status'],'SAMPLE_VERIFIED_ONCE');self.assertEqual(self.paths,['/metrics']);self.assertFalse(r['health_verified']);self.assertFalse(r['continuous_collection_verified']);self.assertFalse(r['device_identity_verified'])
    def test_redirect_is_not_followed(self):
        self.status=302;r=self.run_probe();self.assertEqual(r['reason'],'HTTP_STATUS_REJECTED');self.assertEqual(len(self.paths),1)
    def test_authentication_error_is_failure_without_response_leak(self):
        self.status=401;self.body=b'secret';r=self.run_probe();self.assertEqual(r['probe_status'],'FAILED');self.assertNotIn('secret',json.dumps(r))
    def test_wrong_content_type_and_compression_rejected(self):
        self.ctype='text/html';self.assertEqual(self.run_probe()['reason'],'CONTENT_TYPE_REJECTED');self.ctype='text/plain';self.encoding='gzip';self.assertEqual(self.run_probe()['reason'],'ENCODING_REJECTED')
    def test_oversize_body_bounded(self):
        self.body=b'#'+b'x'*m.MAX_BYTES;self.assertEqual(self.run_probe()['reason'],'RESPONSE_TOO_LARGE')
    def test_downstream_query_is_explicit_and_encoded(self):
        self.body=b'probe_success 1\n';r=self.run_probe('http-service');self.assertEqual(r['probe_status'],'SAMPLE_VERIFIED_ONCE');self.assertIn('module=mop_http',self.paths[0]);self.assertIn('target=https%3A%2F%2F192.0.2.20%3A443%2F',self.paths[0])
    def test_backend_probe_zero_is_not_success(self):
        self.body=b'pg_up 0\n';self.assertEqual(self.run_probe('postgresql')['reason'],'TARGET_PROBE_FAILED')
    def test_snmp_request_uses_named_auth_not_community(self):
        self.body=b'ifHCInOctets{ifIndex="1"} 0\n';self.assertEqual(self.run_probe('network-snmp')['probe_status'],'SAMPLE_VERIFIED_ONCE');self.assertIn('auth=replace_with_configured_snmp_profile',self.paths[0])
    def test_total_deadline_rejects_delayed_headers(self):
        self.delay=2;start=time.monotonic();r=self.run_probe(timeout_seconds=1);self.assertEqual(r['probe_status'],'FAILED');self.assertLess(time.monotonic()-start,1.7)
    def test_tcp_connect_is_not_agent_or_health_success(self):
        c=target('zabbix-linux',endpoint=f'tcp://127.0.0.1:{self.server.server_port}');t=c['targets'][0];r=m.probe(c,t['id'],allow_endpoints=[t['endpoint']]);self.assertEqual(r['probe_status'],'TCP_REACHABLE_PROTOCOL_UNVERIFIED');self.assertFalse(r['health_verified'])

if __name__=='__main__':unittest.main()

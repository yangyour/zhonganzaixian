import importlib.util
from pathlib import Path
import unittest
from unittest.mock import MagicMock

spec=importlib.util.spec_from_file_location('provision',Path(__file__).with_name('configure-basic-network-monitoring.py'))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
D={'key':'sangfor-hci','ip':'192.168.1.170','brand':'测试','model':'synthetic','device_type':'超融合'}

class ProvisionTest(unittest.TestCase):
    def api(self,drift=None):
        api=MagicMock()
        def rpc(method,params):
            if method=='apiinfo.version':return '7.0.31'
            if method=='hostgroup.get':return [{'groupid':'1'}]
            if method=='host.get':return [{'hostid':'2','description':module.OWNER,'status':'0','hostgroups':[{'groupid':'1'}],'tags':[{'tag':'mop_scope','value':module.SCOPE,'automatic':'0'}]}]
            if method=='item.get':
                metric=next(x for x in module.metric_plan(D['ip']) if x['key_']==params['filter']['key_'][0])
                return [{'itemid':'3','type':'3','value_type':str(metric['value_type']),'delay':drift or '60s','history':'7d','status':'0','units':metric['units']}]
            raise AssertionError('Unexpected write')
        api.rpc.side_effect=rpc;return api
    def test_no_agent_snmp_or_login_metrics(self):
        plan=module.metric_plan(D['ip'])
        self.assertEqual(6,len(plan));self.assertEqual([3,0,0,3,3,3],[x['value_type'] for x in plan])
        self.assertTrue(all(D['ip'] in x['key_'] for x in plan))
        self.assertEqual('net.tcp.service[tcp,192.168.1.170,443]',plan[-1]['key_'])
    def test_default_plan_only_reads(self):
        api=self.api();result=module.provision(api,[D])
        self.assertFalse(result['apply']);self.assertFalse(result['notification_rules_created'])
        self.assertTrue(all(c.args[0].endswith('.get') or c.args[0]=='apiinfo.version' for c in api.rpc.call_args_list))
    def test_reapply_does_not_duplicate_or_rewrite(self):
        api=self.api();result=module.provision(api,[D],True)
        self.assertEqual('2',result['devices'][0]['provider_host_id'])
        self.assertTrue(all(c.args[0].endswith('.get') or c.args[0]=='apiinfo.version' for c in api.rpc.call_args_list))
    def test_drift_stops_without_overwrite(self):
        with self.assertRaises(ValueError):module.provision(self.api('5s'),[D],True)
    def test_wrong_version_stops_before_host_requests(self):
        api=MagicMock();api.rpc.return_value='8.0.0'
        with self.assertRaises(ValueError):module.provision(api,[D],True)
        self.assertEqual(1,api.rpc.call_count)
    def test_firewall_inclusion_preserves_three_existing_targets_and_corrected_address(self):
        import json
        inventory=json.loads((Path(__file__).resolve().parents[1]/'deploy/lan-test/devices.json').read_text(encoding='utf-8'))
        selected=[d for d in inventory['devices'] if d['key'] in module.KEYS]
        self.assertEqual(tuple(d['key'] for d in selected),module.KEYS)
        self.assertEqual('192.169.11.1',selected[0]['ip'])
        self.assertEqual(24,sum(len(module.metric_plan(d['ip'])) for d in selected))

if __name__=='__main__':unittest.main()

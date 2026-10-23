import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import demo_plan as demo

class DemoPlanTest(unittest.TestCase):
    def config(self):return {'version':1,'namespace_id':'79000000-0000-4000-8000-000000000090','username':'demo','anchor_time':'2026-10-22T12:00:00Z','deployment_mode':'dedicated'}
    def test_golden_is_reproducible(self):self.assertEqual(demo.prepare(self.config()),demo.read_json(demo.ROOT/'fixtures/demo-preparation-golden.json'))
    def test_pages_and_permission_union_are_not_runtime_acceptance(self):
        p=demo.prepare(self.config());self.assertEqual(43,p['coverage']['pages_declared']);self.assertEqual(48,p['coverage']['capability_union_count']);self.assertEqual(0,p['coverage']['pages_accepted']);self.assertFalse(p['coverage']['all_functions_ready']);self.assertTrue(all(f['acceptance_status']=='NOT_RUN' and not f['data_initialized'] and not f['interaction_verified'] for f in p['features']))
    def test_no_credentials_accounts_or_grants_are_created(self):
        p=demo.prepare(self.config())
        for key in ['account_created','credentials_created','permissions_granted','business_data_written','outbound_enabled']:self.assertIs(False,p[key])
        self.assertTrue(all(not c['active'] and not c['binding_verified'] for c in p['contexts']));self.assertNotIn('password',p)
    def test_protected_and_historical_namespaces_are_forbidden(self):
        for value in demo.catalog()['protected_tenant_ids']:
            c=self.config();c['namespace_id']=value
            with self.assertRaises(demo.Invalid):demo.prepare(c)
    def test_real_and_old_fixture_usernames_are_not_reused(self):
        for name in demo.RESERVED_USERS:
            c=self.config();c['username']=name
            with self.assertRaises(demo.Invalid):demo.prepare(c)
    def test_duplicate_json_and_unknown_credential_fields_fail(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'input.json';p.write_text('{"version":1,"version":1}')
            with self.assertRaises(demo.Invalid):demo.read_json(p)
        for key in ['password','token','tenant_id','endpoint','approved','active']:
            c=self.config();c[key]='override'
            with self.assertRaises(demo.Invalid):demo.prepare(c)
    def test_invalid_namespace_types_and_case_fail(self):
        for value in [None,{},'wrong','00000000-0000-0000-0000-000000000000','79000000-0000-4000-8000-00000000009A']:
            c=self.config();c['namespace_id']=value
            with self.assertRaises(demo.Invalid):demo.prepare(c)
    def test_anchor_time_is_explicit_and_deterministic(self):
        for value in [None,'2026-10-22','2026-10-22T12:00:00','2026-02-30T12:00:00Z','2100-01-01T00:00:00Z']:
            c=self.config();c['anchor_time']=value
            with self.assertRaises(demo.Invalid):demo.prepare(c)
    def test_deployment_choice_has_no_unsafe_default(self):
        for value in [None,'production','unknown']:
            c=self.config();c['deployment_mode']=value
            with self.assertRaises(demo.Invalid):demo.prepare(c)
        c=self.config();c['deployment_mode']='isolated_tenants';p=demo.prepare(c);self.assertIn('GLOBAL_POLICY_SCOPE_UNRESOLVED',p['blockers']);self.assertFalse(p['permissions_granted'])
    def test_contexts_and_entities_are_disjoint_and_stable(self):
        a=demo.prepare(self.config());b=demo.prepare(self.config());self.assertEqual(a['contexts'],b['contexts']);ids=[x['id'] for x in a['entities']];self.assertEqual(len(ids),len(set(ids)));self.assertEqual(3,len({c['tenant_id'] for c in a['contexts']}))
    def test_new_namespace_changes_ids_not_existing_objects(self):
        a=demo.prepare(self.config());c=self.config();c['namespace_id']='79000000-0000-4000-8000-000000000091';b=demo.prepare(c);self.assertTrue({x['id'] for x in a['entities']}.isdisjoint({x['id'] for x in b['entities']}))
    def test_request_bindings_are_same_context_and_pending(self):
        p=demo.prepare(self.config());entities={e['key']:e for e in p['entities']};contexts={c['key']:c['tenant_id'] for c in p['contexts']}
        for r in p['request_fixtures']:
            self.assertFalse(r['executed']);self.assertFalse(r['references_verified'])
            for binding in r['bindings']:self.assertEqual(contexts[r['context']],entities[binding['entity_key']]['tenant_id'])
    def test_shared_global_policy_is_never_claimed_available(self):
        p=demo.prepare(self.config());self.assertTrue(p['global_operation_constraints']);self.assertIn('GLOBAL_POLICY_SCOPE_UNRESOLVED',p['blockers']);self.assertIn('ROLE_SCENARIO_MODEL_NOT_CONFIRMED',p['blockers'])
    def test_source_drift_requires_review(self):
        cat=demo.catalog();cat['source_sha256']['apps/web/src/router.ts']='0'*64
        with patch.object(demo,'read_json',return_value=cat):
            with self.assertRaises(demo.Invalid):demo.catalog()
    def test_bundle_is_fresh_only_and_manifest_last(self):
        with tempfile.TemporaryDirectory() as d:
            p=demo.prepare(self.config());out=Path(d)/'new';demo.write_bundle(p,out);before=(out/'plan.json').read_bytes();self.assertEqual(p,json.loads(before))
            with self.assertRaises(FileExistsError):demo.write_bundle(p,out)
            self.assertEqual(before,(out/'plan.json').read_bytes())
    def test_rehashed_tampered_plan_still_cannot_be_written(self):
        with tempfile.TemporaryDirectory() as d:
            p=demo.prepare(self.config());p['request_fixtures'][0]['body']['password']='not-a-real-secret';p['plan_sha256']=demo.digest({k:v for k,v in p.items() if k!='plan_sha256'})
            with self.assertRaises(demo.Invalid):demo.write_bundle(p,Path(d)/'bad')
            self.assertFalse((Path(d)/'bad').exists())
    def test_symlink_output_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'real').mkdir();(root/'link').symlink_to(root/'real',target_is_directory=True)
            with self.assertRaises(demo.Invalid):demo.write_bundle(demo.prepare(self.config()),root/'link'/'new')
    def test_example_is_explicitly_unconfigured(self):
        with self.assertRaises(demo.Invalid):demo.prepare(demo.read_json(demo.ROOT/'deploy/examples/demo-plan.example.json'))

if __name__=='__main__':unittest.main()

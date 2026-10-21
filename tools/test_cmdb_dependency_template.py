import json
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
class CmdbDependencyTemplateTest(unittest.TestCase):
 def test_template_does_not_publish_or_invent_customer_decisions(self):
  d=json.loads((ROOT/'deploy/examples/cmdb-dependencies.json').read_text())
  self.assertEqual(1,d['schema_version']);self.assertEqual('customer-cmdb-v1-20261021',d['catalog_version'])
  self.assertIs(d['enabled'],False);self.assertIs(d['production_approved'],False)
  for k,v in d.items():
   if k not in {'schema_version','catalog_version','enabled','production_approved'}:self.assertIsNone(v,k)
 def test_all_known_mapping_and_source_conflicts_have_named_placeholders(self):
  d=json.loads((ROOT/'deploy/examples/cmdb-dependencies.json').read_text())
  for k in ['legacy_asset_type_crosswalk_reference','asset_identity_mapping_reference','fw_adc_monitoring_type_reference','import_ssh_collection_resolution','import_note_requiredness_resolution','l2_count_discrepancy_resolution','credential_reference_provider','ap_billing_contract_mode_reference','asset_lifecycle_transition_policy_reference']:
   self.assertIn(k,d)
if __name__=='__main__':unittest.main()

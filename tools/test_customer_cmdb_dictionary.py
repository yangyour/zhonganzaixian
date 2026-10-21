import hashlib
import json
import unittest
from pathlib import Path
from build_customer_cmdb_dictionary import ROOT,SOURCE,OUTPUTS,read_sheets,catalog,render

class CustomerCmdbDictionaryTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  cls.bytes=(ROOT/SOURCE).read_bytes();cls.catalog=catalog(cls.bytes)
 def test_generated_copies_are_exact_and_deterministic(self):
  expected=render(self.bytes)
  for path in OUTPUTS:self.assertEqual(expected,(ROOT/path).read_text())
 def test_no_other_type_and_all_sixteen_subtypes_are_retained(self):
  self.assertEqual({'SW','RT','FW','WL','SV','HC','VD','ST','DS'},{x['code'] for x in self.catalog['primary_types']})
  self.assertEqual(16,len(self.catalog['subtypes']));self.assertIn('fw_adc',{x['code'] for x in self.catalog['subtypes']})
 def test_source_positions_match_every_subtype(self):
  sheets=read_sheets(self.bytes)
  for item in self.catalog['subtypes']:
   row=next(r for r in sheets[item['source']['sheet']] if r['row']==item['source']['row'])
   self.assertEqual(item['code'],row['cells']['A']);self.assertEqual(item['parent'],row['cells']['C'])
 def test_attributes_are_unique_and_secret_values_not_in_catalog(self):
  attrs=self.catalog['attributes'];self.assertEqual(95,len(attrs));self.assertEqual(95,len({a['code'] for a in attrs}))
  cred=next(a for a in attrs if a['code']=='credential_ref');self.assertIn('不得出现在接口',cred['description']);self.assertNotIn('value',cred)
 def test_conflicts_stay_unresolved_not_silently_corrected(self):
  issues={x['code']:x for x in self.catalog['unresolved']};self.assertEqual(4,len(issues))
  self.assertEqual((15,16),(issues['L2_COUNT_CONFLICT']['declared'],issues['L2_COUNT_CONFLICT']['observed']))
  for issue in issues.values():self.assertIsNone(issue['resolution'])
 def test_ssh_is_not_invented_as_collection_method(self):
  choices={x['code'] for x in self.catalog['enum_values'] if x['field']=='collect_method'}
  self.assertEqual({'agent_active','agent_passive','snmpv3','http','sender'},choices)
 def test_source_is_not_runtime_activation(self):
  self.assertFalse(self.catalog['activated']);self.assertFalse(self.catalog['production_approved'])
  self.assertEqual(hashlib.sha256(self.bytes).hexdigest(),self.catalog['source']['sha256'])
 def test_source_change_is_rejected_even_if_zip_can_be_opened(self):
  with self.assertRaisesRegex(ValueError,'differs'):catalog(self.bytes+b'changed')
 def test_four_status_dimensions_are_distinct(self):
  fields={x['code'] for x in self.catalog['attributes']};self.assertTrue({'lifecycle_state','sync_state','health_state','billable'}<=fields)
  health={x['code'] for x in self.catalog['enum_values'] if x['field']=='health_state'};self.assertIn('unknown',health)
 def test_ap_weight_is_source_text_not_a_per_asset_rounding_rule(self):
  ap=next(x for x in self.catalog['subtypes'] if x['code']=='wl_ap');self.assertEqual('0.2',ap['billing_weight'])
  self.assertIn('ceil',json.dumps(self.catalog['source_sections']['09_计费折算'],ensure_ascii=False))
if __name__=='__main__':unittest.main()

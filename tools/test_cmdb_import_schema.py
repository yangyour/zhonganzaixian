import json,unittest
from build_cmdb_import_schema import render,ROOT,OUTPUT
class CmdbImportSchemaTest(unittest.TestCase):
 def test_generated_source_is_exact(self):self.assertEqual(render(),(ROOT/OUTPUT).read_text())
 def test_columns_only_no_customer_rows(self):
  j=json.loads(render());self.assertEqual(33,len(j['columns']));self.assertEqual('AG',j['columns'][-1]['letter'])
  self.assertEqual({'catalog_version','catalog_hash','source_blob','columns'},set(j))
  self.assertEqual(list(range(33)),[r['position'] for r in j['columns']])

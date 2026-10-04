"""Pure one-job proof guards. No credentials, process launch, signals or database operations."""
from pathlib import Path
import copy,importlib.util,sys,unittest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT/'tools/niko-test'))
spec=importlib.util.spec_from_file_location('export_page_proof',ROOT/'tools/niko-test/prepare-accepted-export-proof.py');proof=importlib.util.module_from_spec(spec);spec.loader.exec_module(proof)
class ExportPageProofGuardTest(unittest.TestCase):
 def control(self):
  identifier='66000000-0000-4000-8000-000000000001';marker='SYNTHETIC_EXPORT_E2E_'+identifier
  return {'completed':False,'ui_receipt_confirmed':True,'marker':marker,'accepted_scope':{'actor_id':identifier,'tenant_id':'66000000-0000-4000-8000-000000000002','role':'padmin','permission_version':1},'original':{'path':'/asset-export-jobs','key':identifier,'job_id':'66000000-0000-4000-8000-000000000003','body':{'expected_version':0,'mode':'PREPARE_ONLY_NO_PUBLICATION','filters':{'type':None,'status':None,'site_id':'','query':marker}}}}
 def test_only_fresh_confirmed_synthetic_target(self):
  c=self.control();self.assertEqual(c['original']['job_id'],proof.target(c)[0])
  for patch in ({'completed':True},{'ui_receipt_confirmed':False},{'marker':'real-data'}):
   with self.assertRaises(ValueError):proof.target({**c,**patch})
 def test_job_route_or_key_cannot_be_replaced(self):
  for field,value in [('job_id','../other'),('path','/assets'),('key','key\n')]:
   c=self.control();c['original'][field]=value
   with self.assertRaises(ValueError):proof.target(c)
 def test_preparation_mode_and_exact_filter_are_mandatory(self):
  for field,value in [('mode','PUBLISH'),('expected_version',True),('filters',{'type':None,'status':None,'site_id':None,'query':None})]:
   c=self.control();c['original']['body'][field]=value
   with self.assertRaises(ValueError):proof.target(c)
 def test_customer_or_malformed_scope_is_rejected(self):
  for field,value in [('role','viewer'),('permission_version',True),('permission_version',0),('actor_id','raw')]:
   c=self.control();c['accepted_scope'][field]=value
   with self.assertRaises((ValueError,AttributeError)):proof.target(c)
 def test_environment_projects_only_existing_database_configuration(self):
  settings={'SPRING_DATASOURCE_URL':'jdbc:postgresql://127.0.0.1:25432/mop_test','SPRING_DATASOURCE_USERNAME':'mop_test_runtime','SPRING_DATASOURCE_PASSWORD':'SYNTHETIC_NOT_A_CREDENTIAL','OBJECT_STORAGE_SECRET_KEY':'NOT_REAL'}
  e=proof.private_environment(settings,{'PATH':'test','HTTP_PROXY':'not-used','OBJECT_STORAGE_SECRET_KEY':'not-used','MOP_IMPORT_WORKER_ENABLED':'true'})
  self.assertNotIn('OBJECT_STORAGE_SECRET_KEY',e);self.assertNotIn('HTTP_PROXY',e);self.assertEqual('false',e['MOP_IMPORT_WORKER_ENABLED']);self.assertEqual('30000',e['MOP_EXPORT_PREPARATION_LEASE_MILLIS']);self.assertEqual('true',e['MOP_EXPORT_PREPARATION_WORKER_ENABLED'])
 def test_wrong_database_role_endpoint_and_empty_secret_fail_closed(self):
  for field,value in [('SPRING_DATASOURCE_URL','jdbc:postgresql://remote/mop_test'),('SPRING_DATASOURCE_USERNAME','migrator'),('SPRING_DATASOURCE_PASSWORD','')]:
   s={'SPRING_DATASOURCE_URL':'jdbc:postgresql://127.0.0.1:25432/mop_test','SPRING_DATASOURCE_USERNAME':'mop_test_runtime','SPRING_DATASOURCE_PASSWORD':'SYNTHETIC'};s[field]=value
   with self.assertRaises(ValueError):proof.private_environment(s,{})
 def test_postgresql_linewrapped_base64_decodes_exact_bytes(self):
  self.assertEqual(b'abcd',proof.decode_file('YWJj\nZA=='))
 def test_base64_does_not_ignore_unexpected_characters(self):
  for value in ['YWJj ZA==','YWJj\rZA==','YWJj!ZA==']:
   with self.assertRaises(ValueError):proof.decode_file(value)
 def test_nontext_and_oversized_base64_are_rejected(self):
  for value in [None,{},'A'*(24*1024*1024+1)]:
   with self.assertRaises(ValueError):proof.decode_file(value)
if __name__=='__main__':unittest.main()

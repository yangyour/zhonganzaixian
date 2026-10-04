"""Pure restart-evidence guards. Synthetic control data, no database or process operations."""
from pathlib import Path
import contextlib,copy,importlib.util,io,json,os,tempfile,unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parent.parent
class ExportQueueRestartTest(unittest.TestCase):
 def setUp(self):
  spec=importlib.util.spec_from_file_location('export_restart',ROOT/'tools/niko-test/export-queue-restart-snapshot.py');self.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.m)
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);base=Path(self.temp.name);self.m.BEFORE=base/'before.json';self.m.AFTER=base/'after.json'
  self.sample={'instance':{'postmaster_started_at':'before','server_version':'17.test'},'tables':{'test':{'rows':1,'equality_sha256':'a'*64}},'queue':{'queued':1,'total':1,'prepared':0,'stored_receipt_descriptions':0}}
 def run_main(self,mode,value):
  with patch.object(self.m.sys,'argv',['probe',mode]),patch.object(self.m,'capture',return_value=value) as capture,contextlib.redirect_stdout(io.StringIO()):self.m.main()
  return capture
 def before(self):self.m.BEFORE.write_text(json.dumps({'snapshot':self.sample}))
 def after_value(self):value=copy.deepcopy(self.sample);value['instance']['postmaster_started_at']='after';return value
 def test_record_is_exclusive_private_and_does_not_write_after(self):
  self.run_main('--record',self.sample);self.assertEqual(0o600,self.m.BEFORE.stat().st_mode&0o777);self.assertFalse(self.m.AFTER.exists());self.assertEqual(self.sample,json.loads(self.m.BEFORE.read_text())['snapshot'])
 def test_record_never_overwrites_existing_evidence(self):
  self.before();old=self.m.BEFORE.read_bytes()
  with self.assertRaises(RuntimeError):self.run_main('--record',self.sample)
  self.assertEqual(old,self.m.BEFORE.read_bytes())
 def test_verify_requires_before_evidence_before_database_capture(self):
  with patch.object(self.m,'capture') as capture,patch.object(self.m.sys,'argv',['probe','--verify']):
   with self.assertRaises(RuntimeError):self.m.main()
   capture.assert_not_called()
 def test_unchanged_rows_and_different_instance_can_be_recorded(self):
  self.before();self.run_main('--verify',self.after_value());self.assertTrue(self.m.AFTER.is_file());self.assertEqual(self.sample,json.loads(self.m.BEFORE.read_text())['snapshot'])
 def test_same_instance_never_counts_as_restart(self):
  self.before()
  with self.assertRaises(RuntimeError):self.run_main('--verify',self.sample)
  self.assertFalse(self.m.AFTER.exists())
 def test_row_hash_count_queue_or_server_change_rejects(self):
  self.before()
  for field in ['hash','count','queue','version']:
   value=self.after_value()
   if field=='hash':value['tables']['test']['equality_sha256']='b'*64
   if field=='count':value['tables']['test']['rows']=2
   if field=='queue':value['queue']['queued']=0
   if field=='version':value['instance']['server_version']='changed'
   with self.assertRaises(RuntimeError):self.run_main('--verify',value)
   self.assertFalse(self.m.AFTER.exists())
 def test_unknown_mode_does_not_capture(self):
  with patch.object(self.m,'capture') as capture,patch.object(self.m.sys,'argv',['probe','--restart']):
   with self.assertRaises(RuntimeError):self.m.main()
   capture.assert_not_called()
 def test_after_evidence_cannot_be_overwritten(self):
  self.before();self.m.AFTER.write_text('owned previous evidence')
  with self.assertRaises(RuntimeError):self.run_main('--verify',self.after_value())
  self.assertEqual('owned previous evidence',self.m.AFTER.read_text())
 def test_scope_remains_runtime_readonly_without_history_access_or_process_control(self):
  source=(ROOT/'tools/niko-test/export-queue-restart-snapshot.py').read_text();self.assertIn('REPEATABLE READ READ ONLY',source);self.assertIn("'mop_test_runtime'",source);self.assertNotIn('flyway_schema_history',source);self.assertNotIn('MOP_MIGRATION_PASSWORD',source);self.assertNotIn('kill(',source);self.assertEqual(('mop_asset_export_job','mop_asset_export_attempt','mop_idempotency','mop_audit'),self.m.TABLES)
if __name__=='__main__':unittest.main()

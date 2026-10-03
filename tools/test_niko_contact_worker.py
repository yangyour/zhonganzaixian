"""Isolated non-executable ZIP/process metadata guards; no real credentials, process signals or database calls."""
from pathlib import Path
import os,subprocess,sys,tempfile,unittest,zipfile
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/niko-test'))
import contact_worker_artifact as artifact
from owned_contact_worker import is_owned_contact_worker
class ContactWorkerGuardTest(unittest.TestCase):
 def fixture(self,base):
  root=base/'repo';(root/'.local/niko-test').mkdir(parents=True);source=root/'server/worker/target/mop-worker-0.1.0-SNAPSHOT-contact-imports.jar';source.parent.mkdir(parents=True);self.archive(source);return root,source
 def archive(self,path,start='cn.mop.contactworker.ContactImportWorkerApplication'):
  with zipfile.ZipFile(path,'w') as z:z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nStart-Class: '+start+'\n');z.writestr('BOOT-INF/classes/cn/mop/contactworker/ContactImportWorkerApplication.class',b'not executable synthetic test bytes')
 def test_pinned_contact_copy_does_not_follow_target_rebuild(self):
  with tempfile.TemporaryDirectory() as d:
   root,source=self.fixture(Path(d));pinned=artifact.prepare_artifact(root);original=pinned.read_bytes();source.write_bytes(b'rebuild in progress');self.assertEqual(original,pinned.read_bytes());self.assertEqual(pinned,artifact.verified_artifact(root,pinned));self.assertEqual(0o400,pinned.stat().st_mode&0o777)
 def test_photo_or_asset_import_manifest_cannot_masquerade_as_contact_worker(self):
  for main in ['cn.mop.worker.WorkerApplication','cn.mop.importworker.AssetImportWorkerApplication']:
   with tempfile.TemporaryDirectory() as d:
    root,source=self.fixture(Path(d));self.archive(source,main);self.assertRaises(ValueError,artifact.prepare_artifact,root)
 def test_relative_external_mutable_or_corrupt_artifact_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root,source=self.fixture(Path(d));pinned=artifact.prepare_artifact(root)
   for value in [source,'relative.jar',Path(d)/pinned.name]:self.assertRaises((ValueError,OSError),artifact.verified_artifact,root,value)
   pinned.chmod(0o600);self.assertRaises(ValueError,artifact.verified_artifact,root,pinned);pinned.write_bytes(b'changed');pinned.chmod(0o400);self.assertRaises(ValueError,artifact.verified_artifact,root,pinned)
 def test_source_symlink_and_shared_runtime_directory_rejected(self):
  with tempfile.TemporaryDirectory() as d:
   root,source=self.fixture(Path(d));other=source.with_name('held.jar');source.rename(other);source.symlink_to(other);self.assertRaises(ValueError,artifact.prepare_artifact,root);source.unlink();other.rename(source);pinned=artifact.prepare_artifact(root);pinned.parent.chmod(0o777);self.assertRaises(ValueError,artifact.verified_artifact,root,pinned)
 def test_only_exact_child_java_argv_exe_and_start_ticks_match(self):
  with tempfile.TemporaryDirectory() as d:
   base=Path(d);root,_=self.fixture(base);pinned=artifact.prepare_artifact(root);java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java';java.parent.mkdir(parents=True);java.write_text('non-executable');proc=base/'proc';child=proc/'902';child.mkdir(parents=True);fields=['S','901']+['0']*17+['12345'];(child/'stat').write_text('902 (java) '+' '.join(fields));(child/'exe').symlink_to(java);args=[str(java),'-Xms64m','-Xmx256m','-jar',str(pinned)];(child/'cmdline').write_bytes(('\0'.join(args)+'\0').encode());self.assertTrue(is_owned_contact_worker(902,901,root,proc,pinned,'12345'));self.assertFalse(is_owned_contact_worker(902,900,root,proc,pinned,'12345'));self.assertFalse(is_owned_contact_worker(902,901,root,proc,pinned,'12346'));(child/'cmdline').write_bytes(('\0'.join(args+['extra'])+'\0').encode());self.assertFalse(is_owned_contact_worker(902,901,root,proc,pinned,'12345'))
 def test_invalid_launch_and_unapproved_probe_mode_stop_before_private_read(self):
  with tempfile.TemporaryDirectory() as d:
   r=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/start-contact-worker.py'),'invalid','3000'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,r.returncode);self.assertIn('private configuration not loaded',r.stdout+r.stderr)
   r=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/verify-contact-worker.py'),'--unknown'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,r.returncode);self.assertFalse((Path(d)/'.local').exists())
 def test_worker_launch_environment_excludes_oidc_storage_and_migration_credentials(self):
  source=(ROOT/'tools/niko-test/start-contact-worker.py').read_text();self.assertNotIn('env=os.environ.copy()',source);self.assertIn("('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD')",source);self.assertIn("'SPRING_PROFILES_ACTIVE':'remote_test'",source)
if __name__=='__main__':unittest.main()

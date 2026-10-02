"""Isolated guard checks; no real processes, identities, components or credentials are changed."""
from pathlib import Path
import importlib.util,json,os,subprocess,sys,tempfile,unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
GUARD=module('niko_owned_api',ROOT/'tools/niko-test/owned_api.py')
class NikoFaultGuardTest(unittest.TestCase):
    def fixture(self,base,parent=901,command=None):
        root=base/'repo';root.mkdir();proc=base/'proc';child=proc/'902';child.mkdir(parents=True);java=base/'mop-toolchain/jdk-17.0.20.1+1/bin/java';java.parent.mkdir(parents=True);java.write_text('synthetic-non-executable')
        # stat index1 after the comm field is PPID; include spaces in comm to catch naive splitting.
        (child/'stat').write_text('902 (synthetic java worker) S '+str(parent)+' '+' '.join(['0']*20))
        cmd=command or [str(java),'-Xms128m','-Xmx512m','-jar',str(root/'server/api/target/mop-api-0.1.0-SNAPSHOT.jar')]
        (child/'cmdline').write_bytes(b'\0'.join(x.encode() for x in cmd)+b'\0');(child/'exe').symlink_to(java)
        return root,proc,child
    def test_exact_owned_api_identity_accepts_only_known_parent_and_command(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));self.assertTrue(GUARD.is_owned_api(902,901,root,proc));self.assertFalse(GUARD.is_owned_api(902,903,root,proc));self.assertFalse(GUARD.is_owned_api(1,901,root,proc));self.assertFalse(GUARD.is_owned_api(999,901,root,proc))
            with patch.object(GUARD.os,'getuid',return_value=root.stat().st_uid+1):self.assertFalse(GUARD.is_owned_api(902,901,root,proc))
    def test_other_program_or_repo_is_never_a_signal_target(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));original=(child/'cmdline').read_bytes();(child/'cmdline').write_bytes(original.replace(b'mop-api-0.1.0-SNAPSHOT.jar',b'unrelated.jar'));self.assertFalse(GUARD.is_owned_api(902,901,root,proc));(child/'cmdline').write_bytes(original+b'--extra\0');self.assertFalse(GUARD.is_owned_api(902,901,root,proc))
    def test_missing_or_invalid_process_metadata_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));(child/'stat').write_text('invalid');self.assertFalse(GUARD.is_owned_api(902,901,root,proc));(child/'stat').unlink();self.assertFalse(GUARD.is_owned_api(902,901,root,proc))
    def test_listener_must_belong_to_exact_process_and_loopback_port(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));(proc/'net').mkdir();(child/'fd').mkdir()
            (proc/'net/tcp').write_text('header\n0: 0100007F:1F90 00000000:0000 0A 0 0 0 0 0 12345\n')
            self.assertFalse(GUARD.owns_loopback_listener(902,proc));(child/'fd/5').symlink_to('socket:[12345]');self.assertTrue(GUARD.owns_loopback_listener(902,proc))
            (proc/'net/tcp').write_text('header\n0: 00000000:1F90 00000000:0000 0A 0 0 0 0 0 12345\n');self.assertFalse(GUARD.owns_loopback_listener(902,proc))
    def test_listening_on_wrong_port_or_only_established_connection_is_not_ready(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));(proc/'net').mkdir();(child/'fd').mkdir();(child/'fd/5').symlink_to('socket:[12345]')
            for address,state in [('0100007F:1F91','0A'),('0100007F:1F90','01')]:
                (proc/'net/tcp').write_text('header\n0: '+address+' 00000000:0000 '+state+' 0 0 0 0 0 12345\n');self.assertFalse(GUARD.owns_loopback_listener(902,proc))
    def test_run_stack_rejects_unknown_mode_before_any_component_or_private_read(self):
        with tempfile.TemporaryDirectory() as d:
            result=subprocess.run(['bash',str(ROOT/'tools/niko-test/run-stack.sh'),'--unapproved-mode'],cwd=d,capture_output=True,text=True);self.assertEqual(2,result.returncode);self.assertFalse((Path(d)/'.local').exists())
    def test_fault_evidence_collision_stops_before_launch_or_private_read(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);local=root/'.local/niko-test';local.mkdir(parents=True);record=local/'api-crash-http-before.json';record.write_text('synthetic-existing-evidence');result=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/api-crash-snapshot.py'),'--check-new-run'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,result.returncode);self.assertEqual('synthetic-existing-evidence',record.read_text());self.assertNotIn('synthetic-existing-evidence',result.stdout+result.stderr)
    def test_snapshot_unknown_mode_never_reads_or_creates_private_config(self):
        with tempfile.TemporaryDirectory() as d:
            result=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/api-crash-snapshot.py'),'--unknown'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,result.returncode);self.assertFalse((Path(d)/'.local').exists())
    def test_private_checkpoint_writer_refuses_overwrite(self):
        module_value=module('niko_crash_snapshot',ROOT/'tools/niko-test/api-crash-snapshot.py')
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'record.json';module_value.private_write(path,{'synthetic':True});self.assertEqual(0o600,path.stat().st_mode&0o777);original=path.read_bytes();self.assertRaises(FileExistsError,module_value.private_write,path,{'other':True});self.assertEqual(original,path.read_bytes())
if __name__=='__main__':unittest.main()

"""Isolated guard checks; no real processes, identities, components or credentials are changed."""
from pathlib import Path
import importlib.util,json,os,subprocess,sys,tempfile,unittest
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'tools/niko-test'))
import api_artifact,zipfile
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
GUARD=module('niko_owned_api',ROOT/'tools/niko-test/owned_api.py')
class NikoFaultGuardTest(unittest.TestCase):
    def fixture(self,base,parent=901,command=None):
        root=base/'repo';root.mkdir();proc=base/'proc';child=proc/'902';child.mkdir(parents=True);java=base/'mop-toolchain/jdk-17.0.20.1+1/bin/java';java.parent.mkdir(parents=True);java.write_text('synthetic-non-executable')
        # stat index1 after the comm field is PPID; include spaces in comm to catch naive splitting.
        (child/'stat').write_text('902 (synthetic java worker) S '+str(parent)+' '+' '.join(['0']*20))
        local=root/'.local/niko-test';local.mkdir(parents=True);source=root/'server/api/target/mop-api-0.1.0-SNAPSHOT.jar';source.parent.mkdir(parents=True)
        with zipfile.ZipFile(source,'w') as z:
            z.writestr('META-INF/MANIFEST.MF','synthetic');z.writestr('BOOT-INF/classes/cn/mop/api/MopApplication.class',b'synthetic')
        artifact=api_artifact.prepare_artifact(root)
        cmd=command or [str(java),'-Xms128m','-Xmx512m','-jar',str(artifact)]
        (child/'cmdline').write_bytes(b'\0'.join(x.encode() for x in cmd)+b'\0');(child/'exe').symlink_to(java)
        return root,proc,child
    def artifact(self,root):return next((root/'.local/niko-test/runtime-artifacts').glob('api-*/*.jar'))
    def test_exact_owned_api_identity_accepts_only_known_parent_and_command(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));self.assertTrue(GUARD.is_owned_api(902,901,root,proc,artifact=self.artifact(root)));self.assertFalse(GUARD.is_owned_api(902,903,root,proc,artifact=self.artifact(root)));self.assertFalse(GUARD.is_owned_api(1,901,root,proc,artifact=self.artifact(root)));self.assertFalse(GUARD.is_owned_api(999,901,root,proc,artifact=self.artifact(root)))
            with patch.object(GUARD.os,'getuid',return_value=root.stat().st_uid+1):self.assertFalse(GUARD.is_owned_api(902,901,root,proc,artifact=self.artifact(root)))
    def test_other_program_or_repo_is_never_a_signal_target(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));original=(child/'cmdline').read_bytes();(child/'cmdline').write_bytes(original.replace(b'.jar',b'-unrelated.jar'));self.assertFalse(GUARD.is_owned_api(902,901,root,proc,artifact=self.artifact(root)));(child/'cmdline').write_bytes(original+b'--extra\0');self.assertFalse(GUARD.is_owned_api(902,901,root,proc,artifact=self.artifact(root)))
    def test_missing_or_invalid_process_metadata_fails_closed(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));(child/'stat').write_text('invalid');self.assertFalse(GUARD.is_owned_api(902,901,root,proc,artifact=self.artifact(root)));(child/'stat').unlink();self.assertFalse(GUARD.is_owned_api(902,901,root,proc,artifact=self.artifact(root)))
    def test_missing_pinned_artifact_never_authorizes_a_process(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));self.assertFalse(GUARD.is_owned_api(902,901,root,proc))
            self.assertFalse(GUARD.is_owned_api(902,901,root,proc,artifact=root/'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'))
    def test_changed_pinned_bytes_fail_identity_even_with_matching_command(self):
        with tempfile.TemporaryDirectory() as d:
            root,proc,child=self.fixture(Path(d));pinned=self.artifact(root);pinned.chmod(0o600);pinned.write_bytes(b'changed runtime');pinned.chmod(0o400)
            self.assertFalse(GUARD.is_owned_api(902,901,root,proc,artifact=pinned))
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
    def test_v10_scenario_whitelists_fourteen_tables_and_keeps_original_evidence_separate(self):
        original=module('niko_original_snapshot_scope',ROOT/'tools/niko-test/api-crash-snapshot.py');latest=module('niko_v10_snapshot_scope',ROOT/'tools/niko-test/api-v10-crash-snapshot.py')
        self.assertEqual(11,len(original.TABLES));self.assertEqual(14,len(latest.TABLES));self.assertEqual(set(original.TABLES)|{'mop_asset_manual_details','mop_asset_manual_revision','mop_ticket_internal_note'},set(latest.TABLES))
        self.assertTrue({original.BEFORE,original.AFTER,original.HTTP,original.FINAL}.isdisjoint({latest.BEFORE,latest.AFTER,latest.HTTP,latest.FINAL}))
    def test_v10_checkpoint_collision_refuses_overwrite_before_any_private_read(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);local=root/'.local/niko-test';local.mkdir(parents=True);record=local/'api-v10-crash-http-before.json';record.write_text('synthetic-v10-existing')
            result=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/api-v10-crash-snapshot.py'),'--check-new-run'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,result.returncode);self.assertEqual('synthetic-v10-existing',record.read_text());self.assertNotIn('synthetic-v10-existing',result.stdout+result.stderr)
    def test_v10_unknown_mode_does_not_open_configuration_or_create_files(self):
        with tempfile.TemporaryDirectory() as d:
            result=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/api-v10-crash-snapshot.py'),'--unknown'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,result.returncode);self.assertFalse((Path(d)/'.local').exists())
    def test_original_checkpoint_does_not_silently_become_v10_checkpoint(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);local=root/'.local/niko-test';local.mkdir(parents=True);old=local/'api-crash-http-before.json';old.write_text('original-preserved')
            result=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/api-v10-crash-snapshot.py'),'--check-new-run'],cwd=d,capture_output=True,text=True);self.assertEqual(0,result.returncode);self.assertEqual('original-preserved',old.read_text());self.assertFalse((local/'api-v10-crash-http-before.json').exists())
    def test_v14_scope_adds_six_explicit_tables_and_keeps_old_evidence_separate(self):
        older=module('niko_v10_old_scope',ROOT/'tools/niko-test/api-v10-crash-snapshot.py');latest=module('niko_v14_scope',ROOT/'tools/niko-test/api-v14-crash-snapshot.py')
        self.assertEqual(20,len(latest.TABLES));self.assertEqual(len(latest.TABLES),len(set(latest.TABLES)))
        self.assertEqual(set(older.TABLES)|{'mop_ticket_comment','mop_working_calendar_draft','mop_working_calendar_revision','mop_manual_incident_registration','mop_manual_recovery_confirmation','mop_configuration'},set(latest.TABLES))
        self.assertTrue({older.BEFORE,older.AFTER,older.HTTP,older.FINAL}.isdisjoint({latest.BEFORE,latest.AFTER,latest.HTTP,latest.FINAL}))
    def test_v14_checkpoint_collision_prevents_any_new_fault_or_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            local=Path(d)/'.local/niko-test';local.mkdir(parents=True);record=local/'api-v14-crash-http-before.json';record.write_text('existing-v14-proof')
            result=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/api-v14-crash-snapshot.py'),'--check-new-run'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,result.returncode);self.assertEqual('existing-v14-proof',record.read_text());self.assertNotIn('existing-v14-proof',result.stdout+result.stderr)
    def test_v14_unknown_mode_never_reads_private_settings(self):
        with tempfile.TemporaryDirectory() as d:
            result=subprocess.run([sys.executable,str(ROOT/'tools/niko-test/api-v14-crash-snapshot.py'),'--unknown'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,result.returncode);self.assertFalse((Path(d)/'.local').exists())
    def test_v14_mode_is_explicit_and_reuses_owned_process_guard(self):
        source=(ROOT/'tools/niko-test/run-stack.sh').read_text();self.assertIn("--verify-owned-v14-api-crash",source);self.assertIn("tools/niko-test/api-v14-crash-snapshot.py",source);self.assertIn('owned_child "$API_PID"',source);self.assertIn('--require-listener "$API_PID" "$$" "$API_ARTIFACT"',source);self.assertIn('[[ "$API_EXIT" == 137 ]]',source)
    def test_warranty_fingerprint_scope_excludes_only_additive_fields_and_is_readonly(self):
        latest=module('niko_warranty_snapshot',ROOT/'tools/niko-test/warranty-migration-snapshot.py');prior=module('niko_warranty_prior_scope',ROOT/'tools/niko-test/api-v14-crash-snapshot.py')
        self.assertEqual(prior.TABLES,latest.TABLES);source=(ROOT/'tools/niko-test/warranty-migration-snapshot.py').read_text()
        self.assertIn("if table=='mop_asset_manual_revision' else 'to_jsonb(t)::text'",source);self.assertIn("ARRAY['warranty_start','warranty_supplier','warranty_details_declared']",source);self.assertIn('REPEATABLE READ READ ONLY',source);self.assertIn('ROLLBACK;',source)
        self.assertTrue({latest.BEFORE,latest.AFTER}.isdisjoint({prior.BEFORE,prior.AFTER,prior.HTTP,prior.FINAL}))
    def test_warranty_record_collision_and_unknown_mode_stop_before_configuration_access(self):
        script=ROOT/'tools/niko-test/warranty-migration-snapshot.py'
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);r=subprocess.run([sys.executable,str(script),'--unknown'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,r.returncode);self.assertFalse((root/'.local').exists())
            local=root/'.local/niko-test';local.mkdir(parents=True);p=local/'warranty-v15-legacy-before.json';p.write_text('existing-evidence')
            r=subprocess.run([sys.executable,str(script),'--record'],cwd=d,capture_output=True,text=True);self.assertNotEqual(0,r.returncode);self.assertEqual('existing-evidence',p.read_text());self.assertNotIn('existing-evidence',r.stdout+r.stderr)
if __name__=='__main__':unittest.main()

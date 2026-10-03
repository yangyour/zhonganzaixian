"""Isolated configuration-file tests; no component or account is created."""
from pathlib import Path
import json,subprocess,sys,tempfile,unittest,stat,hashlib
ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/'tools/niko-test/prepare-private.py'
class NikoPrivateBootstrapTest(unittest.TestCase):
 def test_explicit_flag_required_before_any_write(self):
  with tempfile.TemporaryDirectory() as d:
   r=subprocess.run([sys.executable,str(SCRIPT)],cwd=d,capture_output=True,text=True)
   self.assertNotEqual(0,r.returncode);self.assertFalse((Path(d)/'.local').exists())
 def test_new_private_files_are_restricted_and_secrets_never_printed(self):
  with tempfile.TemporaryDirectory() as d:
   r=subprocess.run([sys.executable,str(SCRIPT),'--create-approved-local-test-credentials'],cwd=d,capture_output=True,text=True)
   self.assertEqual(0,r.returncode);p=Path(d)/'.local/niko-test/private/bootstrap.json';data=json.loads(p.read_text())
   self.assertEqual(6,len(data['test_users']));self.assertEqual(0o600,stat.S_IMODE(p.stat().st_mode));self.assertEqual(0o700,stat.S_IMODE(p.parent.stat().st_mode))
   for secret in list(data['component_credentials'].values())+[u['password'] for u in data['test_users']]:self.assertNotIn(secret,r.stdout+r.stderr)
 def test_existing_credentials_are_never_rotated_implicitly(self):
  with tempfile.TemporaryDirectory() as d:
   args=[sys.executable,str(SCRIPT),'--create-approved-local-test-credentials'];subprocess.run(args,cwd=d,capture_output=True,check=True)
   p=Path(d)/'.local/niko-test/private/bootstrap.json';before=p.read_bytes();r=subprocess.run(args,cwd=d,capture_output=True)
   self.assertNotEqual(0,r.returncode);self.assertEqual(before,p.read_bytes())
 def test_all_six_migration_hashes_match_frozen_sources(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-migrations.json').read_text())['sha256'];self.assertEqual(6,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_independently_applied_v7_hash_is_frozen_separately(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-dependency-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_independently_applied_v8_hash_is_frozen_separately(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-worklog-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_independently_applied_v9_hash_is_frozen_separately(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-asset-metadata-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_v10_internal_notes_hash_is_separate_from_frozen_v1_to_v9(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-internal-note-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_v11_comment_hash_is_separate_from_frozen_v1_to_v10(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-ticket-comment-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_v12_calendar_hash_is_separate_from_frozen_v1_to_v11(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-working-calendar-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_v13_manual_incident_hash_is_separate_from_frozen_v1_to_v12(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-manual-incident-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_v14_recovery_hash_is_separate_from_frozen_v1_to_v13(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-manual-recovery-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_v15_warranty_hash_and_guard_are_pinned_separately(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-manual-warranty-migration.json').read_text());self.assertEqual(1,len(manifest['sha256']))
  for name,expected in manifest['sha256'].items():
   raw=(ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes();self.assertEqual(expected,hashlib.sha256(raw).hexdigest())
   body=raw.decode().split(' AS $$',1)[1].split('$$;',1)[0];self.assertEqual(manifest['guard_body_sha256'],hashlib.sha256(body.encode()).hexdigest())
 def test_migration_unknown_or_combined_modes_fail_before_private_or_component_access(self):
  script=ROOT/'tools/niko-test/run-migration.py'
  for args in [[],['--apply'],['--check','--apply-manual-warranty'],['--unknown']]:
   with tempfile.TemporaryDirectory() as d:
    r=subprocess.run([sys.executable,str(script),*args],cwd=d,capture_output=True,text=True)
    self.assertNotEqual(0,r.returncode);self.assertIn('Explicit migration mode required',r.stderr);self.assertFalse((Path(d)/'.local').exists())
 def test_v16_capacity_scenarios_hash_is_separate_from_frozen_previous_migrations(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-capacity-scenario-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  for name,expected in manifest.items():self.assertEqual(expected,hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest())
 def test_database_identity_only_flag_creates_no_storage_credentials(self):
  with tempfile.TemporaryDirectory() as d:
   r=subprocess.run([sys.executable,str(SCRIPT),'--create-approved-database-identity-credentials'],cwd=d,capture_output=True,text=True)
   self.assertEqual(0,r.returncode);p=Path(d)/'.local/niko-test/private/bootstrap.json';data=json.loads(p.read_text())
   self.assertEqual({'pg_admin_password','pg_migrator_password','pg_runtime_password','kc_db_password','kc_admin_password','oidc_client_secret'},set(data['component_credentials']))
   self.assertEqual(6,len(data['test_users']));self.assertEqual(0o600,stat.S_IMODE(p.stat().st_mode))
   for secret in list(data['component_credentials'].values())+[u['password'] for u in data['test_users']]:self.assertNotIn(secret,r.stdout+r.stderr)
 def test_narrow_flag_cannot_expand_or_overwrite_existing_configuration(self):
  with tempfile.TemporaryDirectory() as d:
   subprocess.run([sys.executable,str(SCRIPT),'--create-approved-database-identity-credentials'],cwd=d,capture_output=True,check=True)
   p=Path(d)/'.local/niko-test/private/bootstrap.json';before=p.read_bytes()
   for flag in ['--create-approved-database-identity-credentials','--create-approved-local-test-credentials']:
    result=subprocess.run([sys.executable,str(SCRIPT),flag],cwd=d,capture_output=True)
    self.assertNotEqual(0,result.returncode);self.assertEqual(before,p.read_bytes())
 def test_v17_contacts_have_separate_reviewed_hash_and_explicit_mode(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-customer-contact-migration.json').read_text())['sha256'];self.assertEqual(1,len(manifest))
  name='V17__customer_contact_revisions.sql';self.assertEqual(hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest(),manifest[name])
  source=(ROOT/'tools/niko-test/run-migration.py').read_text();self.assertIn('--apply-customer-contacts',source);self.assertIn('frozen-customer-contact-migration.json',source)

if __name__=='__main__':unittest.main()

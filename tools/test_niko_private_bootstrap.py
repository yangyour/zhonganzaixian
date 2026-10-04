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

 def test_v18_contact_import_has_explicit_mode_and_frozen_resource(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-contact-import-migration.json').read_text());name='V18__durable_contact_import_jobs.sql'
  self.assertEqual({name},set(manifest['sha256']));self.assertEqual(hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest(),manifest['sha256'][name]);self.assertEqual(2,len(manifest['function_body_sha256']))
  source=(ROOT/'tools/niko-test/run-migration.py').read_text();self.assertIn('--apply-contact-imports',source);self.assertIn('frozen-contact-import-migration.json',source)

 def test_v19_guard_fix_is_independent_and_v18_stays_frozen(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-contact-import-fix-migration.json').read_text());name='V19__contact_import_generated_hash_guard.sql';self.assertEqual({name},set(manifest['sha256']));self.assertEqual(hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration'/name).read_bytes()).hexdigest(),manifest['sha256'][name]);self.assertEqual(1,len(manifest['function_body_sha256']))
  self.assertEqual('75325f0423f788d5a71b71e0b1c8ba3b5c30e628aa3c588437f671954cbb39e9',hashlib.sha256((ROOT/'server/persistence/src/main/resources/db/migration/V18__durable_contact_import_jobs.sql').read_bytes()).hexdigest());self.assertIn('--apply-contact-import-fix',(ROOT/'tools/niko-test/run-migration.py').read_text())

class MigrationManifestParityTest(unittest.TestCase):
 def test_both_entrypoints_cover_exactly_all_frozen_migration_files(self):
  import re
  actual={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'server/persistence/src/main/resources/db/migration').glob('*.sql')}
  for entrypoint in ['deploy/migration-policy.ps1','tools/niko-test/run-migration.py']:
   text=(ROOT/entrypoint).read_text();names=re.findall(r"frozen-[a-z-]+\.json",text);self.assertEqual(len(names),len(set(names)));expected={}
   for name in names:
    values=json.loads((ROOT/'tools/niko-test'/name).read_text())['sha256'];self.assertTrue(set(values).isdisjoint(expected));expected.update(values)
   self.assertEqual(actual,expected);self.assertEqual(set(range(1,27)),{int(name.split('__')[0][1:]) for name in expected})
 def test_v20_remains_frozen_and_both_launchers_require_explicit_feature(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-asset-export-migration.json').read_text());self.assertEqual({'V20__durable_asset_export_jobs.sql':'5f8114dcdd2bd1cdf0c60fb75a5b521175462f583786602cd2b4a1835f960a83'},manifest['sha256']);self.assertEqual(3,len(manifest['function_body_sha256']))
  for path in ['deploy/migration-policy.ps1','tools/niko-test/run-migration.py']:self.assertIn('--apply-asset-exports',(ROOT/path).read_text())
  windows=(ROOT/'deploy/migrate-test-schema.ps1').read_text();self.assertIn('AssetExportSchemaVerifier.class',windows);self.assertIn('$exportVerifierSource',windows)

 def test_inspection_template_migration_is_explicit_and_windows_verifier_is_fresh(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-inspection-template-migration.json').read_text());self.assertEqual(1,len(manifest['sha256']));self.assertEqual(2,len(manifest['function_body_sha256']))
  for path in ['deploy/migration-policy.ps1','tools/niko-test/run-migration.py']:self.assertIn('--apply-inspection-templates',(ROOT/path).read_text())
  windows=(ROOT/'deploy/migrate-test-schema.ps1').read_text();self.assertIn('InspectionTemplateSchemaVerifier.class',windows);self.assertIn('(Get-Item -LiteralPath $inspectionVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $inspectionVerifierSource).LastWriteTimeUtc',windows)

 def test_worksheet_migration_mode_resource_and_windows_freshness_are_explicit(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-inspection-worksheet-migration.json').read_text());self.assertEqual(1,len(manifest['sha256']));self.assertEqual(2,len(manifest['function_body_sha256']))
  for path in ['deploy/migration-policy.ps1','tools/niko-test/run-migration.py']:self.assertIn('--apply-inspection-worksheets',(ROOT/path).read_text())
  windows=(ROOT/'deploy/migrate-test-schema.ps1').read_text();self.assertIn('InspectionWorksheetSchemaVerifier.class',windows);self.assertIn('(Get-Item -LiteralPath $worksheetVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $worksheetVerifierSource).LastWriteTimeUtc',windows)

 def test_source_collection_explicit_mode_and_verifier_freshness(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-inspection-source-collection-migration.json').read_text());self.assertEqual(1,len(manifest['sha256']));self.assertEqual(3,len(manifest['function_body_sha256']))
  for path in ['deploy/migration-policy.ps1','tools/niko-test/run-migration.py']:self.assertIn('--apply-inspection-source-collections',(ROOT/path).read_text())
  windows=(ROOT/'deploy/migrate-test-schema.ps1').read_text();self.assertIn('InspectionSourceCollectionSchemaVerifier.class',windows);self.assertIn('(Get-Item -LiteralPath $sourceCollectionVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $sourceCollectionVerifierSource).LastWriteTimeUtc',windows)


 def test_backup_plan_migration_has_explicit_mode_and_fresh_windows_verifier(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-backup-plan-migration.json').read_text());self.assertEqual(1,len(manifest['sha256']));self.assertEqual(4,len(manifest['function_body_sha256']))
  for path in ['deploy/migration-policy.ps1','tools/niko-test/run-migration.py']:self.assertIn('--apply-backup-plan-drafts',(ROOT/path).read_text())
  windows=(ROOT/'deploy/migrate-test-schema.ps1').read_text();self.assertIn('BackupPlanSchemaVerifier.class',windows);self.assertIn('(Get-Item -LiteralPath $backupPlanVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $backupPlanVerifierSource).LastWriteTimeUtc',windows)

 def test_change_notice_migration_has_explicit_mode_and_fresh_windows_verifier(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-change-notice-migration.json').read_text());self.assertEqual(1,len(manifest['sha256']));self.assertEqual(4,len(manifest['function_body_sha256']))
  for path in ['deploy/migration-policy.ps1','tools/niko-test/run-migration.py']:self.assertIn('--apply-change-notice-drafts',(ROOT/path).read_text())
  windows=(ROOT/'deploy/migrate-test-schema.ps1').read_text();self.assertIn('ChangeNoticeSchemaVerifier.class',windows);self.assertIn('(Get-Item -LiteralPath $changeNoticeVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $changeNoticeVerifierSource).LastWriteTimeUtc',windows)

 def test_notification_preference_migration_has_explicit_mode_and_fresh_windows_verifier(self):
  manifest=json.loads((ROOT/'tools/niko-test/frozen-notification-preference-migration.json').read_text());self.assertEqual(1,len(manifest['sha256']));self.assertEqual(3,len(manifest['function_body_sha256']))
  for path in ['deploy/migration-policy.ps1','tools/niko-test/run-migration.py']:self.assertIn('--apply-notification-preference-drafts',(ROOT/path).read_text())
  windows=(ROOT/'deploy/migrate-test-schema.ps1').read_text();self.assertIn('NotificationPreferenceSchemaVerifier.class',windows);self.assertIn('(Get-Item -LiteralPath $notificationPreferenceVerifierClass).LastWriteTimeUtc -lt (Get-Item -LiteralPath $notificationPreferenceVerifierSource).LastWriteTimeUtc',windows)

if __name__=='__main__':unittest.main()

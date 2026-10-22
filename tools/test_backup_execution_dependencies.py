"""Backup dependency slots do not activate runtime execution."""
import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
class BackupExecutionDependenciesTest(unittest.TestCase):
    def test_inactive_and_no_credential_or_endpoint_supplied(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        self.assertEqual(d['purpose'],'dependency_shape_only_not_a_runtime_publication')
        self.assertIs(d['backup_execution_enabled'],False)
        self.assertIs(d['production_approved'],False)
        for k,v in d.items():
            if k.endswith('_reference') or k=='tenant_id': self.assertIsNone(v,k)
    def test_device_coordination_and_reconciliation_have_no_guessed_configuration(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        for key in ['device_coordination_policy_reference','trusted_clock_and_journal_reference','expired_execution_stop_verifier_reference','physical_fencing_or_stop_protocol_reference']:
            self.assertIn(key,d)
            self.assertIsNone(d[key])
        self.assertIs(d['backup_execution_enabled'],False)

    def test_archive_staging_requires_explicit_proofs_and_capacity_policy(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        for key in ['archive_staging_authorizer_reference','archive_no_dispatch_proof_verifier_reference','archive_original_object_verifier_reference','encrypted_staging_capacity_and_retention_reference']:
            self.assertIn(key,d)
            self.assertIsNone(d[key])
        self.assertIs(d['backup_execution_enabled'],False)

    def test_atomic_registration_keeps_authorization_and_collection_proof_unconfigured(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        for key in ['backup_version_registration_authorizer_reference','backup_collection_completion_evidence_verifier_reference','backup_version_registration_binding_reference']:
            self.assertIn(key,d)
            self.assertIsNone(d[key])
        self.assertIs(d['backup_execution_enabled'],False)
        self.assertIs(d['production_approved'],False)

    def test_all_runtime_slots_are_existing_unpublished_keys(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        keys={s['key'] for s in json.loads((ROOT/'contracts/config-baseline.json').read_text())['settings']}
        self.assertGreater(len(d['runtime_publications']),0)
        for k,v in d['runtime_publications'].items():
            self.assertIn(k,keys)
            self.assertIsNone(v,k)
    def test_inventory_service_binding_stays_unconfigured(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        self.assertIn('backup_version_inventory_binding_reference',d)
        self.assertIsNone(d['backup_version_inventory_binding_reference'])
        self.assertIsNone(d['backup_version_inventory_authorizer_reference'])
        self.assertIs(d['backup_execution_enabled'],False)
    def test_archive_dispatch_consumer_binding_stays_unconfigured(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        self.assertIn('archive_dispatch_consumer_binding_reference',d)
        self.assertIsNone(d['archive_dispatch_consumer_binding_reference'])
        self.assertIsNone(d['archive_staging_authorizer_reference'])
        self.assertIsNone(d['archive_original_object_verifier_reference'])
        self.assertIs(d['backup_execution_enabled'],False)
    def test_version_consumer_binding_does_not_enable_runtime_or_replace_proofs(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        for key in ['version_registration_consumer_binding_reference','backup_version_registration_binding_reference','backup_version_registration_authorizer_reference','backup_collection_completion_evidence_verifier_reference','archive_original_object_verifier_reference']:
            self.assertIn(key,d)
            self.assertIsNone(d[key])
        self.assertIs(d['backup_execution_enabled'],False)
        self.assertIs(d['production_approved'],False)
if __name__=='__main__': unittest.main()

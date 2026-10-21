import copy
import json
import unittest
from dependency_template_check import ROOT,FILES,TemplateError,check_template,report
class DependencyTemplateCheckTest(unittest.TestCase):
    def setUp(self):
        self.settings={r['key']:r for r in json.loads((ROOT/'contracts/config-baseline.json').read_text())['settings']}
        self.base=json.loads((ROOT/'deploy/examples/remote-session-dependencies.json').read_text())
    def test_all_examples_valid_but_never_ready_or_authorized(self):
        value=report();self.assertTrue(value['shape_valid']);self.assertFalse(value['ready']);self.assertFalse(value['production_approved']);self.assertFalse(value['external_connections_checked']);self.assertEqual(len(FILES),len(value['modules']))
        for row in value['modules']:self.assertFalse(row['ready']);self.assertFalse(row['execution_authorized']);self.assertGreater(row['unresolved_reference_slots'],0)
    def test_fixed_backup_constraints_are_not_editable_slots(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        self.assertEqual(set(d['fixed_registry_keys']),{'backup.min_retention_months','backup.failure_ticket_count','backup.drill_warning_days'})
        for key in d['fixed_registry_keys']:self.assertNotIn(key,d['runtime_publications'])
    def test_rejects_enabled_approved_or_targeted_examples(self):
        for key,value in [('bastion_session_enabled',True),('production_approved',True),('tenant_id','synthetic-tenant')]:
            d=copy.deepcopy(self.base);d[key]=value
            with self.subTest(key=key),self.assertRaises(TemplateError):check_template('remote-session',d,self.settings)
    def test_rejects_fixed_or_unknown_runtime_publication_keys(self):
        for key in ['remote.engineer_concurrency','unregistered.policy']:
            d=copy.deepcopy(self.base);d['runtime_publications'][key]=None
            with self.subTest(key=key),self.assertRaises(TemplateError):check_template('remote-session',d,self.settings)
    def test_rejects_non_null_reference_or_business_value(self):
        for mutate in [lambda d:d.update(existing_credential_binding_reference='SYNTHETIC_NOT_A_SECRET'),lambda d:d['runtime_publications'].update({'remote.idle_timeout_sec':0}),lambda d:d.update(password='SYNTHETIC_NOT_A_SECRET')]:
            d=copy.deepcopy(self.base);mutate(d)
            with self.assertRaises(TemplateError):check_template('remote-session',d,self.settings)
    def test_rejects_nonfixed_or_duplicate_fixed_references(self):
        for value in [['remote.default_session_min'],['remote.engineer_concurrency']*2]:
            d=copy.deepcopy(self.base);d['fixed_registry_keys']=value
            with self.assertRaises(TemplateError):check_template('remote-session',d,self.settings)
    def test_rejects_wrong_purpose_or_missing_switch(self):
        d=copy.deepcopy(self.base);d['purpose']='runtime';
        with self.assertRaises(TemplateError):check_template('remote-session',d,self.settings)
        d=copy.deepcopy(self.base);del d['bastion_session_enabled']
        with self.assertRaises(TemplateError):check_template('remote-session',d,self.settings)
    def test_backup_crypto_references_remain_unconfigured_and_not_execution_grants(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        for key in ['approved_envelope_encryption_profile_reference','fresh_data_key_provider_reference','crypto_access_authorizer_reference','approved_kms_key_version_reference','backup_plaintext_size_limit_reference']:
            self.assertIn(key,d);self.assertIsNone(d[key])
        self.assertFalse(d['backup_execution_enabled']);self.assertFalse(check_template('backup-execution',d,self.settings)['ready'])
    def test_backup_archive_requires_separate_authorization_and_bucket_governance(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        for key in ['backup_archive_access_authorizer_reference','approved_immutable_bucket_governance_reference']:
            self.assertIn(key,d);self.assertIsNone(d[key])
        self.assertFalse(d['backup_execution_enabled']);self.assertFalse(d['production_approved'])
if __name__=='__main__': unittest.main()

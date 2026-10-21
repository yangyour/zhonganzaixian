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
    def test_all_runtime_slots_are_existing_unpublished_keys(self):
        d=json.loads((ROOT/'deploy/examples/backup-execution-dependencies.json').read_text())
        keys={s['key'] for s in json.loads((ROOT/'contracts/config-baseline.json').read_text())['settings']}
        self.assertGreater(len(d['runtime_publications']),0)
        for k,v in d['runtime_publications'].items():
            self.assertIn(k,keys)
            self.assertIsNone(v,k)
if __name__=='__main__': unittest.main()

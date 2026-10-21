"""Event pull dependencies remain unresolved rather than silently starting a worker."""
import json
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

class EventDependencyTemplateTest(unittest.TestCase):
    def test_example_is_inactive_and_contains_no_supplied_connection(self):
        data = json.loads((ROOT / 'deploy/examples/event-pull-dependencies.json').read_text())
        self.assertEqual(data['purpose'], 'dependency_shape_only_not_a_runtime_publication')
        self.assertIs(data['event_pull_enabled'], False)
        self.assertIs(data['production_approved'], False)
        for key in ('resource_pool_id', 'approved_source_connection_reference', 'existing_read_credential_reference',
                    'verified_object_mapping_reference', 'durable_watermark_repository_reference', 'failed_payload_store_reference'):
            self.assertIsNone(data[key])
        self.assertEqual(data['runtime_publications'], {'event.review_transition_policy': None})

    def test_fixed_keys_are_existing_registry_constraints_not_new_defaults(self):
        data = json.loads((ROOT / 'deploy/examples/event-pull-dependencies.json').read_text())
        registry = json.loads((ROOT / 'contracts/config-baseline.json').read_text())
        settings = {row['key']: row for row in registry['settings']}
        self.assertEqual(set(data['fixed_registry_keys']), {'event.compensation_interval_sec', 'event.compensation_lookback_sec', 'event.failed_payload_retention_hours'})
        for key in data['fixed_registry_keys']:
            self.assertEqual(settings[key]['contract_value_kind'], 'fixed_constraint')

if __name__ == '__main__':
    unittest.main()

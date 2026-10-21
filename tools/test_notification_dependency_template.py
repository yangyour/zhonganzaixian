"""The shipped dependency checklist must never silently activate notification sending."""
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

class NotificationDependencyTemplateTest(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT / 'deploy/examples/notification-routing-dependencies.json').read_text())

    def test_template_never_enables_dispatch(self):
        self.assertEqual(self.data['purpose'], 'dependency_shape_only_not_a_runtime_publication')
        self.assertIs(self.data['production_approved'], False)
        self.assertIs(self.data['notification_routing_enabled'], False)

    def test_all_customer_publications_remain_unresolved(self):
        expected = {'notification.severity_priority_mapping', 'notification.matrix_precedence',
                    'notification.provider_credentials', 'notification.sms_month_quota',
                    'notification.voice_concurrency', 'notification.price',
                    'oncall.roster_and_escalation_chain', 'oncall.unanswered_retry_interval_sec'}
        self.assertEqual(set(self.data['runtime_publications']), expected)
        self.assertTrue(all(v is None for v in self.data['runtime_publications'].values()))
        for key in ('effective_preference_publication_reference', 'approved_recipient_mapping_reference',
                    'channel_and_budget_configuration_reference'):
            self.assertIsNone(self.data[key])

    def test_six_channels_use_only_empty_references(self):
        self.assertEqual(set(self.data['channels']), {'EMAIL', 'SMS', 'DINGTALK', 'WECHAT_WORK', 'WEBHOOK', 'VOICE'})
        expected = {'provider_adapter', 'provider_credentials_reference', 'approved_endpoint_reference',
                    'receipt_verification_configuration_reference', 'retry_budget_reference'}
        for fields in self.data['channels'].values():
            self.assertEqual(set(fields), expected)
            self.assertTrue(all(v is None for v in fields.values()))

if __name__ == '__main__':
    unittest.main()

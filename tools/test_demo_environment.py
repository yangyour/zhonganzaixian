"""The demo writer must reject real scope before any HTTP request."""
import unittest
from types import SimpleNamespace
from unittest.mock import Mock

from demo_environment import Client, PROTECTED


class DemoWriteScopeTest(unittest.TestCase):
    def client(self, tenant='11111111-1111-4111-8111-111111111111'):
        client = Client.__new__(Client)
        client.tenant = tenant
        client.context = 'padmin'
        client.env = SimpleNamespace(tenants={'11111111-1111-4111-8111-111111111111'},
            state={'users': {'main': {'id': '22222222-2222-4222-8222-222222222222'}}, 'requests': []}, save=Mock())
        client.session = {'tenant': {'id': tenant}}
        client.http = Mock()
        client.csrf = None
        return client

    def denied(self, client, path, body=None):
        with self.assertRaises(RuntimeError):
            client.call(path, 'POST', body)
        client.http.request.assert_not_called()

    def test_real_tenant_cannot_write(self):
        for tenant in PROTECTED:
            self.denied(self.client(tenant), '/assets', {'name': '[DEMO] forbidden'})

    def test_session_must_match_owned_tenant(self):
        client = self.client()
        client.session = {'tenant': {'id': next(iter(PROTECTED))}}
        self.denied(client, '/assets', {})

    def test_protected_reference_cannot_be_sent(self):
        for tenant in PROTECTED:
            self.denied(self.client(), '/assets', {'tenant_id': tenant})

    def test_outbound_and_global_mutations_are_blocked(self):
        for path in ['/email/test-messages', '/wecom/test-messages', '/identity/policy', '/monitoring-onboarding/registered-targets/a/test']:
            self.denied(self.client(), path, {})

    def test_cross_origin_urls_are_blocked(self):
        for path in ['//example.invalid', 'https://example.invalid', '/path/https://example.invalid']:
            self.denied(self.client(), path, {})

    def test_inactive_demo_binding_is_the_only_channel_write_exception(self):
        client = self.client()
        client.http.request.return_value.status_code = 200
        client.http.request.return_value.json.return_value = {'data': {'active': False}}
        result = client.call('/email/bindings/22222222-2222-4222-8222-222222222222', 'PUT',
            {'active': False, 'email': 'demo@example.invalid', 'expected_version': 2})
        self.assertEqual({'active': False}, result)
        self.assertEqual(1, client.http.request.call_count)

    def test_active_or_real_recipient_binding_cannot_be_sent(self):
        for body in [{'active': True, 'email': 'demo@example.invalid'}, {'active': False, 'email': 'person@example.com'}]:
            client = self.client()
            with self.assertRaises(RuntimeError):
                client.call('/email/bindings/22222222-2222-4222-8222-222222222222', 'PUT', body)
            client.http.request.assert_not_called()

    def test_disabled_binding_must_target_an_owned_demo_identity(self):
        client = self.client()
        with self.assertRaises(RuntimeError):
            client.call('/email/bindings/33333333-3333-4333-8333-333333333333', 'PUT',
                {'active': False, 'email': 'demo@example.invalid'})
        client.http.request.assert_not_called()


if __name__ == '__main__':
    unittest.main()

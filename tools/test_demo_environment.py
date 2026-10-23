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
        client.env = SimpleNamespace(tenants={'11111111-1111-4111-8111-111111111111'})
        client.session = {'tenant': {'id': tenant}}
        client.http = Mock()
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


if __name__ == '__main__':
    unittest.main()

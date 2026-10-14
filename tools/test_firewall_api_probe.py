import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('firewall_probe', Path(__file__).with_name('probe-firewall-api.py'))
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class FirewallEntryTest(unittest.TestCase):
    def response(self, codes, status=200, json_object=True):
        return {'status': 'HTTP_RESPONSE', 'http_status': status,
                'provider_codes': codes, 'json_object': json_object}

    def test_http_200_does_not_hide_login_requirement(self):
        for codes in ([1003], [0, 1003], [0, 1012]):
            self.assertEqual('AUTHENTICATION_REQUIRED', probe.classify(self.response(codes)))

    def test_nested_permission_denial_is_not_success(self):
        self.assertEqual('ACCESS_DENIED', probe.classify(self.response([0, 13])))

    def test_missing_route_is_not_confirmed(self):
        for value in (self.response([1002]), self.response([], 404)):
            self.assertEqual('API_ROUTE_NOT_CONFIRMED', probe.classify(value))

    def test_html_and_bad_codes_are_not_api_success(self):
        self.assertEqual('API_RESPONSE_NOT_CONFIRMED', probe.classify(self.response([], json_object=False)))
        self.assertEqual('PROVIDER_REJECTED', probe.classify(self.response(['INVALID_CODE_TYPE'])))
        self.assertEqual('PROVIDER_REJECTED', probe.classify(self.response([22])))

    def test_unauthenticated_zero_code_does_not_claim_authenticated_readiness(self):
        self.assertEqual('UNAUTHENTICATED_RESPONSE_ONLY', probe.classify(self.response([0])))
        self.assertEqual('API_RESPONSE_NOT_CONFIRMED', probe.classify(self.response([0], 500)))

    def test_tls_and_transport_failures_remain_distinct(self):
        for status in ('TLS_NOT_VERIFIED', 'TRANSPORT_UNAVAILABLE', 'RESPONSE_TOO_LARGE'):
            self.assertEqual(status, probe.classify({'status': status}))


if __name__ == '__main__':
    unittest.main()

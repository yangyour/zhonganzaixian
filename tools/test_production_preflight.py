"""Pure validation and synthetic transport tests, not actual TLS or component acceptance."""
from copy import deepcopy
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import ssl
import tempfile
import unittest
from unittest.mock import patch

import production_preflight as preflight


def manifest():
    return dict(format=1, public_origin='https://mop.example.test', source_commit='a' * 40,
                artifact_sha256='b' * 64, database_isolation_decision_ref=None,
                component_approval_ref=None, production_approval_ref=None)


class SyntheticProbe:
    origin = 'https://mop.example.test'

    def tls(self):
        return {'notAfter': 'Dec 31 23:59:59 2099 GMT'}

    def request(self, path, secure=True):
        if not secure:
            return 308, [('Location', self.origin + path)], b''
        if path == preflight.CSRF_PATH:
            return 200, [('Set-Cookie', 'MOP_SESSION=SYNTHETIC; Secure; HttpOnly; SameSite=Strict; Path=/')], b'{}'
        return 200, [('Strict-Transport-Security', 'max-age=31536000')], json.dumps(
            {'data': {'ready': True, 'durable': True, 'mode': 'remote_test', 'production_ready': False}}).encode()


class PreflightTest(unittest.TestCase):
    def network(self, probe=None):
        return {item['id']: item for item in preflight.collect_network(probe or SyntheticProbe(), datetime.now(timezone.utc))}

    def test_current_test_readiness_cannot_certify_production(self):
        checks = self.network()
        self.assertEqual(checks['dependency_readiness']['status'], 'PASS')
        self.assertEqual(checks['runtime_environment']['status'], 'FAIL')

    def test_config_only_never_claims_probe_success(self):
        report = preflight.evaluate(manifest())
        self.assertFalse(report['production_ready'])
        self.assertTrue(all(item['status'] == 'NOT_RUN' for item in report['checks'][-7:]))

    def test_approval_references_are_not_customer_signoff(self):
        config = manifest()
        for key in ('database_isolation_decision_ref', 'component_approval_ref', 'production_approval_ref'):
            config[key] = 'Q02-review'
        report = preflight.evaluate(config, SyntheticProbe())
        self.assertEqual(report['result'], 'BLOCKED')
        self.assertFalse(report['production_ready'])
        self.assertEqual(report['formal_tc_status'], 'NOT_RUN')
        self.assertEqual(sum(c['code'] == 'REFERENCE_REQUIRES_REVIEW' for c in report['checks']), 3)

    def test_no_secret_keys_or_credential_urls(self):
        for value in ['http://mop.example.test', 'https://user:secret@mop.example.test', 'https://mop.example.test/?token=x',
                      'https://mop.example.test/#x', 'https://mop.example.test:444', 'https://mop.example.test\\other',
                      'https://mop.example.test\n', 'https://mop.example.test/']:
            with self.subTest(value=value), self.assertRaises(ValueError):
                preflight.validate_config({**manifest(), 'public_origin': value})
        with self.assertRaises(ValueError):
            preflight.validate_config({**manifest(), 'password': 'SYNTHETIC_SECRET'})

    def test_invalid_config_types_and_unbounded_references(self):
        for key, value in [('format', True), ('source_commit', 'not-a-commit'), ('artifact_sha256', 1),
                           ('production_approval_ref', 'x' * 129), ('component_approval_ref', 'https://secret')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                preflight.validate_config({**manifest(), key: value})

    def test_duplicate_json_keys_and_nan_rejected(self):
        for raw in ['{"data":{"ready":false,"ready":true}}', '{"format":NaN}']:
            with self.assertRaises(ValueError):
                preflight.parse_json(raw)

    def test_tls_failure_stops_https_requests_without_bypass(self):
        probe = SyntheticProbe()
        with patch.object(probe, 'tls', side_effect=ssl.SSLCertVerificationError('SYNTHETIC_SECRET')):
            with patch.object(probe, 'request', wraps=probe.request) as request:
                checks = self.network(probe)
        self.assertEqual(checks['tls_identity']['code'], 'CERTIFICATE_VALIDATION_FAILED')
        self.assertEqual(checks['session_cookie']['status'], 'NOT_RUN')
        request.assert_called_once_with(preflight.READY_PATH, secure=False)
        self.assertNotIn('SYNTHETIC_SECRET', json.dumps(checks))

    def test_certificate_expiry_boundary(self):
        probe = SyntheticProbe()
        with patch.object(probe, 'tls', return_value={'notAfter': 'Oct 31 00:00:00 2026 GMT'}):
            checks = preflight.collect_network(probe, datetime(2026, 10, 1, tzinfo=timezone.utc))
        self.assertEqual(next(c for c in checks if c['id'] == 'tls_expiry')['status'], 'FAIL')

    def test_missing_certificate_expiry_is_failure(self):
        probe = SyntheticProbe()
        with patch.object(probe, 'tls', return_value={}):
            self.assertEqual(self.network(probe)['tls_expiry']['status'], 'FAIL')

    def test_forged_or_malformed_readiness_fails(self):
        probe = SyntheticProbe()
        for body in [b'[]', b'{bad}', b'{"data":{"ready":"true","durable":true}}',
                     b'{"data":{"ready":true,"durable":false}}', b'{"data":{"ready":true,"ready":false}}']:
            with self.subTest(body=body), patch.object(probe, 'request', return_value=(200, [], body)):
                self.assertEqual(self.network(probe)['dependency_readiness']['status'], 'FAIL')

    def test_http_error_redirect_and_timeout_never_ready(self):
        probe = SyntheticProbe()
        for status in (301, 401, 503):
            with patch.object(probe, 'request', return_value=(status, [], b'{"data":{"ready":true,"durable":true}}')):
                self.assertEqual(self.network(probe)['dependency_readiness']['status'], 'FAIL')
        with patch.object(probe, 'request', side_effect=TimeoutError('SYNTHETIC_SECRET')):
            checks = self.network(probe)
            self.assertEqual(checks['dependency_readiness']['status'], 'FAIL')
            self.assertNotIn('SYNTHETIC_SECRET', json.dumps(checks))

    def test_cookie_security_attributes_and_host_scope(self):
        good = 'MOP_SESSION=SYNTHETIC; Secure; HttpOnly; SameSite=Strict; Path=/'
        self.assertTrue(preflight.secure_session_cookie([('Set-Cookie', good)]))
        for bad in [good.replace('; Secure', ''), good.replace('; HttpOnly', ''), good.replace('Strict', 'Lax'),
                    good.replace('Path=/', 'Path=/api'), good + '; Domain=example.test', good + '; Max-Age=300',
                    'OTHER=SYNTHETIC; Secure; HttpOnly; SameSite=Strict; Path=/']:
            self.assertFalse(preflight.secure_session_cookie([('Set-Cookie', bad)]))
        self.assertFalse(preflight.secure_session_cookie([('Set-Cookie', good), ('Set-Cookie', good)]))

    def test_hsts_missing_duplicate_or_short_lifetime(self):
        for headers in [[], [('Strict-Transport-Security', 'max-age=0')],
                        [('Strict-Transport-Security', 'max-age=31536000;max-age=0')],
                        [('Strict-Transport-Security', 'max-age=31536000')] * 2]:
            self.assertFalse(preflight.valid_hsts(headers))

    def test_http_redirect_must_preserve_verified_origin_and_path(self):
        probe = SyntheticProbe()
        for location in ['https://other.example.test' + preflight.READY_PATH, probe.origin, 'http://mop.example.test' + preflight.READY_PATH]:
            with patch.object(probe, 'request', return_value=(308, [('Location', location)], b'')):
                self.assertEqual(self.network(probe)['http_redirect']['status'], 'FAIL')

    def test_private_response_metadata_never_enters_evidence(self):
        probe = SyntheticProbe()
        with patch.object(probe, 'request', return_value=(503, [('Authorization', 'SYNTHETIC_SECRET')], b'SYNTHETIC_SECRET')):
            self.assertNotIn('SYNTHETIC_SECRET', json.dumps(preflight.evaluate(manifest(), probe)))

    def test_transport_keeps_standard_trust_and_disallows_other_paths(self):
        probe = preflight.PublicProbe('https://mop.example.test')
        self.assertTrue(probe.context.check_hostname)
        self.assertEqual(probe.context.verify_mode, ssl.CERT_REQUIRED)
        self.assertGreaterEqual(probe.context.minimum_version, ssl.TLSVersion.TLSv1_2)
        with self.assertRaises(ValueError):
            probe.request('/admin/delete')

    def test_evidence_is_exclusive_and_cli_output_is_redacted(self):
        with tempfile.TemporaryDirectory() as root:
            config, output = Path(root) / 'config.json', Path(root) / 'report.json'
            config.write_text(json.dumps(manifest()), encoding='utf-8')
            with patch('sys.stdout', new_callable=io.StringIO):
                self.assertEqual(preflight.main(['--config', str(config), '--output', str(output)]), 2)
                original = output.read_bytes()
                self.assertEqual(preflight.main(['--config', str(config), '--output', str(output)]), 1)
                self.assertEqual(output.read_bytes(), original)
            config.write_text('{"password":"SYNTHETIC_SECRET"}', encoding='utf-8')
            with patch('sys.stdout', new_callable=io.StringIO) as stdout:
                self.assertEqual(preflight.main(['--config', str(config), '--output', str(output)]), 1)
                self.assertNotIn('SYNTHETIC_SECRET', stdout.getvalue())


if __name__ == '__main__':
    unittest.main()

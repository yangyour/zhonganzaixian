"""Read-only launch evidence collector. Never enables production or executes deployment."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
from http.cookies import SimpleCookie
import json
from pathlib import Path
import re
import ssl
from urllib.parse import urlsplit

MAX_BYTES = 262144
READY_PATH = '/api/v1/health/ready'
CSRF_PATH = '/api/v1/auth/csrf'
NETWORK_CHECKS = ('tls_identity', 'tls_expiry', 'dependency_readiness',
                  'runtime_environment', 'hsts', 'session_cookie', 'http_redirect')
CONFIG_FIELDS = {'format', 'public_origin', 'source_commit', 'artifact_sha256',
                 'database_isolation_decision_ref', 'component_approval_ref', 'production_approval_ref'}


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def parse_json(raw):
    return json.loads(raw, object_pairs_hook=strict_object,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('INVALID_JSON_NUMBER')))


def validate_config(config):
    """Only a credential-free manifest is accepted; invalid values never appear in errors."""
    if not isinstance(config, dict) or set(config) != CONFIG_FIELDS or type(config['format']) is not int or config['format'] != 1:
        raise ValueError('INVALID_CONFIG_SHAPE')
    origin = config['public_origin']
    if origin is not None:
        if not isinstance(origin, str) or not origin.isascii() or re.search(r'\s|[\\#?]', origin):
            raise ValueError('INVALID_ORIGIN')
        parsed = urlsplit(origin)
        if (parsed.scheme != 'https' or not parsed.hostname or parsed.username is not None
                or parsed.password is not None or parsed.path or parsed.query or parsed.fragment
                or parsed.port not in (None, 443) or not re.fullmatch(r'[A-Za-z0-9.-]+', parsed.hostname)):
            raise ValueError('INVALID_ORIGIN')
    for key, size in [('source_commit', 40), ('artifact_sha256', 64)]:
        value = config[key]
        if value is not None and (not isinstance(value, str) or not re.fullmatch('[a-f0-9]{%d}' % size, value)):
            raise ValueError('INVALID_ARTIFACT_IDENTITY')
    for key in sorted(CONFIG_FIELDS - {'format', 'public_origin', 'source_commit', 'artifact_sha256'}):
        value = config[key]
        if value is not None and (not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]{0,127}', value)):
            raise ValueError('INVALID_APPROVAL_REFERENCE')
    return config


def check(name, status, code):
    return {'id': name, 'status': status, 'code': code}


def passed(name, condition, ok, failure):
    return check(name, 'PASS' if condition else 'FAIL', ok if condition else failure)


def headers_named(headers, name):
    return [value for key, value in headers if key.lower() == name.lower()]


def secure_session_cookie(headers):
    found = []
    declarations = 0
    try:
        for raw in headers_named(headers, 'set-cookie'):
            declarations += len(re.findall(r'(?:^|[;,])\s*MOP_SESSION\s*=', raw))
            cookie = SimpleCookie()
            cookie.load(raw)
            if 'MOP_SESSION' in cookie:
                found.append(cookie['MOP_SESSION'])
    except Exception:
        return False
    if len(found) != 1 or declarations != 1:
        return False
    cookie = found[0]
    return bool(cookie.value and cookie['secure'] is True and cookie['httponly'] is True
                and cookie['samesite'].lower() == 'strict' and cookie['path'] == '/'
                and not cookie['domain'] and not cookie['max-age'] and not cookie['expires'])


def valid_hsts(headers):
    values = headers_named(headers, 'strict-transport-security')
    if len(values) != 1:
        return False
    if len(re.findall(r'(?:^|;)\s*max-age\s*=', values[0], re.I)) != 1:
        return False
    ages = re.findall(r'(?:^|;)\s*max-age\s*=\s*([0-9]+)\s*(?=;|$)', values[0], re.I)
    return len(ages) == 1 and int(ages[0]) >= 31536000


class PublicProbe:
    """Direct, bounded GET/HEAD only. TLS verification and redirect rejection cannot be disabled."""
    def __init__(self, origin, ca_file=None):
        self.origin = origin
        self.host = urlsplit(origin).hostname
        self.context = ssl.create_default_context(cafile=ca_file)
        self.context.minimum_version = ssl.TLSVersion.TLSv1_2

    def tls(self):
        connection = http.client.HTTPSConnection(self.host, 443, timeout=10, context=self.context)
        try:
            connection.connect()
            return connection.sock.getpeercert()
        finally:
            connection.close()

    def request(self, path, secure=True):
        if path not in (READY_PATH, CSRF_PATH):
            raise ValueError('PROBE_PATH_NOT_ALLOWED')
        connection = (http.client.HTTPSConnection(self.host, 443, timeout=10, context=self.context)
                      if secure else http.client.HTTPConnection(self.host, 80, timeout=10))
        try:
            connection.request('GET' if secure else 'HEAD', path,
                               headers={'Accept': 'application/json', 'User-Agent': 'MOP-launch-preflight/1'})
            response = connection.getresponse()
            body = response.read(MAX_BYTES + 1) if secure else b''
            if len(body) > MAX_BYTES:
                raise ValueError('RESPONSE_TOO_LARGE')
            return response.status, response.getheaders(), body
        finally:
            connection.close()


def collect_network(probe, now):
    result = []
    try:
        certificate = probe.tls()
        result.append(check('tls_identity', 'PASS', 'CHAIN_AND_HOSTNAME_VERIFIED'))
        remaining = ssl.cert_time_to_seconds(certificate['notAfter']) - now.timestamp()
        result.append(passed('tls_expiry', remaining > 30 * 86400, 'MORE_THAN_30_DAYS', 'EXPIRY_WITHIN_30_DAYS'))
    except ssl.SSLCertVerificationError:
        result.append(check('tls_identity', 'FAIL', 'CERTIFICATE_VALIDATION_FAILED'))
    except Exception:
        result.append(check('tls_identity' if not result else 'tls_expiry', 'FAIL', 'TLS_UNAVAILABLE_OR_INVALID'))
    if not any(item['id'] == 'tls_identity' and item['status'] == 'PASS' for item in result):
        result.extend(check(name, 'NOT_RUN', 'VERIFIED_TLS_REQUIRED') for name in NETWORK_CHECKS[1:-1])
    else:
        try:
            status, headers, raw = probe.request(READY_PATH)
            # Error bodies and arbitrary component metadata must never be copied to evidence.
            body = parse_json(raw) if status == 200 else None
            data = body.get('data') if isinstance(body, dict) else None
            ready = isinstance(data, dict) and data.get('ready') is True and data.get('durable') is True
            result.append(passed('dependency_readiness', ready, 'LIVE_DEPENDENCIES_READY', 'DEPENDENCIES_NOT_READY'))
            production = ready and data.get('mode') == 'production' and data.get('production_ready') is True
            result.append(passed('runtime_environment', production, 'RUNTIME_REPORTS_PRODUCTION', 'NOT_PRODUCTION_RUNTIME'))
            result.append(passed('hsts', valid_hsts(headers), 'HSTS_ONE_YEAR', 'HSTS_MISSING_OR_WEAK'))
        except Exception:
            result.extend(check(name, 'FAIL', 'READINESS_UNAVAILABLE_OR_INVALID')
                          for name in ('dependency_readiness', 'runtime_environment', 'hsts'))
        try:
            status, headers, _ = probe.request(CSRF_PATH)
            result.append(passed('session_cookie', status == 200 and secure_session_cookie(headers),
                                 'HOST_ONLY_SECURE_SESSION_COOKIE', 'SESSION_COOKIE_MISSING_OR_UNSAFE'))
        except Exception:
            result.append(check('session_cookie', 'FAIL', 'SESSION_PROBE_UNAVAILABLE'))
    try:
        status, headers, _ = probe.request(READY_PATH, secure=False)
        locations = headers_named(headers, 'location')
        redirect = status in (301, 308) and locations == [probe.origin + READY_PATH]
        result.append(passed('http_redirect', redirect, 'SAME_ORIGIN_HTTPS_REDIRECT', 'HTTP_NOT_PERMANENTLY_REDIRECTED'))
    except Exception:
        # Inaccessible HTTP could mean firewall/no listener; it does not prove a redirect.
        result.append(check('http_redirect', 'FAIL', 'HTTP_REDIRECT_UNVERIFIED'))
    return result


def evaluate(config, probe=None, now=None):
    validate_config(config)
    now = now or datetime.now(timezone.utc)
    checks = []
    for key in ('public_origin', 'source_commit', 'artifact_sha256'):
        checks.append(check(key, 'PASS' if config[key] else 'BLOCKED', 'DECLARED' if config[key] else 'NOT_CONFIGURED'))
    for key in ('database_isolation_decision_ref', 'component_approval_ref', 'production_approval_ref'):
        # A string supplied in a manifest cannot certify a customer approval.
        checks.append(check(key, 'BLOCKED', 'REFERENCE_REQUIRES_REVIEW' if config[key] else 'CUSTOMER_CONFIRMATION_PENDING'))
    if probe is not None and config['public_origin'] is not None:
        checks.extend(collect_network(probe, now))
    else:
        checks.extend(check(name, 'NOT_RUN', 'EXPLICIT_PROBE_AND_ORIGIN_REQUIRED') for name in NETWORK_CHECKS)
    return {'format': 1, 'recorded_at_utc': now.isoformat(), 'operation': 'READ_ONLY_PREFLIGHT',
            'manifest_sha256': hashlib.sha256(json.dumps(config, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
            'production_ready': False, 'deployment_performed': False, 'formal_tc_status': 'NOT_RUN',
            'result': 'BLOCKED', 'checks': checks}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--output', required=True, help='New evidence file; existing evidence is never overwritten')
    parser.add_argument('--probe', action='store_true', help='Perform unauthenticated GET/HEAD to the declared origin')
    parser.add_argument('--ca-file', help='Optional private trust bundle; hostname verification remains mandatory')
    args = parser.parse_args(argv)
    try:
        raw = Path(args.config).read_bytes()
        if len(raw) > MAX_BYTES:
            raise ValueError('CONFIG_TOO_LARGE')
        config = validate_config(parse_json(raw))
        # Reserve evidence path before network access to avoid losing a run to an existing file.
        with Path(args.output).open('x', encoding='utf-8') as output:
            probe = PublicProbe(config['public_origin'], args.ca_file) if args.probe and config['public_origin'] else None
            report = evaluate(config, probe)
            output.write(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'result': report['result'], 'production_ready': False,
                          'checks': len(report['checks']), 'formal_tc_status': 'NOT_RUN'}))
        return 2
    except Exception:
        print(json.dumps({'result': 'ERROR', 'code': 'PREFLIGHT_INPUT_OR_OUTPUT_INVALID', 'production_ready': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

"""Bounded read-only HTTP execution over the designated pinned SSH connection; not a scale acceptance test."""
import argparse
from datetime import datetime, timezone
import http.client
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import time
import threading

from production_preflight import parse_json

OWNER_ROOT = Path('E:/work/wxw9/zhonganzaixian/mop-platform')
ROOT = Path(__file__).resolve().parents[1]
PATHS = {'live': '/api/v1/health/live', 'ready': '/api/v1/health/ready'}


def validate(requests, rate, seconds, endpoint):
    if (type(requests) is not int or not 1 <= requests <= 300 or type(rate) not in (int, float)
            or not math.isfinite(rate) or not 0.1 <= rate <= 10 or type(seconds) is not int or not 1 <= seconds <= 60
            or endpoint not in PATHS or (requests - 1) / rate > seconds):
        raise ValueError('PROBE_BOUNDS_INVALID')


def execute(fetch, requests, rate, seconds, endpoint, monotonic=time.monotonic, sleep=time.sleep):
    validate(requests, rate, seconds, endpoint)
    start = monotonic()
    deadline = start + seconds
    next_request = start
    samples = []
    for sequence in range(1, requests + 1):
        remaining = deadline - monotonic()
        if remaining <= 0:
            break
        wait = next_request - monotonic()
        if wait > 0:
            sleep(min(wait, remaining))
        began = monotonic()
        if began >= deadline:
            break
        code = None
        outcome = 'UNAVAILABLE'
        try:
            code, body = fetch(PATHS[endpoint], min(5, deadline - began))
            data = body.get('data') if isinstance(body, dict) else None
            valid = (code == 200 and isinstance(data, dict) and data.get('mode') == 'remote_test'
                     and (data.get('ready') is True if endpoint == 'ready' else data.get('status') == 'UP'))
            outcome = 'EXPECTED_TEST_RESPONSE' if valid else 'UNEXPECTED_OR_UNREADY_RESPONSE'
        except Exception:
            pass
        elapsed = max(0, (monotonic() - began) * 1000)
        samples.append({'sequence': sequence, 'http_status': code if type(code) is int and 100 <= code <= 599 else None,
                        'elapsed_ms': round(elapsed, 3), 'outcome': outcome})
        # No catch-up bursts after slow requests; concurrency is fixed to one.
        next_request = max(began + 1 / rate, monotonic())
    latencies = sorted(sample['elapsed_ms'] for sample in samples if sample['outcome'] == 'EXPECTED_TEST_RESPONSE')
    passed = len(samples) == requests and len(latencies) == requests
    return {'format': 1, 'recorded_at_utc': datetime.now(timezone.utc).isoformat(), 'scope': 'READ_ONLY_READINESS_BURST',
            'environment': 'lan-test-20261008', 'transport': 'HTTP_OVER_PINNED_PROJECT_SSH', 'endpoint': endpoint,
            'requested_samples': requests, 'actual_samples': len(samples), 'max_requests_per_second': rate,
            'concurrency': 1, 'budget_seconds': seconds, 'result': 'PASSED' if passed else 'FAILED_OR_INCOMPLETE',
            'client_observed_p95_ms': latencies[max(0, math.ceil(len(latencies) * 0.95) - 1)] if latencies else None,
            'latency_target_ms': None, 'latency_acceptance': 'NOT_ASSESSED', 'samples': samples,
            'dependency_readiness_verified': endpoint == 'ready' and passed, 'business_requests_sent': 0,
            'component_lifecycle_changed': False, 'formal_scale_acceptance': 'NOT_RUN', 'production_ready': False}


class DesignatedHttp:
    def __init__(self):
        spec = importlib.util.spec_from_file_location('readiness_owned_connection', ROOT / 'tools/update-lan-test-server.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.ROOT = OWNER_ROOT
        self.server = module.Server()  # checks the existing SSH host key, project owner and exact environment

    def close(self):
        self.server.client.close()

    def fetch(self, path, timeout):
        if path not in PATHS.values():
            raise ValueError('PROBE_PATH_NOT_ALLOWED')
        transport = self.server.client.get_transport()
        deadline = time.monotonic() + timeout
        channel = transport.open_channel('direct-tcpip', ('127.0.0.1', 10010), ('127.0.0.1', 0), timeout=timeout)
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            channel.close()
            raise TimeoutError('PROBE_CHANNEL_DEADLINE')
        channel.settimeout(remaining)
        watchdog = threading.Timer(remaining, channel.close)
        watchdog.daemon = True
        watchdog.start()
        connection = http.client.HTTPConnection('127.0.0.1', 10010, timeout=timeout)
        connection.sock = channel
        try:
            connection.request('GET', path, headers={'Accept': 'application/json', 'Connection': 'close'})
            response = connection.getresponse()
            body = response.read(262145)
            if len(body) > 262144:
                raise ValueError('PROBE_RESPONSE_LIMIT')
            return response.status, parse_json(body)
        finally:
            watchdog.cancel()
            connection.close()
            channel.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--requests', type=int, default=3)
    parser.add_argument('--rate', type=float, default=1)
    parser.add_argument('--seconds', type=int, default=10)
    parser.add_argument('--endpoint', choices=PATHS, default='live')
    parser.add_argument('--output', required=True)
    args = parser.parse_args(argv)
    client = None
    try:
        validate(args.requests, args.rate, args.seconds, args.endpoint)
        with Path(args.output).open('x', encoding='utf-8') as output:
            if args.execute:
                client = DesignatedHttp()
                report = execute(client.fetch, args.requests, args.rate, args.seconds, args.endpoint)
            else:
                report = {'format': 1, 'result': 'NOT_RUN', 'formal_scale_acceptance': 'NOT_RUN', 'production_ready': False}
            report['runner_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
            output.write(json.dumps(report, indent=2) + '\n')
        print(json.dumps({key: report[key] for key in ('result', 'formal_scale_acceptance', 'production_ready')}))
        return 0 if report['result'] == 'PASSED' else 2
    except Exception:
        print(json.dumps({'result': 'BLOCKED', 'code': 'READINESS_PROBE_UNAVAILABLE_OR_INVALID', 'production_ready': False}))
        return 1
    finally:
        if client is not None:
            client.close()


if __name__ == '__main__':
    raise SystemExit(main())

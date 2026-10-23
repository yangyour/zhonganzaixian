"""Pure deadline, outcome and input tests; no local substitute service or real scale assertion."""
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import threading

import readiness_load_probe as probe


class SimulatedClock:
    value = 0
    def now(self): return self.value
    def sleep(self, seconds): self.value += seconds


class ReadinessLoadProbeTest(unittest.TestCase):
    def run_probe(self, fetch, requests=3, rate=1, seconds=10, endpoint='live'):
        clock = SimulatedClock()
        return probe.execute(fetch, requests, rate, seconds, endpoint, clock.now, clock.sleep)

    def test_valid_small_run_is_not_scale_or_latency_acceptance(self):
        fetch = Mock(return_value=(200, {'data': {'mode': 'remote_test', 'status': 'UP'}}))
        result = self.run_probe(fetch)
        self.assertEqual(result['result'], 'PASSED')
        self.assertEqual(result['actual_samples'], 3)
        self.assertEqual(result['formal_scale_acceptance'], 'NOT_RUN')
        self.assertFalse(result['dependency_readiness_verified'])
        self.assertIsNone(result['latency_target_ms'])
        self.assertTrue(all(call.args[0] == '/api/v1/health/live' for call in fetch.call_args_list))

    def test_ready_requires_boolean_ready_and_expected_test_runtime(self):
        for body in [{'data': {'mode': 'remote_test', 'ready': 'true'}}, {'data': {'mode': 'local_fixture', 'ready': True}}, {}]:
            self.assertEqual(self.run_probe(Mock(return_value=(200, body)), endpoint='ready')['result'], 'FAILED_OR_INCOMPLETE')
        result = self.run_probe(Mock(return_value=(200, {'data': {'mode': 'remote_test', 'ready': True}})), endpoint='ready')
        self.assertTrue(result['dependency_readiness_verified'])

    def test_failures_and_timeouts_are_samples_without_raw_diagnostics(self):
        fetch = Mock(side_effect=[TimeoutError('SYNTHETIC_SECRET'), (503, {}), (200, {'data': {'mode': 'remote_test', 'status': 'UP'}})])
        result = self.run_probe(fetch)
        self.assertEqual(result['actual_samples'], 3)
        self.assertEqual(result['result'], 'FAILED_OR_INCOMPLETE')
        self.assertNotIn('SYNTHETIC_SECRET', str(result))

    def test_budget_exhaustion_stops_without_claiming_missing_samples(self):
        clock = SimulatedClock()
        def slow(path, timeout):
            clock.value += 5
            return 200, {'data': {'mode': 'remote_test', 'status': 'UP'}}
        result = probe.execute(slow, 10, 2, 6, 'live', clock.now, clock.sleep)
        self.assertEqual(result['actual_samples'], 2)
        self.assertEqual(result['result'], 'FAILED_OR_INCOMPLETE')

    def test_no_catchup_burst_after_slow_response(self):
        clock = SimulatedClock(); starts = []
        def fetch(path, timeout):
            starts.append(clock.value); clock.value += 2
            return 200, {'data': {'mode': 'remote_test', 'status': 'UP'}}
        probe.execute(fetch, 3, 1, 10, 'live', clock.now, clock.sleep)
        self.assertEqual(starts, [0, 2, 4])

    def test_bounds_and_arbitrary_paths_are_rejected_before_fetch(self):
        for values in [(0, 1, 10, 'live'), (301, 10, 60, 'live'), (3, float('nan'), 10, 'live'),
                       (3, 11, 10, 'live'), (3, 1, 61, 'live'), (30, 1, 10, 'live'), (3, 1, 10, '/delete')]:
            fetch = Mock()
            with self.assertRaises(ValueError): self.run_probe(fetch, *values)
            fetch.assert_not_called()

    def test_redirect_and_empty_success_body_are_not_followed_or_accepted(self):
        for code, body in [(302, {}), (200, []), (200, {})]:
            result = self.run_probe(Mock(return_value=(code, body)))
            self.assertEqual(result['result'], 'FAILED_OR_INCOMPLETE')
            self.assertIsNone(result['client_observed_p95_ms'])

    def test_default_cli_never_reads_private_configuration_or_opens_network(self):
        with tempfile.TemporaryDirectory() as root, patch.object(probe, 'DesignatedHttp') as client:
            output = Path(root) / 'report.json'
            with patch('sys.stdout', new_callable=io.StringIO):
                self.assertEqual(probe.main(['--output', str(output)]), 2)
                first = output.read_bytes()
                self.assertEqual(probe.main(['--output', str(output), '--execute']), 1)
                self.assertEqual(output.read_bytes(), first)
            client.assert_not_called()

    def test_trickling_or_blocked_response_is_cut_off_by_total_deadline(self):
        client = probe.DesignatedHttp.__new__(probe.DesignatedHttp)
        client.server = Mock()
        channel = Mock()
        closed = threading.Event()
        channel.close.side_effect = closed.set
        client.server.client.get_transport.return_value.open_channel.return_value = channel
        connection = Mock()
        def blocked_read(limit):
            if not closed.wait(1):
                raise AssertionError('The watchdog did not close the owned channel')
            raise TimeoutError('SYNTHETIC_TRICKLE')
        connection.getresponse.return_value.read.side_effect = blocked_read
        with patch.object(probe.http.client, 'HTTPConnection', return_value=connection):
            with self.assertRaises(TimeoutError): client.fetch('/api/v1/health/live', 0.01)
        self.assertTrue(closed.is_set())
        connection.request.assert_called_once_with('GET', '/api/v1/health/live', headers={'Accept': 'application/json', 'Connection': 'close'})


if __name__ == '__main__':
    unittest.main()

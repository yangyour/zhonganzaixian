"""Pure sample validation tests; no device, database, or service is contacted."""
from copy import deepcopy
import unittest

import lan_monitoring_diagnostics as diag


class SampleAssessmentTest(unittest.TestCase):
    def setUp(self):
        self.now = 1000
        self.item = {'itemid': '42', 'status': '0', 'state': '0',
                     'lastclock': '980', 'lastvalue': '0'}
        self.samples = [{'itemid': '42', 'clock': str(t), 'ns': '0', 'value': '0'}
                        for t in range(440, 981, 60)]

    def assess(self, metric='icmp'):
        return diag.assess(self.item, self.samples, metric, self.now)

    def test_zero_is_a_real_failed_probe_with_fresh_collection(self):
        result = self.assess()
        self.assertEqual('FRESH', result['status'])
        self.assertEqual('0', result['current_value'])
        self.assertEqual(10, result['history_points'])
        self.assertEqual(60, result['max_observed_gap_seconds'])
        self.assertTrue(result['window_coverage_observed'])

    def test_stale_samples_never_release_old_values(self):
        self.item.update(lastclock='800', lastvalue='1')
        result = self.assess()
        self.assertEqual('STALE', result['status'])
        self.assertIsNone(result['current_value'])

    def test_future_clock_cannot_be_fresh(self):
        self.item['lastclock'] = '1001'
        self.assertEqual('INVALID_RESPONSE', self.assess()['status'])

    def test_disabled_and_unsupported_remain_distinct(self):
        for field, status in [('status', 'DISABLED'), ('state', 'UNSUPPORTED')]:
            with self.subTest(field=field):
                item = {**self.item, field: '1'}
                result = diag.assess(item, self.samples, 'icmp', self.now)
                self.assertEqual(status, result['status'])
                self.assertIsNone(result['current_value'])

    def test_missing_first_value_is_not_a_zero(self):
        self.item['lastclock'] = '0'
        result = self.assess()
        self.assertEqual('NO_DATA', result['status'])
        self.assertIsNone(result['current_value'])

    def test_gap_does_not_claim_window_coverage(self):
        del self.samples[3]
        result = self.assess()
        self.assertEqual('FRESH', result['status'])
        self.assertEqual(120, result['max_observed_gap_seconds'])
        self.assertFalse(result['window_coverage_observed'])

    def test_partial_window_or_one_point_does_not_claim_coverage(self):
        for samples in ([], self.samples[-1:], self.samples[3:]):
            with self.subTest(points=len(samples)):
                result = diag.assess(self.item, samples, 'icmp', self.now)
                self.assertFalse(result['window_coverage_observed'])

    def test_wrong_item_duplicate_and_ordering_are_rejected(self):
        for change in ('wrong_item', 'duplicate', 'reversed', 'future', 'too_old', 'bad_ns'):
            with self.subTest(change=change):
                rows = deepcopy(self.samples)
                if change == 'wrong_item':
                    rows[0]['itemid'] = '43'
                elif change == 'duplicate':
                    rows.insert(0, deepcopy(rows[0]))
                elif change == 'reversed':
                    rows.reverse()
                elif change == 'future':
                    rows[-1]['clock'] = '1001'
                elif change == 'too_old':
                    rows[0]['clock'] = '399'
                else:
                    rows[0]['ns'] = '1000000000'
                result = diag.assess(self.item, rows, 'icmp', self.now)
                self.assertEqual('INVALID_RESPONSE', result['status'])
                self.assertIsNone(result['current_value'])
                self.assertFalse(result['window_coverage_observed'])

    def test_values_are_bounded_by_metric_and_keep_precision(self):
        for metric, value in [('cpu', '101'), ('memory', '-1'), ('icmp', '2'),
                              ('tcp_22', '0.5'), ('loss', 'NaN'), ('latency', 'Inf')]:
            with self.subTest(metric=metric):
                self.item['lastvalue'] = value
                result = self.assess(metric)
                self.assertEqual('INVALID_RESPONSE', result['status'])
                self.assertIsNone(result['current_value'])
        self.assertEqual('0.0000000000000123456789', diag.numeric('0.0000000000000123456789', 'latency'))

    def test_history_values_are_validated_even_if_current_is_valid(self):
        self.samples[0]['value'] = '999'
        self.assertEqual('INVALID_RESPONSE', self.assess('cpu')['status'])

    def test_source_failure_text_is_never_reflected(self):
        self.item['lastvalue'] = 'AUTH_FAILED sensitive-provider-text'
        self.samples[0]['value'] = 'sensitive-provider-text'
        result = self.assess('state')
        self.assertEqual('FRESH', result['status'])
        self.assertEqual('COLLECTOR_NOT_OK', result['current_value'])
        self.assertNotIn('sensitive', str(result))

    def test_history_at_limit_is_not_silently_treated_as_complete(self):
        rows = self.samples * 4
        result = diag.assess(self.item, rows[:diag.HISTORY_LIMIT], 'icmp', self.now)
        self.assertEqual('INVALID_RESPONSE', result['status'])


class ProviderScopeTest(unittest.TestCase):
    def test_wrong_host_or_disabled_host_stops_before_history(self):
        class Api:
            calls = []
            def rpc(self, method, params):
                self.calls.append(method)
                return [{'hostid': '43', 'status': '0'}]
        api = Api()
        with self.assertRaises(ValueError):
            diag.read_metrics(api, '42', {}, '3')
        self.assertEqual(['host.get'], api.calls)

    def test_mapping_drift_stops_before_history(self):
        class Api:
            def __init__(self):
                self.calls = []
            def rpc(self, method, params):
                self.calls.append(method)
                if method == 'host.get':
                    return [{'hostid': '42', 'status': '0'}]
                return [{'itemid': '43', 'hostid': '42', 'key_': 'unapproved',
                         'type': '3', 'value_type': '3', 'units': '', 'delay': '60s'}]
        api = Api()
        specs = {'icmp': {'id': '43', 'key': 'approved', 'value_type': '3', 'units': ''}}
        with self.assertRaises(ValueError):
            diag.read_metrics(api, '42', specs, '3')
        self.assertEqual(['host.get', 'item.get'], api.calls)


if __name__ == '__main__':
    unittest.main()

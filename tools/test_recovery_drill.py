"""Synthetic recovery observations only, never a real restore or RPO/RTO acceptance result."""
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import recovery_drill as drill


def manifest():
    snapshot = {'namespace': 'mop_drill_synthetic_unit', 'source_commit': 'a' * 40, 'artifact_sha256': 'b' * 64,
                'schema_sha256': 'c' * 64, 'objects_sha256': 'd' * 64, 'observed_at': '2026-10-23T07:00:00Z',
                'recovered_through_at': '2026-10-23T06:59:00Z', 'dependencies_ready': True,
                'tables': [{'name': 'public.mop_asset', 'rows': 2, 'sha256': 'e' * 64}]}
    return {'format': 1, 'environment': 'designated-isolated-test', 'isolation_authorization_ref': 'UNIT-ONLY',
            'backup_sha256': 'f' * 64, 'disruption_at': '2026-10-23T07:01:00Z',
            'restore_started_at': '2026-10-23T07:02:00Z', 'ready_at': '2026-10-23T07:10:00Z',
            'rpo_target_seconds': 120, 'rto_target_seconds': 540,
            'source': snapshot, 'restored': {**deepcopy(snapshot), 'observed_at': '2026-10-23T07:11:00Z'}}


class RecoveryDrillTest(unittest.TestCase):
    def test_matching_observations_never_claim_restore_or_acceptance(self):
        result = drill.verify(manifest(), 'f' * 64)
        self.assertEqual(result['result'], 'OBSERVATIONS_MATCH')
        self.assertEqual(result['rpo_seconds_from_declared_watermark'], 120)
        self.assertEqual(result['rto_seconds_from_declared_outage'], 540)
        self.assertFalse(result['restore_executed_by_this_tool'])
        self.assertFalse(result['customer_acceptance_verified'])
        self.assertFalse(result['production_ready'])

    def test_rto_counts_time_since_outage_not_only_restore_start(self):
        data = manifest()
        data['rto_target_seconds'] = 539
        self.assertEqual(drill.verify(data, 'f' * 64)['result'], 'FAIL')

    def test_rpo_threshold_and_null_targets_are_distinct(self):
        data = manifest()
        data['rpo_target_seconds'] = 119
        self.assertEqual(drill.verify(data, 'f' * 64)['result'], 'FAIL')
        data['rpo_target_seconds'] = data['rto_target_seconds'] = None
        result = drill.verify(data, 'f' * 64)
        self.assertEqual(result['result'], 'INCOMPLETE')
        self.assertEqual(sum(c['status'] == 'NOT_ASSESSED' for c in result['checks']), 2)

    def test_actual_archive_corruption_fails(self):
        self.assertEqual(drill.verify(manifest(), '0' * 64)['result'], 'FAIL')

    def test_missing_rows_changed_content_schema_or_release_fail(self):
        for field, value in [('schema_sha256', '0' * 64), ('source_commit', '0' * 40),
                             ('artifact_sha256', '0' * 64), ('objects_sha256', '0' * 64),
                             ('namespace', 'mop_drill_another_unit')]:
            data = manifest()
            data['restored'][field] = value
            self.assertEqual(drill.verify(data, 'f' * 64)['result'], 'FAIL')
        for field, value in [('rows', 1), ('sha256', '0' * 64), ('name', 'public.other')]:
            data = manifest()
            data['restored']['tables'][0][field] = value
            self.assertEqual(drill.verify(data, 'f' * 64)['result'], 'FAIL')

    def test_missing_object_manifest_is_not_treated_as_complete_recovery(self):
        data = manifest()
        data['restored']['objects_sha256'] = None
        self.assertEqual(drill.verify(data, 'f' * 64)['result'], 'INCOMPLETE')

    def test_unready_dependencies_do_not_produce_rto_success(self):
        data = manifest()
        data['restored']['dependencies_ready'] = False
        result = drill.verify(data, 'f' * 64)
        self.assertEqual(result['result'], 'FAIL')
        self.assertIsNone(result['rto_seconds_from_declared_outage'])

    def test_missing_readiness_time_is_failed_recovery(self):
        data = manifest()
        data['ready_at'] = None
        result = drill.verify(data, 'f' * 64)
        self.assertEqual(result['result'], 'FAIL')
        self.assertIsNone(result['rto_seconds_from_declared_outage'])

    def test_wrong_or_shared_namespace_never_qualifies(self):
        for name in ['mop_test', 'production', '55000000-0000-4000-8000-000000000001', 'mop_drill_x']:
            data = manifest()
            data['restored']['namespace'] = name
            with self.assertRaises(ValueError):
                drill.verify(data, 'f' * 64)

    def test_duplicate_table_empty_table_set_and_boolean_counts_fail(self):
        for tables in [[], [manifest()['source']['tables'][0]] * 2,
                       [{'name': 'public.mop_asset', 'rows': True, 'sha256': 'e' * 64}]]:
            data = manifest()
            data['restored']['tables'] = tables
            with self.assertRaises(ValueError):
                drill.verify(data, 'f' * 64)

    def test_impossible_or_timezone_free_observations_fail(self):
        for field, value in [('disruption_at', '2026-10-23T06:00:00Z'), ('ready_at', '2026-10-23T07:00:00Z'),
                             ('ready_at', '2026-10-23T08:00:00Z'), ('restore_started_at', '2026-10-23T07:02:00')]:
            data = manifest()
            data[field] = value
            with self.assertRaises(ValueError):
                drill.verify(data, 'f' * 64)

    def test_restore_cannot_claim_watermark_after_outage_or_different_backup_cutoff(self):
        data = manifest()
        data['restored']['recovered_through_at'] = '2026-10-23T07:02:00Z'
        with self.assertRaises(ValueError):
            drill.verify(data, 'f' * 64)
        data['restored']['recovered_through_at'] = '2026-10-23T06:58:00Z'
        self.assertEqual(drill.verify(data, 'f' * 64)['result'], 'FAIL')

    def test_unknown_secret_fields_and_noninteger_targets_are_rejected(self):
        for key, value in [('password', 'SYNTHETIC_SECRET'), ('rpo_target_seconds', True), ('rto_target_seconds', -1)]:
            with self.assertRaises(ValueError):
                drill.verify({**manifest(), key: value}, 'f' * 64)

    def test_cli_hashes_exact_archive_without_mutating_or_overwriting(self):
        with tempfile.TemporaryDirectory() as root:
            archive, source, output = (Path(root) / name for name in ('backup.bin', 'manifest.json', 'report.json'))
            archive.write_bytes(b'SYNTHETIC_BACKUP_BYTES')
            data = manifest()
            data['backup_sha256'] = hashlib.sha256(archive.read_bytes()).hexdigest()
            source.write_text(json.dumps(data), encoding='utf-8')
            args = ['--backup', str(archive), '--manifest', str(source), '--output', str(output)]
            with patch('sys.stdout', new_callable=io.StringIO):
                self.assertEqual(drill.main(args), 0)
                original = output.read_bytes()
                self.assertEqual(drill.main(args), 1)
                self.assertEqual(output.read_bytes(), original)
                self.assertEqual(archive.read_bytes(), b'SYNTHETIC_BACKUP_BYTES')


if __name__ == '__main__':
    unittest.main()

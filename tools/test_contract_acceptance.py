"""Evidence integrity tests use explicit synthetic files and never run contract tests."""
from copy import deepcopy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import contract_acceptance as acceptance


class ContractAcceptanceTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / 'docs/evidence').mkdir(parents=True)
        self.file = self.root / 'docs/evidence/unit.json'
        self.file.write_bytes(b'{"kind":"SYNTHETIC_UNIT_ONLY"}')
        self.proof = {'path': 'docs/evidence/unit.json', 'sha256': hashlib.sha256(self.file.read_bytes()).hexdigest()}
        self.index = {'requirement_count': 2, 'requirements': [
            {'id': 'M2-5', 'title': 'Isolation', 'contract_scope': 'Contract', 'declared_acceptance_status': 'NOT_RUN'},
            {'id': 'M7-0', 'title': 'Route pending', 'contract_scope': 'Optional', 'declared_acceptance_status': 'NOT_RUN'}]}
        self.bundle = {'format': 1, 'source_commit': 'a' * 40, 'artifact_sha256': 'b' * 64,
                       'environment': 'synthetic-unit-only', 'records': []}
        self.record = {'run_id': 'synthetic-1', 'case_id': 'TC-M2-5', 'layer': 'UNIT', 'outcome': 'PASS',
                       'source_commit': 'a' * 40, 'artifact_sha256': 'b' * 64, 'environment': 'synthetic-unit-only',
                       'executed_at': '2026-10-23T07:00:00Z', 'evidence': [self.proof]}

    def matrix(self, record=None):
        bundle = deepcopy(self.bundle)
        bundle['records'] = [record or self.record]
        return acceptance.build_matrix(self.index, bundle, self.root)

    def test_successful_unit_evidence_does_not_promote_formal_tc(self):
        result = self.matrix()
        self.assertEqual(result['evidence_files_verified'], 1)
        self.assertEqual(result['formal_rows_without_evidence'], 2)
        self.assertFalse(result['completion_verified'])
        self.assertFalse(result['production_ready'])
        self.assertEqual(result['tests_executed_by_this_tool'], 0)
        self.assertEqual(result['requirements'][0]['tracker_declared_status'], 'NOT_RUN')
        self.assertEqual(result['requirements'][1]['current_work_scope'], 'CUSTOMER_ROUTE_PENDING')

    def test_formal_declaration_still_requires_authenticity_and_signoff_review(self):
        record = {**self.record, 'layer': 'FORMAL_TC'}
        result = self.matrix(record)
        self.assertTrue(result['requirements'][0]['formal_evidence_present'])
        self.assertEqual(result['requirements'][0]['formal_acceptance'], 'UNVERIFIED')
        self.assertFalse(result['evidence_authenticity_verified'])

    def test_empty_bundle_keeps_every_contract_row(self):
        result = acceptance.build_matrix(self.index, self.bundle, self.root)
        self.assertEqual(result['requirement_count'], 2)
        self.assertEqual(result['records_verified'], 0)
        self.assertEqual(result['formal_rows_without_evidence'], 2)

    def test_tampered_or_missing_evidence_is_rejected(self):
        self.file.write_bytes(b'TAMPERED')
        with self.assertRaisesRegex(ValueError, 'DIGEST_MISMATCH'):
            self.matrix()
        self.file.unlink()
        with self.assertRaisesRegex(ValueError, 'MISSING'):
            self.matrix()

    def test_cross_release_commit_artifact_or_environment_is_rejected(self):
        for key, value in [('source_commit', 'c' * 40), ('artifact_sha256', 'd' * 64), ('environment', 'other')]:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'MIXED_RELEASE'):
                self.matrix({**self.record, key: value})

    def test_non_unit_execution_requires_fixed_artifact(self):
        self.bundle['artifact_sha256'] = None
        self.record['artifact_sha256'] = None
        self.assertEqual(self.matrix()['records_verified'], 1)
        with self.assertRaisesRegex(ValueError, 'ARTIFACT_IDENTITY'):
            self.matrix({**self.record, 'layer': 'HTTP'})

    def test_unknown_case_duplicate_run_and_invalid_layer_fail(self):
        for key, value in [('case_id', 'TC-M99-99'), ('layer', 'FAKE'), ('outcome', 'DONE')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.matrix({**self.record, key: value})
        self.bundle['records'] = [self.record, self.record]
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_CASE'):
            acceptance.build_matrix(self.index, self.bundle, self.root)

    def test_failure_history_is_retained_alongside_later_success(self):
        self.bundle['records'] = [{**self.record, 'run_id': 'earlier', 'outcome': 'FAIL'}, self.record]
        result = acceptance.build_matrix(self.index, self.bundle, self.root)
        self.assertEqual(result['declared_layer_outcomes']['UNIT'], {'FAIL': 1, 'PASS': 1})
        self.assertEqual(len(result['requirements'][0]['layers']['UNIT']), 2)

    def test_missing_evidence_only_permitted_for_blocked_or_not_run(self):
        for outcome in ('PASS', 'FAIL'):
            with self.assertRaisesRegex(ValueError, 'REQUIRES_EVIDENCE'):
                self.matrix({**self.record, 'outcome': outcome, 'evidence': []})
        for outcome in ('BLOCKED', 'NOT_RUN'):
            self.assertEqual(self.matrix({**self.record, 'outcome': outcome, 'evidence': []})['records_verified'], 1)

    def test_path_escape_private_directory_urls_and_ambiguous_paths_fail(self):
        for path in ['deploy/private/runtime.json', 'docs/evidence/../../deploy/private/runtime.json',
                     '/etc/passwd', 'https://example.test/file', 'docs/evidence//unit.json',
                     'docs/evidence/%2e%2e/secret', 'docs/evidence/unit.json:secret', 'docs/evidence\\unit.json']:
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.matrix({**self.record, 'evidence': [{**self.proof, 'path': path}]})

    def test_symbolic_link_is_rejected_before_reading_target(self):
        with patch.object(Path, 'is_symlink', return_value=True):
            with self.assertRaisesRegex(ValueError, 'LINK_NOT_ALLOWED'):
                self.matrix()

    def test_duplicate_references_and_oversized_file_fail(self):
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_EVIDENCE'):
            self.matrix({**self.record, 'evidence': [self.proof, self.proof]})
        with patch.object(acceptance, 'MAX_FILE_BYTES', 1):
            with self.assertRaisesRegex(ValueError, 'TOO_LARGE'):
                self.matrix()

    def test_unknown_fields_and_credentials_are_not_allowed_in_record(self):
        with self.assertRaisesRegex(ValueError, 'INVALID_RECORD'):
            self.matrix({**self.record, 'password': 'SYNTHETIC_SECRET'})

    def test_timestamp_requires_explicit_timezone(self):
        for value in ['2026-10-23T07:00:00', None, 'not-a-time']:
            with self.assertRaisesRegex(ValueError, 'INVALID_EXECUTION_TIME'):
                self.matrix({**self.record, 'executed_at': value})

    def test_cli_does_not_overwrite_existing_evidence_or_echo_invalid_secret(self):
        bundle, output = self.root / 'bundle.json', self.root / 'matrix.json'
        bundle.write_text(json.dumps(self.bundle), encoding='utf-8')
        with patch.object(acceptance.contract_progress, 'generate', return_value=json.dumps(self.index)):
            with patch('sys.stdout', new_callable=io.StringIO):
                self.assertEqual(acceptance.main(['--bundle', str(bundle), '--output', str(output)]), 0)
                original = output.read_bytes()
                self.assertEqual(acceptance.main(['--bundle', str(bundle), '--output', str(output)]), 1)
                self.assertEqual(output.read_bytes(), original)
        bundle.write_text('{"token":"SYNTHETIC_SECRET"}', encoding='utf-8')
        with patch('sys.stdout', new_callable=io.StringIO) as stdout:
            self.assertEqual(acceptance.main(['--bundle', str(bundle), '--output', str(output)]), 1)
            self.assertNotIn('SYNTHETIC_SECRET', stdout.getvalue())


if __name__ == '__main__':
    unittest.main()

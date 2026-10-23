"""Synthetic reference-only backend; no credentials, KMS, database or live rotation."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from credential_rotation import RotationCoordinator


def request():
    return {'rotation_id': 'unit-rotation-1', 'scope': 'unit-project-db', 'active_version': 'v1',
            'candidate_version': 'v2', 'expected_generation': 1, 'authorization_ref': 'UNIT-AUTH'}


class CredentialRotationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.coordinator = RotationCoordinator(self.root)
        self.backend = Mock()
        self.backend.authorize.return_value = True
        self.backend.authorize_retirement.return_value = True
        self.backend.validate_candidate.return_value = True
        self.observed = {'active_version': 'v1', 'generation': 1, 'activation_operation': None,
                         'candidate_usable': False, 'previous_revoked': False}
        self.backend.observe.side_effect = lambda _: deepcopy(self.observed)
        self.backend.activate_compare_and_set.side_effect = lambda _: self.observed.update(
            active_version='v2', generation=2, activation_operation='unit-rotation-1', candidate_usable=True)
        self.backend.retire_compare_and_set.side_effect = lambda *_: self.observed.update(previous_revoked=True)

    def test_missing_backend_is_blocked_and_never_claims_completion(self):
        result = self.coordinator.advance(request())
        self.assertEqual(result['state'], 'PREPARED')
        self.assertEqual(result['code'], 'AUTHORIZED_BACKEND_NOT_CONFIGURED')
        self.assertFalse(result['production_ready'])

    def test_activation_retains_old_version_until_separate_authorization(self):
        result = self.coordinator.advance(request(), self.backend)
        self.assertEqual(result['state'], 'ACTIVE')
        self.backend.retire_compare_and_set.assert_not_called()
        self.assertFalse(self.observed['previous_revoked'])
        result = self.coordinator.advance(request(), self.backend, 'UNIT-RETIRE')
        self.assertEqual(result['state'], 'COMPLETE')
        self.backend.activate_compare_and_set.assert_called_once()
        self.backend.retire_compare_and_set.assert_called_once()

    def test_denied_authorization_or_candidate_validation_never_mutates_backend(self):
        self.backend.authorize.return_value = False
        self.assertEqual(self.coordinator.advance(request(), self.backend)['state'], 'PREPARED')
        self.backend.authorize.return_value = True
        self.backend.validate_candidate.return_value = False
        self.assertEqual(self.coordinator.advance(request(), self.backend)['code'], 'CANDIDATE_NOT_VERIFIED')
        self.backend.activate_compare_and_set.assert_not_called()
        self.backend.retire_compare_and_set.assert_not_called()

    def test_activation_timeout_then_reconciliation_never_resubmits_unknown_write(self):
        def activate(_):
            self.observed.update(active_version='v2', generation=2, activation_operation='unit-rotation-1', candidate_usable=True)
            raise TimeoutError('PRIVATE_SECRET')
        self.backend.activate_compare_and_set.side_effect = activate
        self.backend.observe.side_effect = TimeoutError('PRIVATE_SECRET')
        result = self.coordinator.advance(request(), self.backend)
        self.assertEqual(result['state'], 'ACTIVATING')
        self.assertNotIn('PRIVATE_SECRET', json.dumps(result))
        self.backend.observe.side_effect = lambda _: deepcopy(self.observed)
        result = self.coordinator.advance(request(), self.backend)
        self.assertEqual(result['state'], 'ACTIVE')
        self.backend.activate_compare_and_set.assert_called_once()

    def test_retirement_timeout_never_retries_until_observation_proves_revocation(self):
        self.backend.retire_compare_and_set.side_effect = TimeoutError('PRIVATE_SECRET')
        result = self.coordinator.advance(request(), self.backend, 'UNIT-RETIRE')
        self.assertEqual(result['state'], 'RETIRING')
        self.coordinator.advance(request(), self.backend, 'UNIT-RETIRE')
        self.backend.retire_compare_and_set.assert_called_once()
        self.observed['previous_revoked'] = True
        self.assertEqual(self.coordinator.advance(request(), self.backend)['state'], 'COMPLETE')

    def test_changed_generation_other_operator_or_unusable_candidate_never_retires(self):
        self.coordinator.advance(request(), self.backend)
        for field, value in [('generation', 3), ('activation_operation', 'another-operation'), ('candidate_usable', False)]:
            original = self.observed[field]
            self.observed[field] = value
            self.assertEqual(self.coordinator.advance(request(), self.backend, 'UNIT-RETIRE')['code'],
                             'ACTIVATION_OR_GENERATION_UNCONFIRMED')
            self.observed[field] = original
        self.backend.retire_compare_and_set.assert_not_called()

    def test_denied_retirement_does_not_remove_old_credential(self):
        self.backend.authorize_retirement.return_value = False
        self.assertEqual(self.coordinator.advance(request(), self.backend, 'UNIT-RETIRE')['code'], 'RETIREMENT_NOT_AUTHORIZED')
        self.backend.retire_compare_and_set.assert_not_called()

    def test_pending_rotation_blocks_another_request_in_same_scope(self):
        self.coordinator.advance(request())
        result = self.coordinator.advance({**request(), 'rotation_id': 'unit-other'}, self.backend)
        self.assertEqual(result['code'], 'ANOTHER_ROTATION_UNRESOLVED')
        self.backend.authorize.assert_not_called()

    def test_existing_lock_is_not_removed(self):
        folder = self.root / hashlib.sha256(request()['scope'].encode()).hexdigest()
        folder.mkdir()
        lock = folder / 'rotation.lock'
        lock.write_text('another-process')
        result = self.coordinator.advance(request(), self.backend)
        self.assertEqual(result['code'], 'ROTATION_LOCKED')
        self.assertEqual(lock.read_text(), 'another-process')
        self.backend.authorize.assert_not_called()

    def test_tampered_or_partial_journal_refuses_resume(self):
        self.coordinator.advance(request(), self.backend)
        files = sorted(self.root.glob('*/*.json'))
        first = json.loads(files[0].read_bytes())
        first['recorded_at'] = 'tampered'
        files[0].write_text(json.dumps(first))
        with self.assertRaises(ValueError):
            self.coordinator.advance(request(), self.backend)
        files[0].write_text('{')
        with self.assertRaises(ValueError):
            self.coordinator.advance(request(), self.backend)

    def test_completed_retry_is_idempotent_and_keeps_journal_unchanged(self):
        first = self.coordinator.advance(request(), self.backend, 'UNIT-RETIRE')
        before = {str(path): path.read_bytes() for path in self.root.glob('*/*.json')}
        self.assertEqual(self.coordinator.advance(request(), self.backend), first)
        self.assertEqual({str(path): path.read_bytes() for path in self.root.glob('*/*.json')}, before)
        self.backend.activate_compare_and_set.assert_called_once()
        self.backend.retire_compare_and_set.assert_called_once()

    def test_reusing_operation_id_with_changed_payload_and_secret_fields_fails(self):
        self.coordinator.advance(request())
        for change in [{'candidate_version': 'v3'}, {'password': 'PRIVATE_SECRET'}]:
            with self.assertRaises(ValueError):
                self.coordinator.advance({**request(), **change}, self.backend)
        self.backend.authorize.assert_not_called()

    def test_journal_records_intent_before_backend_activation_or_retirement(self):
        def activate(_):
            self.assertEqual(json.loads(sorted(self.root.glob('*/*.json'))[-1].read_bytes())['state'], 'ACTIVATING')
            self.observed.update(active_version='v2', generation=2, activation_operation='unit-rotation-1', candidate_usable=True)
        def retire(*_):
            self.assertEqual(json.loads(sorted(self.root.glob('*/*.json'))[-1].read_bytes())['state'], 'RETIRING')
            self.observed['previous_revoked'] = True
        self.backend.activate_compare_and_set.side_effect = activate
        self.backend.retire_compare_and_set.side_effect = retire
        self.assertEqual(self.coordinator.advance(request(), self.backend, 'UNIT-RETIRE')['state'], 'COMPLETE')

    def test_crash_after_activation_intent_resumes_by_observation_only(self):
        def crash(_):
            self.observed.update(active_version='v2', generation=2, activation_operation='unit-rotation-1', candidate_usable=True)
            raise KeyboardInterrupt()
        self.backend.activate_compare_and_set.side_effect = crash
        with self.assertRaises(KeyboardInterrupt):
            self.coordinator.advance(request(), self.backend)
        self.assertEqual(self.coordinator.advance(request(), self.backend)['state'], 'ACTIVE')
        self.backend.activate_compare_and_set.assert_called_once()

    def test_skipped_journal_transition_and_last_event_mutation_are_rejected(self):
        self.coordinator.advance(request(), self.backend)
        last = sorted(self.root.glob('*/*.json'))[-1]
        event = json.loads(last.read_bytes())
        event['state'] = 'COMPLETE'
        last.write_text(json.dumps(event))
        with self.assertRaises(ValueError):
            self.coordinator.advance(request(), self.backend)

    def test_retirement_authorization_reference_is_preserved_in_append_only_history(self):
        self.coordinator.advance(request(), self.backend, 'UNIT-RETIRE')
        events = [json.loads(path.read_bytes()) for path in sorted(self.root.glob('*/*.json'))]
        self.assertEqual([event['retirement_approval_ref'] for event in events[-2:]], ['UNIT-RETIRE', 'UNIT-RETIRE'])
        self.assertTrue(all(event['retirement_approval_ref'] is None for event in events[:-2]))


if __name__ == '__main__':
    unittest.main()

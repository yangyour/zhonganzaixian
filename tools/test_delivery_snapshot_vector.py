import json
import unittest
from pathlib import Path
from delivery_snapshot_vector import build_vector

class DeliverySnapshotVectorTest(unittest.TestCase):
    def test_independent_vector_matches_committed_fixture(self):
        fixture=Path(__file__).resolve().parents[1]/'fixtures/delivery-receipt-snapshot-vector-20261021.json'
        self.assertEqual(json.loads(fixture.read_text()),build_vector())
    def test_vector_does_not_claim_provider_proof_or_automatic_send(self):
        v=build_vector()
        self.assertTrue(v['synthetic'])
        self.assertFalse(v['provider_receipt_verified'])
        self.assertFalse(v['automatic_send_enabled'])
        self.assertEqual(v['version'],v['transitions'])
        self.assertEqual(v['logical_delivered_count'],1)
        self.assertNotEqual(v['observed_at'],v['first_delivered_at'])

import hashlib
import json
import unittest
from pathlib import Path
from delivery_journal_vector import build_vector

class DeliveryJournalVectorTest(unittest.TestCase):
    def test_committed_vector_matches_independent_encoder(self):
        actual=json.loads((Path(__file__).resolve().parents[1]/'fixtures/delivery-receipt-journal-vector-20261021.json').read_text())
        self.assertEqual(actual,build_vector())
    def test_each_link_binds_previous_digest_and_is_bounded(self):
        vector=build_vector();previous=vector['root_sha256'];root=bytes.fromhex(vector['root_hex'])
        self.assertEqual(hashlib.sha256(root).hexdigest(),previous)
        for entry in vector['entries']:
            raw=bytes.fromhex(entry['hex']);fmt_length=int.from_bytes(raw[:2],'big');offset=2+fmt_length+8
            self.assertEqual(raw[offset:offset+32].hex(),previous)
            self.assertEqual(hashlib.sha256(raw).hexdigest(),entry['sha256'])
            self.assertLessEqual(len(raw),1024);previous=entry['sha256']
        self.assertEqual(previous,vector['head_sha256'])
    def test_vector_does_not_claim_database_or_provider_authority(self):
        v=build_vector()
        for key in ['database_persisted','provider_receipt_verified','automatic_send_enabled']:self.assertFalse(v[key])
        self.assertEqual(v['version'],6);self.assertEqual(v['logical_delivered_count'],1)
if __name__=='__main__':unittest.main()

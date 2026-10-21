"""Independent standard-library-only public synthetic receipt history vector. No database or provider calls."""
import argparse
import calendar
import hashlib
import json
import struct
import uuid
from pathlib import Path

FORMAT = 'MOP_DELIVERY_RECEIPT_SNAPSHOT_V1'

def build_vector():
    def ident(n): return f'7f000000-0000-4000-8000-{n:012d}'
    def uid(n): return uuid.UUID(ident(n)).bytes
    def utf(value):
        raw = value.encode('ascii')
        return struct.pack('>H', len(raw)) + raw
    seconds = calendar.timegm((2026, 10, 21, 0, 0, 0))
    nanos = 123456789
    def instant(offset): return struct.pack('>qi', seconds, nanos + offset)
    def start(request, at): return b'\x01' + uid(request) + instant(at)
    def receipt(request, receipt_id, status, occurred, observed):
        return b'\x03' + uid(request) + uid(receipt_id) + utf(status) + instant(occurred) + instant(observed)
    transitions = [start(6, 1), receipt(6, 8, 'ACCEPTED', 2, 3), receipt(6, 9, 'FAILED', 4, 5),
                   b'\x02' + instant(6), start(7, 7), receipt(6, 10, 'DELIVERED', 2, 8)]
    header = utf(FORMAT) + uid(1) + uid(2) + uid(3) + uid(4) + utf('WEBHOOK') + uid(5) + utf('OPENED') + instant(0) + struct.pack('>qi', 6, 6)
    snapshot = header + b''.join(transitions)
    return {'synthetic': True, 'provider_receipt_verified': False, 'automatic_send_enabled': False,
            'reference_implementation': 'Python standard library struct/uuid independent from Java DataOutputStream',
            'format': FORMAT, 'tenant_id': ident(1), 'delivery_id': ident(2),
            'deduplication': {'resource_pool_id': ident(3), 'event_id': ident(4), 'channel': 'WEBHOOK', 'receiver_id': ident(5), 'stage': 'OPENED'},
            'queued_at': '2026-10-21T00:00:00.123456789Z', 'observed_at': '2026-10-21T00:00:00.123456797Z',
            'first_delivered_at': '2026-10-21T00:00:00.123456791Z', 'version': 6, 'transitions': 6, 'attempts': 2, 'receipts': 3,
            'status': 'DELIVERED', 'logical_delivered_count': 1, 'bytes': len(snapshot),
            'snapshot_hex': snapshot.hex(), 'sha256': hashlib.sha256(snapshot).hexdigest()}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Compare the committed synthetic vector; no rewriting.')
    parser.parse_args()
    expected = build_vector()
    actual = json.loads((Path(__file__).resolve().parents[1] / 'fixtures/delivery-receipt-snapshot-vector-20261021.json').read_text())
    if actual != expected: raise SystemExit('Snapshot vector differs; do not silently regenerate an accepted format.')
    print(json.dumps({'result': 'PASS', 'scope': 'INDEPENDENT_SYNTHETIC_VECTOR_ONLY', 'bytes': expected['bytes'], 'sha256': expected['sha256'], 'provider_calls': 0}))

if __name__ == '__main__': main()

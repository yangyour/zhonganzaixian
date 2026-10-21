"""Independent bounded synthetic journal vector; no database, provider or runtime configuration."""
import calendar
import hashlib
import json
import struct
import uuid
from pathlib import Path

def build_vector():
    def uid(n): return uuid.UUID(f'7f000000-0000-4000-8000-{n:012d}').bytes
    def utf(s):
        raw=s.encode('ascii')
        return struct.pack('>H',len(raw))+raw
    seconds=calendar.timegm((2026,10,21,0,0,0))
    def time(offset): return struct.pack('>qi',seconds,123456789+offset)
    root=utf('MOP_DELIVERY_RECEIPT_SNAPSHOT_V1')+uid(1)+uid(2)+uid(3)+uid(4)+utf('WEBHOOK')+uid(5)+utf('OPENED')+time(0)+struct.pack('>qi',0,0)
    def start(request,at): return b'\x01'+uid(request)+time(at)
    def receipt(request,receipt,status,occurred,observed): return b'\x03'+uid(1)+uid(2)+uid(request)+uid(receipt)+utf(status)+time(occurred)+time(observed)
    transitions=[start(6,1),receipt(6,8,'ACCEPTED',2,3),receipt(6,9,'FAILED',4,5),b'\x02'+time(6),start(7,7),receipt(6,10,'DELIVERED',2,8)]
    root_sha=hashlib.sha256(root).hexdigest();head=root_sha;entries=[]
    for i,transition in enumerate(transitions):
        raw=utf('MOP_DELIVERY_RECEIPT_JOURNAL_V1')+struct.pack('>q',i+1)+bytes.fromhex(head)+struct.pack('>q',i)+uid(100+i)+uid(20)+struct.pack('>q',7)+utf(f'journal-{i+1}')+transition
        head=hashlib.sha256(raw).hexdigest();entries.append({'version':i+1,'bytes':len(raw),'hex':raw.hex(),'sha256':head})
    return {'synthetic':True,'database_persisted':False,'provider_receipt_verified':False,'automatic_send_enabled':False,'format':'MOP_DELIVERY_RECEIPT_JOURNAL_V1','root_hex':root.hex(),'root_sha256':root_sha,'entries':entries,'head_sha256':head,'bytes':len(root)+sum(e['bytes'] for e in entries),'version':6,'status':'DELIVERED','logical_delivered_count':1}

def main():
    expected=build_vector();actual=json.loads((Path(__file__).resolve().parents[1]/'fixtures/delivery-receipt-journal-vector-20261021.json').read_text())
    if actual!=expected: raise SystemExit('Journal vector differs; do not silently change the accepted format.')
    print(json.dumps({'result':'PASS','scope':'INDEPENDENT_SYNTHETIC_VECTOR_ONLY','bytes':expected['bytes'],'head_sha256':expected['head_sha256'],'provider_calls':0}))
if __name__=='__main__':main()

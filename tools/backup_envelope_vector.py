import argparse
import cryptography
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from pathlib import Path
import struct,json,hashlib
# Optional validation-only dependency; never imported by the Java runtime.
if cryptography.__version__ != '50.0.0': raise SystemExit('Reproducible oracle requires cryptography==50.0.0; no fixture was changed.')
# Public synthetic vector, NOT a deployment key or real wrapped KMS key.
fmt='MOP_BACKUP_AES256_GCM_V1';algorithm='AES-256-GCM';ids=[f'79000000-0000-4000-8000-{n:012d}' for n in [1,2,3,4]]
ref='kms-key-ref:synthetic-vector';wrapped=b'SYNTHETIC-WRAPPED-KEY-NOT-A-KMS-CIPHERTEXT';key=bytes(range(32));nonce=bytes(range(12));plaintext=b'SYNTHETIC router configuration\nhostname example\n'
def utf(value):b=value.encode('ascii');return struct.pack('>H',len(b))+b
header=utf(fmt)+utf(algorithm)+b''.join(utf(v) for v in ids[:3])+struct.pack('>i',1)+utf(ids[3])+utf(ref)+struct.pack('>i',len(wrapped))+wrapped
cipher=AESGCM(key).encrypt(nonce,plaintext,header);envelope=header+nonce+struct.pack('>i',len(cipher))+cipher
out={'synthetic':True,'production_key':False,'reference_implementation':'Python cryptography 50.0.0 AESGCM independent from JCE','format':fmt,'algorithm':algorithm,'tenant_id':ids[0],'asset_id':ids[1],'run_id':ids[2],'version_id':ids[3],'attempt_number':1,'key_reference':ref,'public_test_key_hex':key.hex(),'wrapped_test_key_hex':wrapped.hex(),'nonce_hex':nonce.hex(),'plaintext_hex':plaintext.hex(),'aad_hex':header.hex(),'ciphertext_hex':cipher.hex(),'envelope_hex':envelope.hex(),'envelope_sha256':hashlib.sha256(envelope).hexdigest()}
parser=argparse.ArgumentParser(description='Independent public synthetic backup envelope vector; no KMS, credentials, network or production encryption.')
parser.add_argument('--check',action='store_true',help='Compare the committed synthetic vector without writing; the default action.')
args=parser.parse_args()
fixture=Path(__file__).resolve().parents[1]/'fixtures/backup-envelope-synthetic-vector-20261021.json'
committed=json.loads(fixture.read_text())
assert committed==out,'Synthetic vector differs; do not silently regenerate an accepted format.'
print(json.dumps({'result':'PASS','scope':'INDEPENDENT_SYNTHETIC_VECTOR_ONLY','cryptography_version':cryptography.__version__,'envelope_bytes':len(envelope),'envelope_sha256':out['envelope_sha256'],'kms_calls':0,'production_keys':False}))

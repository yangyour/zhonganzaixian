"""Bounded SNMP v2c GET/GETNEXT diagnostics, not a runtime collector.

Runs unchanged in the designated host / existing collector network namespace.
Only the one inventory target and port 161 are permitted. No SET, scanning,
credential discovery, service installation, or persistent collection.
"""
from datetime import datetime, timezone
import errno
import json
import secrets
import socket
import sys
import time

TARGET = '192.168.1.2'
MAX_PACKET = 65535
IDENTITY = ('1.3.6.1.2.1.1.1.0', '1.3.6.1.2.1.1.2.0', '1.3.6.1.2.1.1.3.0')


def tlv(tag, value):
    size = len(value)
    length = bytes([size]) if size < 128 else (lambda b: bytes([128 | len(b)]) + b)(
        size.to_bytes((size.bit_length() + 7) // 8, 'big'))
    return bytes([tag]) + length + value


def integer(value):
    if type(value) is not int or not 0 <= value <= 2147483647:
        raise ValueError('Invalid request integer')
    raw = value.to_bytes(max(1, (value.bit_length() + 7) // 8), 'big')
    return tlv(2, (b'\0' if raw[0] & 128 else b'') + raw)


def base128(value):
    out = [value & 127]
    value >>= 7
    while value:
        out.insert(0, 128 | (value & 127))
        value >>= 7
    return bytes(out)


def encode_oid(value):
    parts = tuple(int(x) for x in value.split('.'))
    if (len(parts) < 2 or len(parts) > 128 or parts[0] not in (0, 1, 2)
            or not 0 <= parts[1] <= 4294967295 or parts[0] < 2 and parts[1] > 39
            or any(x < 0 or x > 4294967295 for x in parts[2:])):
        raise ValueError('Invalid OID')
    return tlv(6, b''.join(base128(x) for x in (40 * parts[0] + parts[1], *parts[2:])))


def decode_oid(raw):
    parts, number, pending = [], 0, False
    for byte in raw:
        number = number * 128 + (byte & 127)
        if number > 4294967375:
            raise ValueError('Invalid OID arc')
        pending = bool(byte & 128)
        if not pending:
            parts.append(number)
            number = 0
    if not parts or pending or len(parts) > 127:
        raise ValueError('Invalid OID encoding')
    first = min(parts[0] // 40, 2)
    if parts[0] - first * 40 > 4294967295 or any(x > 4294967295 for x in parts[1:]):
        raise ValueError('Invalid OID arc')
    return '.'.join(map(str, (first, parts[0] - first * 40, *parts[1:])))


class Reader:
    def __init__(self, raw):
        self.raw, self.offset = raw, 0

    def read(self, expected=None):
        if self.offset + 2 > len(self.raw):
            raise ValueError('Truncated BER')
        tag, length = self.raw[self.offset:self.offset + 2]
        self.offset += 2
        if length & 128:
            count = length & 127
            if not 1 <= count <= 3 or self.offset + count > len(self.raw):
                raise ValueError('Invalid BER length')
            length = int.from_bytes(self.raw[self.offset:self.offset + count], 'big')
            self.offset += count
        end = self.offset + length
        if end > len(self.raw) or expected is not None and tag != expected:
            raise ValueError('Invalid BER field')
        value = self.raw[self.offset:end]
        self.offset = end
        return tag, value

    def done(self):
        if self.offset != len(self.raw):
            raise ValueError('Trailing BER')

    def number(self):
        _, value = self.read(2)
        if not 1 <= len(value) <= 5:
            raise ValueError('Invalid BER integer')
        return int.from_bytes(value, 'big', signed=True)


def request(community, oids, request_id, next_value=False):
    rows = b''.join(tlv(48, encode_oid(oid) + tlv(5, b'')) for oid in oids)
    pdu = tlv(161 if next_value else 160,
              integer(request_id) + integer(0) + integer(0) + tlv(48, rows))
    return tlv(48, integer(1) + tlv(4, community.encode('utf-8')) + pdu)


def response(raw, community, request_id, requested, next_value=False):
    if len(raw) > MAX_PACKET:
        raise ValueError('Packet too large')
    outer = Reader(raw)
    message = Reader(outer.read(48)[1])
    outer.done()
    if message.number() != 1 or message.read(4)[1] != community.encode('utf-8'):
        raise ValueError('Response identity mismatch')
    pdu = Reader(message.read(162)[1])
    message.done()
    if pdu.number() != request_id:
        raise ValueError('Request ID mismatch')
    error, error_index = pdu.number(), pdu.number()
    bindings = Reader(pdu.read(48)[1])
    pdu.done()
    if not 0 <= error <= 18 or not 0 <= error_index <= len(requested):
        raise ValueError('Invalid error status/index')
    if error != 0:
        return {'status': 'AGENT_ERROR', 'error_status': error, 'error_index': error_index, 'values': []}
    if error_index != 0:
        raise ValueError('Invalid error index')
    rows = []
    for expected in requested:
        row = Reader(bindings.read(48)[1])
        oid = decode_oid(row.read(6)[1])
        tag, value = row.read()
        row.done()
        if (not next_value and oid != expected or next_value and tag not in (128, 129, 130)
                and tuple(map(int, oid.split('.'))) <= tuple(map(int, expected.split('.')))):
            raise ValueError('OID mismatch')
        if tag in (128, 129, 130):
            if value:
                raise ValueError('Invalid exception value')
            status = {128: 'NO_SUCH_OBJECT', 129: 'NO_SUCH_INSTANCE', 130: 'END_OF_MIB'}[tag]
            decoded = None
        elif tag in (2, 65, 66, 67, 70):
            if not 1 <= len(value) <= (9 if tag == 70 else 5):
                raise ValueError('Invalid numeric value')
            number = int.from_bytes(value, 'big', signed=tag == 2)
            minimum, maximum = (-2147483648, 2147483647) if tag == 2 else (
                0, 18446744073709551615 if tag == 70 else 4294967295)
            if not minimum <= number <= maximum:
                raise ValueError('Numeric value outside ASN.1 bounds')
            status, decoded = 'VALUE', str(number)
        elif tag == 4:
            # A configured secret must never be reflected by the agent into evidence.
            decoded = value.decode('utf-8', 'replace').replace(community, '[REDACTED]')
            decoded = ''.join(c if c >= ' ' and c != '\x7f' else '\ufffd' for c in decoded)[:2048]
            status = 'VALUE'
        elif tag == 6:
            status, decoded = 'VALUE', decode_oid(value)
        else:
            status, decoded = 'UNSUPPORTED_VALUE_TYPE', None
        rows.append({'oid': oid, 'asn1_tag': tag, 'status': status, 'value': decoded})
    bindings.done()
    return {'status': 'RESPONSE', 'values': rows}


def exchange(community, oids, next_value=False):
    request_id = secrets.randbelow(2147483646) + 1
    packet = request(community, oids, request_id, next_value)
    started = time.monotonic()
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as connection:
            connection.settimeout(2)
            connection.connect((TARGET, 161))
            connection.send(packet)
            raw = connection.recv(MAX_PACKET + 1)
        result = response(raw, community, request_id, oids, next_value)
    except socket.timeout:
        result = {'status': 'TIMEOUT_NO_RESPONSE', 'values': []}
    except OSError as failure:
        status = {errno.ECONNREFUSED: 'UDP_PORT_UNREACHABLE', errno.EHOSTUNREACH: 'HOST_UNREACHABLE',
                  errno.ENETUNREACH: 'NETWORK_UNREACHABLE', errno.EACCES: 'LOCAL_ACCESS_DENIED',
                  errno.EPERM: 'LOCAL_ACCESS_DENIED'}.get(failure.errno, 'UDP_TRANSPORT_UNAVAILABLE')
        result = {'status': status, 'os_errno': failure.errno, 'values': []}
    except ValueError:
        result = {'status': 'INVALID_RESPONSE', 'values': []}
    result['elapsed_ms'] = round((time.monotonic() - started) * 1000)
    result['received_at'] = datetime.now(timezone.utc).isoformat()
    return result


def probe(config):
    if (not isinstance(config, dict) or set(config) != {'target', 'version', 'community', 'columns'}
            or config['target'] != TARGET or config['version'] != '2c'
            or not isinstance(config['community'], str) or not 1 <= len(config['community'].encode()) <= 255
            or any(ord(c) < 32 or ord(c) == 127 for c in config['community'])
            or not isinstance(config['columns'], list) or not 1 <= len(config['columns']) <= 32):
        raise ValueError('Invalid fixed-target probe configuration')
    for oid in config['columns']:
        if not isinstance(oid, str) or not (oid.startswith('1.3.6.1.2.1.') or oid.startswith('1.3.6.1.4.1.2011.')):
            raise ValueError('Invalid diagnostic column')
        encode_oid(oid)
    community = config['community']
    identity = exchange(community, IDENTITY)
    report = {'target': TARGET, 'port': 161, 'version': '2c', 'identity': identity,
              'columns': [], 'read_only': True, 'resource_writes': 0,
              'monitoring_ready': False, 'persistent_collection': 'NOT_CONFIGURED'}
    rows = identity.get('values', [])
    if (identity['status'] != 'RESPONSE' or len(rows) != 3
            or [r['asn1_tag'] for r in rows] != [4, 6, 67]
            or any(r['status'] != 'VALUE' for r in rows)
            or not rows[1]['value'].startswith('1.3.6.1.4.1.2011.')):
        report['assessment'] = ('IDENTITY_NOT_VERIFIED' if identity['status'] == 'RESPONSE' else identity['status'])
        return report
    report['assessment'] = 'HUAWEI_IDENTITY_READ'
    for oid in config['columns']:
        result = exchange(community, [oid], next_value=True)
        for row in result.get('values', []):
            # A GETNEXT outside this column says nothing about its value/support.
            if not row['oid'].startswith(oid + '.'):
                row.update(status='COLUMN_NOT_RETURNED', value=None)
            else:
                row['index'] = row['oid'][len(oid) + 1:]
        report['columns'].append({'column_oid': oid, **result})
        if result['status'] not in ('RESPONSE', 'AGENT_ERROR'):
            break
    return report


if __name__ == '__main__':
    try:
        raw = sys.stdin.buffer.read(16385)
        if len(raw) > 16384:
            raise ValueError('Probe input too large')
        print(json.dumps(probe(json.loads(raw))))
    except Exception:
        print(json.dumps({'assessment': 'PROBE_FAILED', 'monitoring_ready': False, 'details': 'omitted'}))
        sys.exit(1)

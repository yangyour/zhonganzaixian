"""Bounded, read-only network preflight; a response is never device health.

This standard-library module also runs in the designated server / collector
network namespace. No discovery, login, SNMP guessing, or configuration writes.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import errno
import ipaddress
import json
import re
import socket
import subprocess
import sys
import time

PORTS = (22, 80, 443)
TIMEOUT_SECONDS = 2
PRIVATE_NETWORKS = tuple(ipaddress.ip_network(x) for x in
                         ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
# 2026-10-14 direct user correction. This is one exact device/address exception,
# not a private-range classification or permission to probe neighbouring addresses.
APPROVED_NON_PRIVATE_TARGETS = frozenset({('sangfor-firewall', ipaddress.IPv4Address('192.169.11.1'))})


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def validate_manifest(value):
    if (not isinstance(value, dict)
            or set(value) != {'schema_version', 'environment', 'devices'}
            or type(value['schema_version']) is not int or value['schema_version'] != 1
            or value['environment'] != 'lan-test-20261008'
            or not isinstance(value['devices'], list) or not 1 <= len(value['devices']) <= 32):
        raise ValueError('Invalid device manifest')
    keys, addresses, devices = set(), set(), []
    for device in value['devices']:
        if not isinstance(device, dict) or set(device) != {'key', 'device_type', 'brand', 'model', 'ip'}:
            raise ValueError('Invalid device fields; credentials are not accepted')
        for text in device.values():
            if (not isinstance(text, str) or not text.strip() or text != text.strip()
                    or len(text) > 128 or any(ord(c) < 32 or 127 <= ord(c) <= 159
                                             or 0xD800 <= ord(c) <= 0xDFFF for c in text)):
                raise ValueError('Invalid device text')
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', device['key']):
            raise ValueError('Invalid device key')
        address = ipaddress.IPv4Address(device['ip'])
        if (not any(address in network for network in PRIVATE_NETWORKS)
                and (device['key'], address) not in APPROVED_NON_PRIVATE_TARGETS):
            raise ValueError('Target is outside private ranges and the explicit device allowlist')
        if device['key'] in keys or str(address) in addresses:
            raise ValueError('Duplicate device key or IP')
        keys.add(device['key'])
        addresses.add(str(address))
        devices.append(dict(device))
    return {'schema_version': 1, 'environment': value['environment'], 'devices': devices}


def tcp_probe(ip, port):
    started = time.monotonic()
    try:
        with socket.create_connection((ip, port), timeout=TIMEOUT_SECONDS):
            state = 'OPEN'
    except (TimeoutError, socket.timeout):
        state = 'TIMEOUT'
    except OSError as error:
        state = {
            errno.ECONNREFUSED: 'REFUSED', errno.ENETUNREACH: 'NETWORK_UNREACHABLE',
            errno.EHOSTUNREACH: 'HOST_UNREACHABLE', errno.EACCES: 'PROBE_DENIED',
            errno.EPERM: 'PROBE_DENIED', errno.ETIMEDOUT: 'TIMEOUT',
        }.get(error.errno, 'PROBE_ERROR')
    return {'port': port, 'state': state, 'elapsed_ms': round((time.monotonic() - started) * 1000)}


def icmp_probe(ip):
    try:
        result = subprocess.run(['ping', '-n', '-c', '2', '-W', '2', ip],
                                capture_output=True, timeout=7, check=False,
                                env={'PATH': '/usr/sbin:/usr/bin:/sbin:/bin', 'LC_ALL': 'C'})
        output = result.stdout.decode('ascii', 'replace')
        counts = re.search(r'(\d+) packets transmitted, (\d+) (?:packets )?received', output)
        sent, received = (int(counts[1]), int(counts[2])) if counts else (None, None)
        if sent != 2 or received is None or not 0 <= received <= 2:
            return {'state': 'PROBE_ERROR', 'sent': None, 'received': None, 'average_rtt_ms': None}
        state = 'REPLY' if result.returncode == 0 and received > 0 else (
            'NO_REPLY' if result.returncode == 1 and received == 0 else 'PROBE_ERROR')
        timing = re.search(r'(?:rtt|round-trip) [^=]+ = [\d.]+/([\d.]+)/', output)
        return {'state': state, 'sent': sent, 'received': received,
                'average_rtt_ms': float(timing[1]) if state == 'REPLY' and timing else None}
    except FileNotFoundError:
        state = 'TOOL_MISSING'
    except subprocess.TimeoutExpired:
        state = 'PROBE_TIMEOUT'
    except OSError:
        state = 'PROBE_ERROR'
    return {'state': state, 'sent': None, 'received': None, 'average_rtt_ms': None}


def route_probe(ip):
    try:
        result = subprocess.run(['ip', '-j', '-4', 'route', 'get', ip],
                                capture_output=True, timeout=3, check=False)
        rows = json.loads(result.stdout) if result.returncode == 0 else []
        if not isinstance(rows, list) or len(rows) != 1 or not isinstance(rows[0], dict):
            return {'state': 'UNCONFIRMED'}
        row = rows[0]
        # No route changes; retain only routing facts for this explicit address.
        return {'state': 'FOUND', **{k: row[k] for k in ('dst', 'gateway', 'dev', 'prefsrc', 'type') if k in row}}
    except (OSError, subprocess.TimeoutExpired, ValueError, TypeError):
        return {'state': 'UNCONFIRMED'}


def assess(icmp, tcp):
    # A reset can be sent by a firewall. Preserve REFUSED without proving device identity.
    responded = icmp['state'] == 'REPLY' or any(item['state'] == 'OPEN' for item in tcp)
    return {
        'network_observation': 'RESPONSE_OBSERVED' if responded else 'UNCONFIRMED',
        'device_identity_verified': False,
        'monitoring_ready': False,
        'monitoring_status': 'CONFIG_PENDING',
        'unknown_reason': 'READ_ONLY_CREDENTIALS_AND_VERIFIED_MAPPING_REQUIRED',
        'device_health': None,
        'snmp': {'status': 'NOT_ATTEMPTED', 'reason': 'CREDENTIALS_NOT_CONFIGURED'},
        'firmware': None, 'sys_object_id': None, 'template_ref': None,
    }


def probe_device(device):
    started = timestamp()
    icmp = icmp_probe(device['ip'])
    tcp = [tcp_probe(device['ip'], port) for port in PORTS]
    return {**device, 'started_at': started, 'completed_at': timestamp(),
            'route': route_probe(device['ip']), 'icmp': icmp, 'tcp': tcp, **assess(icmp, tcp)}


def probe_manifest(value):
    manifest = validate_manifest(value)
    with ThreadPoolExecutor(max_workers=4) as workers:
        devices = list(workers.map(probe_device, manifest['devices']))
    return {'schema_version': 1, 'environment': manifest['environment'],
            'completed_at': timestamp(), 'read_only': True,
            'ports_checked': list(PORTS), 'tcp_timeout_seconds': TIMEOUT_SECONDS,
            'devices': devices}


if __name__ == '__main__':
    try:
        # JSON argv contains only validated inventory, never credentials. Using argv
        # avoids a sudo password left on stdin under a NOPASSWD policy being parsed.
        data = sys.argv[1].encode('utf-8') if len(sys.argv) == 2 else sys.stdin.buffer.read(32769)
        if len(data) > 32768:
            raise ValueError('Manifest too large')
        print(json.dumps(probe_manifest(json.loads(data)), ensure_ascii=True))
    except Exception:
        print(json.dumps({'status': 'PROBE_FAILED', 'details': 'omitted'}))
        raise SystemExit(1)

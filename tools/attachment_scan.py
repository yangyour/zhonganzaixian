"""Bounded candidate ClamD stream adapter; does not release, move, delete or publish attachments."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import socket
import struct
import time

MAX_CONTENT_BYTES = 20 * 1024 * 1024
MAX_REPLY_BYTES = 4096
CHUNK_BYTES = 65536
DEADLINE_SECONDS = 15


def timeout_left(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError('SCAN_DEADLINE_EXCEEDED')
    return remaining


def exchange(port, command, deadline, content=None):
    """New bounded connection per command; never send server-visible file paths or administration commands."""
    if command not in (b'zVERSION\0', b'zINSTREAM\0'):
        raise ValueError('SCANNER_COMMAND_NOT_ALLOWED')
    with socket.create_connection(('127.0.0.1', port), timeout=timeout_left(deadline)) as connection:
        connection.settimeout(timeout_left(deadline))
        connection.sendall(command)
        if command == b'zINSTREAM\0':
            for offset in range(0, len(content), CHUNK_BYTES):
                chunk = content[offset:offset + CHUNK_BYTES]
                connection.settimeout(timeout_left(deadline))
                connection.sendall(struct.pack('!I', len(chunk)) + chunk)
            connection.settimeout(timeout_left(deadline))
            connection.sendall(b'\0\0\0\0')
        reply = bytearray()
        while True:
            connection.settimeout(timeout_left(deadline))
            chunk = connection.recv(min(1024, MAX_REPLY_BYTES + 1 - len(reply)))
            if not chunk:
                break
            reply.extend(chunk)
            if len(reply) > MAX_REPLY_BYTES:
                raise ValueError('SCANNER_REPLY_TOO_LARGE')
        # Require EOF after exactly one terminated record; truncated or conflicting replies are unknown.
        if not reply.endswith(b'\0') or reply.count(0) != 1:
            raise ValueError('SCANNER_REPLY_INVALID')
        return bytes(reply[:-1])


def scan_bytes(content, expected_sha256, port=None, enabled=False):
    report = {'format': 1, 'recorded_at_utc': datetime.now(timezone.utc).isoformat(),
              'provider': 'CLAMD_CANDIDATE_NOT_APPROVED', 'verdict': 'UNKNOWN', 'code': 'SCAN_DISABLED',
              'scan_attempted': False, 'content_sha256': None, 'bytes': None, 'engine_version': None,
              'signature_database_version': None, 'signature_freshness_verified': False,
              'scanner_policy_verified': False, 'release_allowed': False, 'attachment_state_changed': False}
    if enabled is not True:
        return report
    if (type(port) is not int or not 1 <= port <= 65535 or not isinstance(content, bytes)
            or not 0 < len(content) <= MAX_CONTENT_BYTES or not isinstance(expected_sha256, str)
            or re.fullmatch(r'[0-9a-f]{64}', expected_sha256) is None):
        return {**report, 'code': 'INVALID_SCAN_INPUT'}
    digest = hashlib.sha256(content).hexdigest()
    if digest != expected_sha256:
        return {**report, 'code': 'CONTENT_DIGEST_MISMATCH'}
    report.update(content_sha256=digest, bytes=len(content))
    deadline = time.monotonic() + DEADLINE_SECONDS
    try:
        before = exchange(port, b'zVERSION\0', deadline)
        version = re.fullmatch(rb'ClamAV ([A-Za-z0-9._-]{1,64})/([0-9]{1,16})/[^\x00-\x1f\x7f]{1,128}', before)
        if version is None:
            return {**report, 'code': 'SCANNER_VERSION_UNVERIFIED'}
        report.update(engine_version=version[1].decode('ascii'), signature_database_version=version[2].decode('ascii'))
        report['scan_attempted'] = True
        verdict = exchange(port, b'zINSTREAM\0', deadline, content)
        # If signatures reload while scanning, the exact engine/database association is unknown.
        if exchange(port, b'zVERSION\0', deadline) != before:
            return {**report, 'code': 'SCANNER_VERSION_CHANGED'}
        if verdict == b'stream: OK':
            return {**report, 'verdict': 'NO_THREAT_REPORTED', 'code': 'SCANNER_REPORTED_OK'}
        if re.fullmatch(rb'stream: [^\x00-\x1f\x7f]{1,512} FOUND', verdict):
            return {**report, 'verdict': 'THREAT_REPORTED', 'code': 'SCANNER_REPORTED_THREAT'}
        return {**report, 'code': 'SCANNER_REPLY_UNRECOGNIZED'}
    except Exception:
        # Vendor errors, detection names and local transport messages are never emitted.
        return {**report, 'code': 'SCANNER_UNAVAILABLE_OR_PROTOCOL_ERROR'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--file', required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--port', type=int, help='Existing approved scanner tunnel on 127.0.0.1; never auto-created')
    parser.add_argument('--scan', action='store_true', help='Explicitly submit the supplied bytes to the configured scanner')
    parser.add_argument('--output', required=True, help='New redacted evidence file')
    args = parser.parse_args(argv)
    try:
        # Exclusive output reservation happens before any read or external submission.
        with Path(args.output).open('x', encoding='utf-8') as output:
            content = b''
            if args.scan:
                path = Path(args.file)
                if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_CONTENT_BYTES:
                    raise ValueError('INVALID_SCAN_FILE')
                with path.open('rb') as source:
                    content = source.read(MAX_CONTENT_BYTES + 1)
            report = scan_bytes(content, args.sha256, args.port, enabled=args.scan)
            output.write(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'verdict': report['verdict'], 'code': report['code'], 'release_allowed': False}))
        # Even a valid scan observation is not a completed attachment release gate.
        return 2
    except Exception:
        print(json.dumps({'verdict': 'UNKNOWN', 'code': 'SCAN_INPUT_OR_OUTPUT_INVALID', 'release_allowed': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

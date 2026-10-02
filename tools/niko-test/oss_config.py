"""Read-only OSS private-file validation. Never source dotenv, write config, or contact OSS."""
import argparse
import json
import os
import re
import stat
import sys
from pathlib import Path

MAX_BYTES = 65536
ENDPOINT = 'https://oss-cn-beijing.aliyuncs.com'
REQUIRED = frozenset({
    'OBJECT_STORAGE_ENABLED', 'OBJECT_STORAGE_PROVIDER', 'OBJECT_STORAGE_ENDPOINT',
    'OBJECT_STORAGE_PUBLIC_ENDPOINT', 'OBJECT_STORAGE_REGION', 'OBJECT_STORAGE_BUCKET',
    'OBJECT_STORAGE_ACCESS_KEY', 'OBJECT_STORAGE_SECRET_KEY', 'OBJECT_STORAGE_PRESIGN_EXPIRES',
    'MOP_OBJECT_STORAGE_PREFIX',
})
ALLOWED = REQUIRED | {'OBJECT_STORAGE_SECURITY_TOKEN'}
PREFIX = re.compile(r'mop-niko-test/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/')


class InvalidConfiguration(ValueError):
    def __init__(self):
        super().__init__('Private OSS configuration rejected; values omitted')


def _reject():
    raise InvalidConfiguration()


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            _reject()
        result[key] = value
    return result


def parse_bytes(raw):
    """Strict JSON object or literal KEY=value lines; no shell/interpolation semantics."""
    if len(raw) > MAX_BYTES:
        _reject()
    try:
        text = raw.decode('utf-8-sig')
        if text.lstrip().startswith('{'):
            result = json.loads(text, object_pairs_hook=_pairs)
        else:
            pairs = []
            for line in text.splitlines():
                line = line.strip()
                if not line or line.startswith('#'):
                    continue
                if '=' not in line:
                    _reject()
                key, value = line.split('=', 1)
                key, value = key.strip(), value.strip()
                if not re.fullmatch(r'[A-Z][A-Z0-9_]*', key):
                    _reject()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
                    value = value[1:-1]
                pairs.append((key, value))
            result = _pairs(pairs)
        if not isinstance(result, dict) or set(result) - ALLOWED:
            _reject()
        if any(not isinstance(v, str) for v in result.values()):
            _reject()
        # Literal file format deliberately refuses shell expressions, including in quoted values.
        if any(any(c in v for c in ('$','`','\\','\x00')) for v in result.values()):
            _reject()
        return result
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise InvalidConfiguration() from None


def validate(values, expected_bucket, expected_prefix):
    """Require caller's explicit stable scope. No default bucket, secret, expiry or prefix."""
    if not isinstance(values, dict) or set(values) - ALLOWED or REQUIRED - set(values):
        _reject()
    if any(not isinstance(v, str) for v in values.values()):
        _reject()
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{1,61}[a-z0-9]', expected_bucket or ''):
        _reject()
    if not PREFIX.fullmatch(expected_prefix or ''):
        _reject()
    exact = {
        'OBJECT_STORAGE_PROVIDER': 'oss', 'OBJECT_STORAGE_ENDPOINT': ENDPOINT,
        'OBJECT_STORAGE_PUBLIC_ENDPOINT': ENDPOINT, 'OBJECT_STORAGE_REGION': 'cn-beijing',
        'OBJECT_STORAGE_BUCKET': expected_bucket, 'MOP_OBJECT_STORAGE_PREFIX': expected_prefix,
    }
    if values['OBJECT_STORAGE_ENABLED'].lower() != 'true' or any(values[k] != v for k,v in exact.items()):
        _reject()
    for key in ('OBJECT_STORAGE_ACCESS_KEY','OBJECT_STORAGE_SECRET_KEY','OBJECT_STORAGE_SECURITY_TOKEN'):
        if key in values:
            value = values[key]
            if not value or any(c.isspace() or ord(c) < 32 or 127 <= ord(c) <= 159 for c in value):
                _reject()
    expiry = values['OBJECT_STORAGE_PRESIGN_EXPIRES']
    if not re.fullmatch(r'[0-9]{1,4}', expiry) or not 1 <= int(expiry) <= 3600:
        _reject()
    # Return an independent mapping only to the explicit in-process caller; CLI never prints it.
    return dict(values)


def read_private_file(path, expected_bucket, expected_prefix):
    """Bounded, regular, owner-only file read; reject symlink leaf and non-private mode."""
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            metadata = os.fstat(stream.fileno())
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077 or metadata.st_uid != os.getuid():
                _reject()
            if metadata.st_size > MAX_BYTES:
                _reject()
            values = parse_bytes(stream.read(MAX_BYTES + 1))
        return validate(values, expected_bucket, expected_prefix)
    except (OSError, ValueError, TypeError):
        raise InvalidConfiguration() from None


class SafeParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default error may echo unknown arguments containing pasted credentials.
        raise InvalidConfiguration()


def main(argv=None):
    try:
        parser = SafeParser(description=__doc__, allow_abbrev=False)
        parser.add_argument('--file', required=True, type=Path)
        parser.add_argument('--expected-bucket', required=True)
        parser.add_argument('--expected-prefix', required=True)
        args = parser.parse_args(argv)
        read_private_file(args.file, args.expected_bucket, args.expected_prefix)
    except InvalidConfiguration:
        print('{"status":"CONFIG_REJECTED","values_omitted":true}', file=sys.stderr)
        return 2
    print('{"status":"CONFIG_VALID_NOT_ACTIVE_NOT_VERIFIED","network_requests":0,"writes":0,"values_omitted":true}')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

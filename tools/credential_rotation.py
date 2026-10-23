"""Durable rotation coordinator with injected authorized backend; no backend is installed by default."""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets

from production_preflight import parse_json

STATES = {'PREPARED', 'STAGED', 'ACTIVATING', 'ACTIVE', 'RETIRING', 'COMPLETE'}
REQUEST_FIELDS = {'rotation_id', 'scope', 'active_version', 'candidate_version', 'expected_generation', 'authorization_ref'}


def require(condition):
    if not condition:
        raise ValueError('ROTATION_INPUT_OR_JOURNAL_INVALID')


def label(value):
    return isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,95}', value) is not None


def validate_request(request):
    require(isinstance(request, dict) and set(request) == REQUEST_FIELDS)
    require(all(label(request[key]) for key in REQUEST_FIELDS - {'expected_generation'}))
    require(type(request['expected_generation']) is int and 1 <= request['expected_generation'] < 9007199254740991)
    require(request['active_version'] != request['candidate_version'])


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


class RotationCoordinator:
    """Append intent before mutation, then reconcile observed versions; never retry unknown writes."""
    def __init__(self, private_journal_root):
        self.root = Path(private_journal_root).resolve()

    def _load(self, folder):
        records = []
        previous_hash = '0' * 64
        for index, path in enumerate(sorted(folder.glob('*.json')), 1):
            require(path.name == f'{index:010d}.json' and not path.is_symlink() and path.stat().st_size <= 16384)
            event = parse_json(path.read_bytes())
            require(isinstance(event, dict) and set(event) == {'revision', 'state', 'request', 'recorded_at', 'previous_sha256', 'retirement_approval_ref'})
            require(type(event['revision']) is int and event['revision'] == index and event['state'] in STATES)
            validate_request(event['request'])
            require(event['previous_sha256'] == previous_hash)
            if not records or records[-1]['request']['rotation_id'] != event['request']['rotation_id']:
                require(event['state'] == 'PREPARED' and event['retirement_approval_ref'] is None)
                require(not records or records[-1]['state'] == 'COMPLETE')
                require(not any(old['request']['rotation_id'] == event['request']['rotation_id'] for old in records))
            else:
                previous = records[-1]
                transitions = {'PREPARED': 'STAGED', 'STAGED': 'ACTIVATING', 'ACTIVATING': 'ACTIVE',
                               'ACTIVE': 'RETIRING', 'RETIRING': 'COMPLETE'}
                require(event['request'] == previous['request'] and transitions.get(previous['state']) == event['state'])
                if event['state'] == 'RETIRING':
                    require(label(event['retirement_approval_ref']))
                else:
                    require(event['retirement_approval_ref'] == previous['retirement_approval_ref'])
            records.append(event)
            previous_hash = hashlib.sha256(canonical(event)).hexdigest()
            require(index <= 10000)
        return records

    def _append(self, folder, records, request, state, retirement_approval_ref=None):
        event = {'revision': len(records) + 1, 'state': state, 'request': request,
                 'recorded_at': datetime.now(timezone.utc).isoformat(),
                 'previous_sha256': hashlib.sha256(canonical(records[-1])).hexdigest() if records else '0' * 64,
                 'retirement_approval_ref': (retirement_approval_ref if state == 'RETIRING' else
                                             records[-1]['retirement_approval_ref'] if state == 'COMPLETE' else None)}
        # An incomplete append after a crash remains visible and blocks resumption; it is never repaired silently.
        descriptor = os.open(folder / f'{len(records) + 1:010d}.json', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'wb') as target:
            target.write(canonical(event))
            target.flush()
            os.fsync(target.fileno())
        records.append(event)

    @staticmethod
    def _observation_matches(value, request):
        return (isinstance(value, dict)
                and set(value) == {'active_version', 'generation', 'activation_operation', 'candidate_usable', 'previous_revoked'}
                and value['active_version'] == request['candidate_version']
                and type(value['generation']) is int and value['generation'] == request['expected_generation'] + 1
                and value['activation_operation'] == request['rotation_id'] and value['candidate_usable'] is True
                and type(value['previous_revoked']) is bool)

    def advance(self, request, backend=None, retirement_approval_ref=None):
        """Backend must authorize, validate_candidate, activate_compare_and_set, observe and retire_compare_and_set.

        Backend methods receive references only, must enforce scope/authorization and must not return credentials.
        Retire requires a separate explicit approval and another compare-and-set against the activated version.
        """
        validate_request(request)
        request = dict(request)
        require(retirement_approval_ref is None or label(retirement_approval_ref))
        scope_hash = hashlib.sha256(request['scope'].encode()).hexdigest()
        self.root.mkdir(parents=True, exist_ok=True)
        folder = self.root / scope_hash
        require(not folder.is_symlink() and folder.resolve().is_relative_to(self.root))
        folder.mkdir(mode=0o700, exist_ok=True)
        lock = folder / 'rotation.lock'
        try:
            descriptor = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        except FileExistsError:
            return {'state': 'BLOCKED', 'code': 'ROTATION_LOCKED', 'production_ready': False}
        lock_owner = secrets.token_hex(16)
        with os.fdopen(descriptor, 'w', encoding='ascii') as owner_file:
            owner_file.write(lock_owner)
        records = []

        def result(code):
            return {'state': records[-1]['state'] if records else 'BLOCKED', 'code': code,
                    'journal_revision': len(records), 'production_ready': False}

        try:
            records = self._load(folder)
            if records:
                current = records[-1]
                if current['request']['rotation_id'] == request['rotation_id']:
                    require(current['request'] == request)
                else:
                    if current['state'] != 'COMPLETE':
                        return result('ANOTHER_ROTATION_UNRESOLVED')
                    require(not any(event['request']['rotation_id'] == request['rotation_id'] for event in records))
                    self._append(folder, records, request, 'PREPARED')
            else:
                self._append(folder, records, request, 'PREPARED')
            if records[-1]['state'] == 'COMPLETE':
                return result('ROTATION_COMPLETE')
            if backend is None:
                return result('AUTHORIZED_BACKEND_NOT_CONFIGURED')
            try:
                if backend.authorize(dict(request)) is not True:
                    return result('ROTATION_NOT_AUTHORIZED')
                if records[-1]['state'] == 'PREPARED':
                    if backend.validate_candidate(dict(request)) is not True:
                        return result('CANDIDATE_NOT_VERIFIED')
                    self._append(folder, records, request, 'STAGED')
                if records[-1]['state'] == 'STAGED':
                    self._append(folder, records, request, 'ACTIVATING')
                    try:
                        backend.activate_compare_and_set(dict(request))
                    except Exception:
                        pass  # A timeout might have committed. Only observation can resolve it.
                observation = backend.observe(dict(request))
                if not self._observation_matches(observation, request):
                    return result('ACTIVATION_OR_GENERATION_UNCONFIRMED')
                if records[-1]['state'] == 'ACTIVATING':
                    self._append(folder, records, request, 'ACTIVE')
                if records[-1]['state'] == 'ACTIVE':
                    if observation['previous_revoked']:
                        return result('PREVIOUS_VERSION_REVOKED_OUTSIDE_THIS_TRANSACTION')
                    if retirement_approval_ref is None:
                        return result('RETIREMENT_REQUIRES_SEPARATE_AUTHORIZATION')
                    if backend.authorize_retirement(dict(request), retirement_approval_ref) is not True:
                        return result('RETIREMENT_NOT_AUTHORIZED')
                    # Persist before revocation. The backend must CAS against the current generation and scope.
                    self._append(folder, records, request, 'RETIRING', retirement_approval_ref)
                    try:
                        backend.retire_compare_and_set(dict(request), retirement_approval_ref)
                    except Exception:
                        pass
                observation = backend.observe(dict(request))
                if not self._observation_matches(observation, request) or not observation['previous_revoked']:
                    return result('RETIREMENT_UNCONFIRMED_NO_AUTOMATIC_RETRY')
                self._append(folder, records, request, 'COMPLETE')
                return result('ROTATION_COMPLETE')
            except Exception:
                return result('BACKEND_UNAVAILABLE_OR_OUTCOME_UNKNOWN')
        finally:
            # This file was created exclusively by this invocation; never remove another scope's lock.
            if lock.read_text(encoding='ascii') == lock_owner:
                lock.unlink()

"""Verify a release-bound evidence bundle and build all 128 contract rows without granting acceptance."""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

import contract_progress
from production_preflight import parse_json

LAYERS = ('UNIT', 'COMPONENT', 'HTTP', 'BROWSER', 'FORMAL_TC')
OUTCOMES = {'PASS', 'FAIL', 'BLOCKED', 'NOT_RUN'}
MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_BUNDLE_BYTES = 2 * 1024 * 1024


def require(condition, code):
    if not condition:
        raise ValueError(code)


def hex_id(value, size):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{%d}' % size, value) is not None


def evidence_digest(root, reference):
    """Hash only public evidence files; no path can escape into private configuration, even via symlinks."""
    require(isinstance(reference, dict) and set(reference) == {'path', 'sha256'}, 'INVALID_EVIDENCE_REFERENCE')
    value = reference['path']
    require(isinstance(value, str) and value.startswith('docs/evidence/') and '\\' not in value
            and not any(ord(char) < 32 for char in value), 'INVALID_EVIDENCE_PATH')
    path = PurePosixPath(value)
    require(value == path.as_posix() and all(part not in ('.', '..') for part in path.parts)
            and ':' not in value and '%' not in value, 'INVALID_EVIDENCE_PATH')
    target = root.joinpath(*path.parts)
    require(target.resolve().is_relative_to((root / 'docs/evidence').resolve()), 'EVIDENCE_OUTSIDE_PUBLIC_SCOPE')
    current = root
    for part in path.parts:
        current = current / part
        require(not current.is_symlink() and not getattr(current, 'is_junction', lambda: False)(), 'EVIDENCE_LINK_NOT_ALLOWED')
        require(current.resolve() == current.absolute(), 'EVIDENCE_LINK_NOT_ALLOWED')
    require(target.is_file() and 0 < target.stat().st_size <= MAX_FILE_BYTES, 'EVIDENCE_MISSING_OR_TOO_LARGE')
    require(hex_id(reference['sha256'], 64), 'INVALID_EVIDENCE_DIGEST')
    digest = hashlib.sha256()
    total = 0
    with target.open('rb') as source:
        for block in iter(lambda: source.read(65536), b''):
            total += len(block)
            require(total <= MAX_FILE_BYTES, 'EVIDENCE_TOO_LARGE')
            digest.update(block)
    require(total > 0 and digest.hexdigest() == reference['sha256'], 'EVIDENCE_DIGEST_MISMATCH')
    return {'path': value, 'sha256': digest.hexdigest(), 'bytes': total}


def build_matrix(index, bundle, root):
    root = Path(root).resolve()
    fields = {'format', 'source_commit', 'artifact_sha256', 'environment', 'records'}
    require(isinstance(bundle, dict) and set(bundle) == fields and type(bundle['format']) is int
            and bundle['format'] == 1, 'INVALID_BUNDLE')
    require(hex_id(bundle['source_commit'], 40), 'INVALID_RELEASE_COMMIT')
    require(bundle['artifact_sha256'] is None or hex_id(bundle['artifact_sha256'], 64), 'INVALID_RELEASE_ARTIFACT')
    require(isinstance(bundle['environment'], str) and re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,63}', bundle['environment']),
            'INVALID_ENVIRONMENT_LABEL')
    records = bundle['records']
    require(isinstance(records, list) and len(records) <= 4096, 'INVALID_RECORDS')
    by_id = {row['id']: row for row in index['requirements']}
    require(len(by_id) == index['requirement_count'] and len(by_id) == len(index['requirements']), 'INVALID_CONTRACT_INDEX')
    grouped = {key: {layer: [] for layer in LAYERS} for key in by_id}
    seen, proofs = set(), {}
    for record in records:
        require(isinstance(record, dict) and set(record) == {'run_id', 'case_id', 'layer', 'outcome', 'source_commit',
                'artifact_sha256', 'environment', 'executed_at', 'evidence'}, 'INVALID_RECORD')
        require(isinstance(record['run_id'], str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,95}', record['run_id']), 'INVALID_RUN_ID')
        require(isinstance(record['case_id'], str) and record['case_id'].startswith('TC-')
                and record['case_id'][3:] in by_id, 'UNKNOWN_CONTRACT_CASE')
        require(isinstance(record['layer'], str) and isinstance(record['outcome'], str)
                and record['layer'] in LAYERS and record['outcome'] in OUTCOMES, 'INVALID_LAYER_OR_OUTCOME')
        require(all(record[key] == bundle[key] for key in ('source_commit', 'artifact_sha256', 'environment')),
                'MIXED_RELEASE_OR_ENVIRONMENT')
        require(record['layer'] == 'UNIT' or bundle['artifact_sha256'] is not None, 'REAL_RUN_REQUIRES_ARTIFACT_IDENTITY')
        try:
            at = datetime.fromisoformat(record['executed_at'].replace('Z', '+00:00'))
            require(at.tzinfo is not None and at.utcoffset() is not None, 'EXECUTION_TIME_REQUIRES_ZONE')
        except (ValueError, AttributeError, TypeError):
            raise ValueError('INVALID_EXECUTION_TIME') from None
        identity = (record['run_id'], record['case_id'], record['layer'])
        require(identity not in seen, 'DUPLICATE_CASE_EXECUTION')
        seen.add(identity)
        references = record['evidence']
        require(isinstance(references, list) and len(references) <= 32
                and (references or record['outcome'] in ('BLOCKED', 'NOT_RUN')), 'EXECUTED_CASE_REQUIRES_EVIDENCE')
        paths = set()
        for reference in references:
            proof = evidence_digest(root, reference)
            require(proof['path'] not in paths, 'DUPLICATE_EVIDENCE_REFERENCE')
            paths.add(proof['path'])
            if proof['path'] in proofs:
                require(proofs[proof['path']] == proof, 'EVIDENCE_CHANGED_DURING_READ')
            proofs[proof['path']] = proof
        grouped[record['case_id'][3:]][record['layer']].append({
            'run_id': record['run_id'], 'declared_outcome': record['outcome'],
            'executed_at': record['executed_at'], 'evidence_paths': sorted(paths)})
    rows = []
    for row in index['requirements']:
        layers = grouped[row['id']]
        rows.append({'id': row['id'], 'case_id': 'TC-' + row['id'], 'title': row['title'],
                     'contract_scope': row['contract_scope'], 'tracker_declared_status': row['declared_acceptance_status'],
                     'current_work_scope': 'CUSTOMER_ROUTE_PENDING' if row['id'].startswith('M7-') else 'IN_CURRENT_DEVELOPMENT_SCOPE',
                     'layers': layers, 'formal_evidence_present': bool(layers['FORMAL_TC']),
                     'formal_acceptance': 'UNVERIFIED', 'customer_signoff_verified': False})
    return {'format': 1, 'scope': 'EVIDENCE_INTEGRITY_AND_DECLARATIONS_ONLY', 'source_commit': bundle['source_commit'],
            'artifact_sha256': bundle['artifact_sha256'], 'environment': bundle['environment'],
            'contract_source_sha256': index.get('source_sha256', {}), 'requirement_count': len(rows),
            'records_verified': len(records), 'evidence_files_verified': len(proofs),
            'declared_layer_outcomes': {layer: dict(sorted(Counter(record['outcome'] for record in records
                                      if record['layer'] == layer).items())) for layer in LAYERS},
            'completion_verified': False, 'production_ready': False, 'tests_executed_by_this_tool': 0,
            'evidence_authenticity_verified': False, 'formal_rows_without_evidence': sum(not row['formal_evidence_present'] for row in rows),
            'evidence': sorted(proofs.values(), key=lambda proof: proof['path']), 'requirements': rows}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bundle', required=True, help='Credential-free run manifest')
    parser.add_argument('--output', required=True, help='New matrix file; never overwrites evidence or tracker')
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    try:
        path = Path(args.bundle)
        require(path.stat().st_size <= MAX_BUNDLE_BYTES, 'BUNDLE_TOO_LARGE')
        bundle = parse_json(path.read_bytes())
        # Rebuild from the actual contract and tracker; never trust a stale cached index.
        index = parse_json(contract_progress.generate(root))
        report = build_matrix(index, bundle, root)
        report['generator_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
        with Path(args.output).open('x', encoding='utf-8') as output:
            output.write(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        print(json.dumps({'integrity_verified': True, 'requirements': report['requirement_count'],
                          'records': report['records_verified'], 'completion_verified': False}))
        return 0
    except Exception:
        # File/JSON errors can contain credentials or private paths; do not forward their text.
        print(json.dumps({'integrity_verified': False, 'code': 'ACCEPTANCE_BUNDLE_INVALID', 'completion_verified': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

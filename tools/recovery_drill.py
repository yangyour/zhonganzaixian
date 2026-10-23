"""Read-only recovery evidence verifier. Does not restore databases or infer RPO/RTO approval."""
import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re

from production_preflight import parse_json


def require(condition):
    if not condition:
        raise ValueError('INVALID_RECOVERY_EVIDENCE')


def sha(value):
    return isinstance(value, str) and re.fullmatch(r'[a-f0-9]{64}', value) is not None


def instant(value):
    require(isinstance(value, str))
    result = datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None and result.utcoffset() is not None)
    return result


def archive_digest(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size > 0)
    digest = hashlib.sha256()
    total = 0
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
            total += len(block)
    require(total > 0)
    return digest.hexdigest()


def validate_snapshot(snapshot):
    fields = {'namespace', 'source_commit', 'artifact_sha256', 'schema_sha256', 'observed_at',
              'recovered_through_at', 'dependencies_ready', 'tables', 'objects_sha256'}
    require(isinstance(snapshot, dict) and set(snapshot) == fields)
    require(isinstance(snapshot['namespace'], str) and re.fullmatch(r'mop_drill_[a-z0-9_]{8,64}', snapshot['namespace']))
    require(isinstance(snapshot['source_commit'], str) and re.fullmatch(r'[a-f0-9]{40}', snapshot['source_commit']))
    require(sha(snapshot['artifact_sha256']) and sha(snapshot['schema_sha256']))
    require(snapshot['objects_sha256'] is None or sha(snapshot['objects_sha256']))
    require(type(snapshot['dependencies_ready']) is bool)
    observed, watermark = instant(snapshot['observed_at']), instant(snapshot['recovered_through_at'])
    require(watermark <= observed)
    require(isinstance(snapshot['tables'], list) and 0 < len(snapshot['tables']) <= 4096)
    tables = {}
    for table in snapshot['tables']:
        require(isinstance(table, dict) and set(table) == {'name', 'rows', 'sha256'})
        require(isinstance(table['name'], str) and re.fullmatch(r'[a-z_][a-z0-9_]{0,62}\.[a-z_][a-z0-9_]{0,62}', table['name']))
        require(type(table['rows']) is int and 0 <= table['rows'] <= 9007199254740991 and sha(table['sha256']))
        require(table['name'] not in tables)
        tables[table['name']] = (table['rows'], table['sha256'])
    return observed, watermark, tables


def verify(manifest, actual_archive_sha256):
    fields = {'format', 'environment', 'isolation_authorization_ref', 'backup_sha256', 'disruption_at',
              'restore_started_at', 'ready_at', 'rpo_target_seconds', 'rto_target_seconds', 'source', 'restored'}
    require(isinstance(manifest, dict) and set(manifest) == fields and type(manifest['format']) is int and manifest['format'] == 1)
    require(isinstance(manifest['environment'], str) and re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,63}', manifest['environment']))
    require(isinstance(manifest['isolation_authorization_ref'], str)
            and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._/-]{0,127}', manifest['isolation_authorization_ref']))
    require(sha(manifest['backup_sha256']) and sha(actual_archive_sha256))
    for field in ('rpo_target_seconds', 'rto_target_seconds'):
        value = manifest[field]
        require(value is None or type(value) is int and 0 <= value <= 31536000)
    source, restored = manifest['source'], manifest['restored']
    before_time, before_watermark, before_tables = validate_snapshot(source)
    after_time, after_watermark, after_tables = validate_snapshot(restored)
    disruption, started = instant(manifest['disruption_at']), instant(manifest['restore_started_at'])
    ready = None if manifest['ready_at'] is None else instant(manifest['ready_at'])
    require(before_time <= disruption <= started <= after_time)
    require(ready is None or started <= ready <= after_time)
    require(after_watermark <= disruption)
    checks = []

    def add(name, passed):
        checks.append({'id': name, 'status': 'PASS' if passed else 'FAIL'})

    add('backup_archive_digest', manifest['backup_sha256'] == actual_archive_sha256)
    add('same_isolated_namespace', source['namespace'] == restored['namespace'])
    add('same_application_release', all(source[key] == restored[key] for key in ('source_commit', 'artifact_sha256')))
    add('same_schema_history', source['schema_sha256'] == restored['schema_sha256'])
    add('same_table_set', set(before_tables) == set(after_tables))
    add('same_table_rows_and_content', before_tables == after_tables)
    add('recovery_watermark', before_watermark == after_watermark)
    add('dependencies_ready', source['dependencies_ready'] and restored['dependencies_ready'] and ready is not None)
    if source['objects_sha256'] is None or restored['objects_sha256'] is None:
        checks.append({'id': 'object_archive_manifest', 'status': 'NOT_RUN'})
    else:
        add('object_archive_manifest', source['objects_sha256'] == restored['objects_sha256'])
    rpo = (disruption - after_watermark).total_seconds()
    rto = (ready - disruption).total_seconds() if ready is not None and restored['dependencies_ready'] else None
    for name, value in [('rpo', rpo), ('rto', rto)]:
        target = manifest[name + '_target_seconds']
        if target is None:
            checks.append({'id': name + '_target', 'status': 'NOT_ASSESSED'})
        else:
            add(name + '_target', value is not None and value <= target)
    result = ('FAIL' if any(item['status'] == 'FAIL' for item in checks) else
              'INCOMPLETE' if any(item['status'] != 'PASS' for item in checks) else 'OBSERVATIONS_MATCH')
    return {'format': 1, 'result': result, 'scope': 'READ_ONLY_ARCHIVE_AND_DECLARED_RECOVERY_OBSERVATIONS',
            'source_commit': source['source_commit'], 'artifact_sha256': source['artifact_sha256'],
            'environment': manifest['environment'], 'backup_sha256': actual_archive_sha256,
            'rpo_seconds_from_declared_watermark': rpo, 'rto_seconds_from_declared_outage': rto,
            'checks': checks, 'restore_executed_by_this_tool': False, 'observations_authenticity_verified': False,
            'isolation_authorization_verified': False, 'customer_acceptance_verified': False, 'production_ready': False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--backup', required=True, help='Existing private backup archive; only streaming SHA256 is read')
    parser.add_argument('--output', required=True, help='New redacted report')
    args = parser.parse_args(argv)
    try:
        path = Path(args.manifest)
        require(path.stat().st_size <= 2 * 1024 * 1024)
        manifest = parse_json(path.read_bytes())
        report = verify(manifest, archive_digest(args.backup))
        with Path(args.output).open('x', encoding='utf-8') as output:
            output.write(json.dumps(report, indent=2) + '\n')
        print(json.dumps({'result': report['result'], 'restore_executed': False, 'customer_acceptance_verified': False}))
        return 0 if report['result'] == 'OBSERVATIONS_MATCH' else 2
    except Exception:
        print(json.dumps({'result': 'ERROR', 'code': 'RECOVERY_EVIDENCE_INVALID', 'restore_executed': False}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())

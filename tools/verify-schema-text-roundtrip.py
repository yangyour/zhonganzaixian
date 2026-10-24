"""Verify schema text with the designated existing PG; optionally create one owned empty clone.

No migration, old-database repair, service change, role change, business-row copy,
notification or database deletion. The migration lock is checked before private
configuration is read. Every created clone and all evidence are retained.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
MIGRATION_OWNER = ROOT / '.local/schema-text-migration-owner.json'
MIGRATION_LOCK = Path('E:/work/wxw9/zhonganzaixian/mop-platform/.local/parallel-launch-20261023/migration.lock')
RETAINED = ROOT / 'docs/evidence/local-astra-ut-20261023/schema-chain/summary.json'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    result = importlib.util.module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def migration_lock():
    saved = json.loads(MIGRATION_OWNER.read_text(encoding='utf-8'))
    stat = MIGRATION_LOCK.stat()
    if MIGRATION_LOCK.is_symlink() or [stat.st_dev, stat.st_ino] != saved['identity'] \
            or json.loads(MIGRATION_LOCK.read_text(encoding='utf-8')) != saved['document'] \
            or saved['document'].get('session') != 'remaining-gaps-20261024':
        raise RuntimeError('SCHEMA_TEXT_MIGRATION_LOCK_NOT_OWNED')


def resource_hashes():
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted((ROOT / 'server/persistence/src/main/resources/db/migration').glob('V*.sql'))}


def catalogue_checks(connection):
    result = {}
    with connection.cursor() as cursor:
        for table in ('mop_raw_monitoring_event', 'mop_monitoring_quarantine'):
            cursor.execute("SELECT encode(sha256(convert_to(string_agg(conname||'='||pg_get_constraintdef(oid),"
                           "chr(10) ORDER BY conname),'UTF8')),'hex') FROM pg_constraint "
                           "WHERE conrelid=%s::regclass AND contype='c'", ('public.' + table,))
            result[table] = cursor.fetchone()[0]
    return result


def run(output, create):
    migration_lock()
    output = output.resolve()
    if not output.is_relative_to((ROOT / '.local').resolve()):
        raise ValueError('OUTPUT_MUST_BE_CHECKOUT_LOCAL')
    output.mkdir(parents=True, exist_ok=False)
    chain = module('schema_text_chain', 'verify-candidate-schema-chain.py')
    retained = module('schema_text_retained', 'verify-notification-routing-retained.py')
    text = module('schema_text_utility', 'postgres_schema_text.py')
    fingerprints = module('schema_text_fingerprints', 'verify-report-capture-isolated.py')

    class Locks(retained.EnvironmentLock):
        def check(self):
            super().check()
            migration_lock()

    run_id = uuid.uuid4().hex
    locks = Locks('remaining-gaps-20261024', run_id, 'mop_test')
    locks.document['operation'] = 'schema-text-roundtrip'
    report = {'environment': 'lan-test-20261008', 'started_at': chain.now(), 'status': 'NOT_RUN',
              'schema_migrations': 0, 'database_creation_requested': create, 'old_database_repairs': 0,
              'business_rows_copied': 0, 'roles_changed': 0, 'services_changed': 0,
              'external_messages': 0, 'database_deletions': 0, 'formal_contract_acceptance': 'NOT_RUN'}
    hashes = resource_hashes()
    pg = None
    stage = None
    try:
        locks.acquire()
        pg = chain.DesignatedPostgres(locks)
        pg.open()
        with pg.connection('mop_test', readonly=True) as source:
            history, capabilities = chain.metadata(source, 55)
            protected = fingerprints.protected_fingerprints(source)
            actual = text.catalog_literal_fingerprints(source)
            report['active_checks'] = catalogue_checks(source)
            with source.cursor() as cursor:
                cursor.execute('SHOW server_version')
                report['postgresql_version'] = cursor.fetchone()[0]
        dump = pg.dump('mop_test', None)
        prepared = text.reuse_empty_public_schema(dump)
        report['dump_sha256'] = hashlib.sha256(dump.encode('utf-8')).hexdigest()
        report['prepared_sha256'] = hashlib.sha256(prepared.encode('utf-8')).hexdigest()
        report['dump_literal_sha256'] = text.literal_bytes_sha256(dump)
        report['prepared_literal_sha256'] = text.literal_bytes_sha256(prepared)
        assert report['dump_literal_sha256'] == report['prepared_literal_sha256']
        # Reproduce only the old transformation in local memory; never execute it.
        damaged = '\n'.join(dump.splitlines()) + '\n'
        report['historical_splitlines_damage_detected'] = text.literal_bytes_sha256(dump) != text.literal_bytes_sha256(damaged)
        assert report['historical_splitlines_damage_detected']
        document = json.loads(RETAINED.read_text(encoding='utf-8'))
        stages = [item for item in document['stages'] if item['stage'] == 'M02']
        assert len(stages) == 1
        old = stages[0]
        assert re.fullmatch(r'mop_monitoring_test_[a-f0-9]{12}', old['database'])
        with pg.connection(old['database'], readonly=True) as previous:
            pg.assert_owned(previous, old)
            old_values = text.catalog_literal_fingerprints(previous)
            report['retained_checks'] = catalogue_checks(previous)
        differing = sorted(key for key in actual.keys() | old_values.keys() if actual.get(key) != old_values.get(key))
        report['retained_literal_or_function_differences'] = differing
        assert 'constraint:mop_raw_monitoring_event:mop_raw_monitoring_event_source_event_id_check2' in differing
        if create:
            stage = {'prefix': 'mop_monitoring_test_', 'database': 'mop_monitoring_test_' + uuid.uuid4().hex[:12],
                     'owner_marker': 'MOP_SCHEMA_TEXT_ROUNDTRIP:' + run_id, 'created': False,
                     'database_owner': 'mop_test_migrator', 'retention_policy': 'RETAIN_DATABASES_NO_DROP'}
            report['clone'] = stage
            chain.create_clone(pg, stage, None, 55)
            with pg.connection(stage['database'], readonly=True) as target:
                pg.assert_owned(target, stage)
                assert text.catalog_literal_fingerprints(target) == actual
                assert chain.metadata(target, 55) == (history, capabilities)
                report['clone_checks'] = catalogue_checks(target)
            stage['retained'] = True
            stage['database_removed'] = False
        with pg.connection('mop_test', readonly=True) as source:
            assert chain.metadata(source, 55) == (history, capabilities)
            after = fingerprints.protected_fingerprints(source)
            assert protected == after
        assert hashes == resource_hashes()
        report.update(status='PASSED_OWNED_EMPTY_CLONE_LITERAL_ROUNDTRIP' if create else 'PASSED_READ_ONLY_DIAGNOSTIC',
                      frozen_migration_resources_unchanged=True, active_history_unchanged=True,
                      actual_tenant_protected_rows_unchanged=True, old_databases_read_only=True,
                      runtime_schema_guard_approval='SEPARATE_NOT_CLAIMED_BY_LITERAL_PROOF')
    except Exception as failure:
        report.update(status='FAILED', failure_type=type(failure).__name__)
        if isinstance(failure, (chain.Refused, text.SchemaTextError)):
            report['reason'] = str(failure)
    finally:
        if pg is not None:
            pg.close()
            report['own_connections_closed'] = True
        if locks.held:
            locks.release()
            report['environment_lock_released'] = True
        report['finished_at'] = chain.now()
        chain.write_json(output / 'summary.json', report)
    print(json.dumps({key: report.get(key) for key in ('status', 'postgresql_version', 'reason', 'failure_type', 'clone_checks')}))
    return 0 if report['status'].startswith('PASSED_') else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--create-isolated-clone', action='store_true')
    args = parser.parse_args()
    raise SystemExit(run(args.output, args.create_isolated_clone))

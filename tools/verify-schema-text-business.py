"""Verify raw storage in this run's newly retained, schema-only PG55 clone.

Active mop_test and the older damaged clone are read-only. No DDL, migration,
repair, old actor restoration, service change, external dispatch or deletion.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET

from importlib.util import spec_from_file_location, module_from_spec
import sys

ROOT = Path(__file__).resolve().parents[1]


def module(name, file):
    spec = spec_from_file_location(name, ROOT / 'tools' / file)
    result = module_from_spec(spec)
    sys.modules[name] = result
    spec.loader.exec_module(result)
    return result


def run(source_summary, output, suite):
    clone_tool = module('schema_business_clone', 'verify-schema-text-roundtrip.py')
    clone_tool.migration_lock()
    source_summary = source_summary.resolve(strict=True)
    if not source_summary.is_relative_to(ROOT / '.local'):
        raise ValueError('OWN_CLONE_SUMMARY_REQUIRED')
    summary = json.loads(source_summary.read_text(encoding='utf-8'))
    if summary.get('status') != 'PASSED_OWNED_EMPTY_CLONE_LITERAL_ROUNDTRIP' \
            or summary.get('environment') != 'lan-test-20261008' \
            or summary.get('frozen_migration_resources_unchanged') is not True:
        raise ValueError('OWN_CLONE_NOT_VERIFIED')
    stage = summary['clone']
    assert stage['prefix'] == 'mop_monitoring_test_' and stage['created'] is True
    assert re.fullmatch(r'mop_monitoring_test_[a-f0-9]{12}', stage['database'])
    assert re.fullmatch(r'MOP_SCHEMA_TEXT_ROUNDTRIP:[a-f0-9]{32}', stage['owner_marker'])
    assert stage['literal_and_function_source_preserved'] is True and stage['inherited_versions'] == 55
    assert stage['inherited_history_reexecuted'] is False and stage['business_rows_copied'] == 0
    assert stage['source_literal_catalogue_sha256'] == stage['restored_literal_catalogue_sha256']
    output = output.resolve()
    if not output.is_relative_to(ROOT / '.local'):
        raise ValueError('LOCAL_OUTPUT_REQUIRED')
    output.mkdir(parents=True, exist_ok=False)
    chain = module('schema_business_chain', 'verify-candidate-schema-chain.py')
    retained = module('schema_business_retained', 'verify-notification-routing-retained.py')
    fingerprints = module('schema_business_fingerprints', 'verify-report-capture-isolated.py')
    previous_summary = json.loads(clone_tool.RETAINED.read_text(encoding='utf-8'))
    previous = next(row for row in previous_summary['stages'] if row['stage'] == 'M02')

    class Locks(retained.EnvironmentLock):
        def check(self):
            super().check()
            clone_tool.migration_lock()

    run_id = uuid.uuid4().hex
    locks = Locks('remaining-gaps-20261024', run_id, stage['database'])
    locks.document['operation'] = 'schema-text-raw-business'
    actor, tenant, asset = (str(uuid.uuid4()) for _ in range(3))
    foreign_tenant=str(uuid.uuid4()) if suite=='mapped-problem' else None
    report = {'environment': 'lan-test-20261008', 'database': stage['database'], 'owner_marker': stage['owner_marker'],
              'status': 'NOT_RUN', 'suite': suite, 'started_at': chain.now(), 'schema_migrations': 0, 'ddl_statements': 0,
              'old_databases_read_only': True, 'actual_tenant_written': False, 'external_messages': 0,
              'database_removed': False, 'formal_contract_acceptance': 'NOT_RUN'}
    pg = None
    seeded = False
    exited = False
    process = None
    try:
        locks.acquire()
        pg = chain.DesignatedPostgres(locks)
        pg.open()
        with pg.connection('mop_test', readonly=True) as connection:
            active_metadata = chain.metadata(connection, 55)
            protected = fingerprints.protected_fingerprints(connection)
        with pg.connection(stage['database'], readonly=True) as connection:
            pg.assert_owned(connection, stage)
            before_metadata = chain.metadata(connection, 55)
            assert chain.digest(before_metadata[0]) == stage['inherited_history_sha256']
            with connection.cursor() as cursor:
                cursor.execute('SHOW server_version')
                report['postgresql_version'] = cursor.fetchone()[0]
        with pg.connection(previous['database'], readonly=True) as connection:
            pg.assert_owned(connection, previous)
            previous_checks = clone_tool.catalogue_checks(connection)
        hashes = clone_tool.resource_hashes()
        with pg.connection(stage['database'], readonly=False) as connection:
            pg.assert_owned(connection, stage)
            with connection.transaction(), connection.cursor() as cursor:
                if suite=='mapped-problem':
                    definitions=json.loads((ROOT/'contracts/config-baseline.json').read_text(encoding='utf-8'))['settings']
                    definition=next(item for item in definitions if item['key']=='ticket.default_dedupe_window_sec')
                    assert definition['contract_value_kind']=='configurable_default' and definition['type']=='integer'
                    cursor.execute("INSERT INTO public.mop_configuration(key,registry_json) VALUES(%s,%s::jsonb) ON CONFLICT(key) DO NOTHING",
                                   (definition['key'],json.dumps(definition,ensure_ascii=False)))
                    cursor.execute('SELECT registry_json FROM public.mop_configuration WHERE key=%s',(definition['key'],))
                    assert cursor.fetchone()[0]==definition
                    report['test_contract_definition']=dict(key=definition['key'],source='contracts/config-baseline.json',tenant_rules_copied=False)
                cursor.execute("INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) "
                               "VALUES(%s,'isolated_schema_text_roundtrip',%s,'SYNTHETIC_SCHEMA_TEXT','padmin')",
                               (actor, 'SYNTHETIC_SCHEMA_TEXT_' + actor))
                if foreign_tenant:
                    cursor.execute('INSERT INTO public.mop_tenant(id,name) VALUES(%s,%s)',(foreign_tenant,'SYNTHETIC_SCHEMA_TEXT_'+foreign_tenant))
                    cursor.execute("INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(%s,%s,'padmin')",(foreign_tenant,actor))
                cursor.execute('INSERT INTO public.mop_tenant(id,name) VALUES(%s,%s)',
                               (tenant, 'SYNTHETIC_SCHEMA_TEXT_' + tenant))
                cursor.execute("INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(%s,%s,'padmin')", (tenant, actor))
                cursor.execute("INSERT INTO public.mop_site(tenant_id,id,name) VALUES(%s,'schema-text-test','SYNTHETIC_SCHEMA_TEXT')", (tenant,))
                cursor.execute("INSERT INTO public.mop_asset(id,tenant_id,name,type,management_ip,site_id,source,created_by,updated_by) "
                               "VALUES(%s,%s,'SYNTHETIC_SCHEMA_TEXT','other','192.0.2.87','schema-text-test','schema-text-test',%s,%s)",
                               (asset, tenant, actor, actor))
        seeded = True
        application = 'mop-schema-text-' + run_id
        env = os.environ.copy()
        for key in list(env):
            if key.startswith(('MOP_', 'PG', 'SPRING_DATASOURCE_')) or key in (
                    'MAVEN_ARGS', 'MAVEN_OPTS', 'MAVEN_DEBUG_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
                env.pop(key, None)
        base = f"jdbc:postgresql://127.0.0.1:{pg.settings['port']}/"
        env.update(MOP_SCHEMA_TEXT_PG_TEST='owned-clone', MOP_SCHEMA_TEXT_TENANT=tenant, MOP_SCHEMA_TEXT_ASSET=asset,
                   MOP_SCHEMA_TEXT_DATABASE=stage['database'], MOP_SCHEMA_TEXT_OWNER_MARKER=stage['owner_marker'],
                   MOP_SCHEMA_TEXT_ACTIVE_URL=base + 'mop_test', MOP_SCHEMA_TEXT_PREVIOUS_URL=base + previous['database'],
                   MOP_SCHEMA_TEXT_APPLICATION=application, SPRING_DATASOURCE_URL=base + stage['database'],
                   SPRING_DATASOURCE_PASSWORD=pg.runtime['runtime_password'])
        if suite=='mapped-problem':
            env.update(MOP_MAPPED_PROBLEM_PG_TEST='retained-v55',MOP_MAPPED_PROBLEM_DATABASE=stage['database'],
                       MOP_MAPPED_PROBLEM_OWNER_MARKER=stage['owner_marker'],MOP_MAPPED_PROBLEM_TENANT=tenant,
                       MOP_MAPPED_PROBLEM_FOREIGN_TENANT=foreign_tenant,MOP_MAPPED_PROBLEM_ACTOR=actor,
                       MOP_MAPPED_PROBLEM_ASSET=asset,MOP_MAPPED_PROBLEM_APPLICATION=application)
        module_name='server/api' if suite=='mapped-problem' else 'server/persistence'
        test_class='MappedProblemIntakePostgresTest' if suite=='mapped-problem' else 'PostgresSchemaTextRoundTripIntegrationTest'
        expected_tests=6 if suite=='mapped-problem' else 7
        started = time.time_ns()
        command = ['mvn.cmd' if os.name == 'nt' else 'mvn', '-B', '-pl', module_name, '-am', 'test',
                   '-Dtest='+test_class, '-Dsurefire.failIfNoSpecifiedTests=false']
        with chain.open_evidence_log(output / 'maven.log') as log:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding='utf-8', errors='replace')
            masks = chain.secret_masks(pg)
            def consume():
                for line in process.stdout:
                    for secret in masks:
                        line = line.replace(secret, '[REDACTED]')
                    log.write(line)
                    log.flush()
            reader = threading.Thread(target=consume, daemon=True)
            reader.start()
            report['maven_exit_code'] = process.wait(timeout=600)
            reader.join(timeout=15)
            assert not reader.is_alive()
            exited = True
        files = list((ROOT / module_name / 'target/surefire-reports').glob('TEST-*'+test_class+'.xml'))
        assert len(files) == 1 and files[0].stat().st_mtime_ns >= started - 2_000_000_000
        junit = ET.parse(files[0]).getroot()
        report['junit'] = {key: int(junit.get(key, '0')) for key in ('tests','failures','errors','skipped')}
        assert report['maven_exit_code'] == 0 and report['junit'] == dict(tests=expected_tests, failures=0, errors=0, skipped=0)
        with pg.connection('postgres', readonly=True) as connection, connection.cursor() as cursor:
            cursor.execute('SELECT count(*) FROM pg_stat_activity WHERE application_name=%s', (application,))
            assert cursor.fetchone()[0] == 0
            report['own_runtime_connections_remaining'] = 0
        with pg.connection(stage['database'], readonly=True) as connection:
            pg.assert_owned(connection, stage)
            assert chain.metadata(connection, 55) == before_metadata
        with pg.connection(previous['database'], readonly=True) as connection:
            pg.assert_owned(connection, previous)
            assert clone_tool.catalogue_checks(connection) == previous_checks
        with pg.connection('mop_test', readonly=True) as connection:
            assert chain.metadata(connection, 55) == active_metadata
            assert fingerprints.protected_fingerprints(connection) == protected
        assert clone_tool.resource_hashes() == hashes
        report.update(status='PASSED_REAL_PG_SOURCE_CLONE_AND_NEGATIVE_GUARDS', active_metadata_unchanged=True,
                      actual_tenant_protected_rows_unchanged=True, older_damaged_clone_unchanged=True,
                      frozen_resources_unchanged=True, own_connections_closed=True)
    except Exception as failure:
        report.update(status='FAILED', failure_type=type(failure).__name__)
    finally:
        if pg is not None:
            if seeded and exited:
                locks.check()
                with pg.connection(stage['database'], readonly=False) as connection:
                    pg.assert_owned(connection, stage)
                    with connection.transaction(), connection.cursor() as cursor:
                        cursor.execute("SELECT identity_source,subject FROM public.mop_user WHERE id=%s", (actor,))
                        assert cursor.fetchone() == ('isolated_schema_text_roundtrip', 'SYNTHETIC_SCHEMA_TEXT_' + actor)
                        cursor.execute('UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 '
                                       'WHERE tenant_id=ANY(%s::uuid[]) AND user_id=%s AND active', ([tenant]+([foreign_tenant] if foreign_tenant else []), actor))
                        cursor.execute('UPDATE public.mop_user SET active=false WHERE id=%s', (actor,))
                        report['own_test_actor_deactivated'] = True
            pg.close()
        if locks.held and (process is None or exited):
            locks.release()
            report['environment_lock_released'] = True
        report['finished_at'] = chain.now()
        chain.write_json(output / 'summary.json', report)
    print(json.dumps({key: report.get(key) for key in ('status','junit','failure_type','postgresql_version')}))
    return 0 if report['status'].startswith('PASSED_') else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--clone-summary', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--suite',choices=('raw-storage','mapped-problem'),default='raw-storage')
    args = parser.parse_args()
    raise SystemExit(run(args.clone_summary, args.output,args.suite))

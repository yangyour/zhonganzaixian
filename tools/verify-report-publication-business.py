"""Verify R02 against the retained owned V53 database without migrations or deletion.

Layer A captures real repository sources and preserves their approval gate. Layer B
is an explicitly labelled ISOLATED_TEST initial state for publication/ACL/receipt
transactions. REQUIRED mode must produce the actual two PDFs and three HTML files;
HTML_ONLY is an explicit reduced mode, never an automatic success fallback.
Only the Java checkpoint's exact fresh actors may be deactivated, after confirmed
process exit and zero target connections. Immutable records/databases are retained.
Importing this tool performs no private reads or connection attempts.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
INPUTS = (ROOT / '.local/candidate-schema-chain-attempt-1/summary.json',
          ROOT / 'docs/evidence/local-astra-ut-20261023/schema-chain/summary.json')
V53_SHA256 = 'b9ee9c9724b3bd4156799058ee24876c04e59d413180f4df4db2d5673f4faddb'
FONT_PATH = Path('C:/Windows/Fonts/simhei.ttf')
FONT_SHA256 = '9b1959db3b3abeb7efdaec26edf7dfe871a6039de8d614af7248575207be629e'
CLASS = 'PostgresReportPublicationBusinessIntegrationTest'
IDENTITY_SOURCE = 'r02_business_validation'
HISTORY_HASH_SQL = ("SELECT encode(sha256(convert_to(COALESCE(jsonb_agg(to_jsonb(h) "
                    "ORDER BY installed_rank)::text,'[]'),'UTF8')),'hex') FROM public.flyway_schema_history h")


def module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    if spec is None or spec.loader is None:
        raise RuntimeError('LOCAL_REVIEWED_HELPER_MISSING')
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def active_baseline(pg, chain, fingerprints, expected_version: int) -> dict:
    """Explicitly pin the active schema; V55 is the recorded release, never an automatic fallback."""
    snapshot = chain.active_snapshot(pg, fingerprints)
    history = snapshot['history']
    exact = len(history) == expected_version \
        and [row[1] for row in history] == [str(value) for value in range(1, expected_version + 1)] \
        and all(row[-1] for row in history)
    if expected_version == 50:
        objects_match = snapshot['candidate_objects_absent']
    elif expected_version == 55:
        with pg.connection('mop_test', readonly=True) as connection, connection.cursor() as cursor:
            cursor.execute('SELECT to_regclass(name) IS NOT NULL FROM unnest(%s::text[]) AS name',
                           (['public.mop_notification_control', 'public.mop_attachment_security_revision',
                             'public.mop_report_publication_document', 'public.mop_monitoring_enrollment',
                             'public.mop_monitoring_object_binding'],))
            objects_match = all(row[0] for row in cursor.fetchall())
    else:
        raise chain.Refused('R02_UNSUPPORTED_EXPLICIT_ACTIVE_VERSION')
    if not exact or not objects_match:
        raise chain.Refused('R02_ACTIVE_BASELINE_NOT_EXPLICIT_V' + str(expected_version))
    return snapshot


def source_scope(path: Path) -> tuple[dict, str]:
    resolved = path.resolve(strict=True)
    if path.absolute() != resolved or resolved not in INPUTS:
        raise ValueError('UNAPPROVED_R02_SUMMARY_PATH')
    raw = resolved.read_bytes()
    if len(raw) > 4 * 1024 * 1024:
        raise ValueError('R02_SUMMARY_TOO_LARGE')
    document = json.loads(raw)
    run_id = document.get('run_id', '')
    if document.get('schema_version') != 1 or document.get('environment') != 'lan-test-20261008' \
            or document.get('status') != 'SCHEMA_CHAIN_VALIDATED_BUSINESS_NOT_RUN' \
            or not re.fullmatch(r'[a-f0-9]{32}', run_id):
        raise ValueError('R02_CHAIN_EVIDENCE_INVALID')
    selected = [value for value in document.get('stages', []) if value.get('stage') == 'R02']
    if len(selected) != 1:
        raise ValueError('R02_STAGE_AMBIGUOUS')
    stage = dict(selected[0])
    if stage.get('prefix') != 'mop_report_test_' or not re.fullmatch(r'mop_report_test_[a-f0-9]{12}', stage.get('database', '')) \
            or stage.get('versions') != [53] or stage.get('database_owner') != 'mop_test_migrator' \
            or stage.get('created') is not True or stage.get('database_removed') is not False \
            or stage.get('status') != 'SCHEMA_VALIDATED_BUSINESS_NOT_RUN' or stage.get('pools_closed') is not True \
            or stage.get('retained_owner_marker_verified') is not True or stage.get('retention_policy') != 'RETAIN_DATABASES_NO_DROP' \
            or not re.fullmatch(r'MOP_CANDIDATE_CHAIN:' + run_id + r':R02:[a-f0-9]{32}', stage.get('owner_marker', '')):
        raise ValueError('R02_RETAINED_OWNER_SCOPE_INVALID')
    records, observed = stage.get('migrations', []), stage.get('observed_candidates', [])
    if len(records) != 1 or len(observed) != 1:
        raise ValueError('R02_FROZEN_MIGRATION_AMBIGUOUS')
    saved, actual = records[0], observed[0]
    if saved.get('version') != '53' or saved.get('sha256') != V53_SHA256 \
            or saved.get('history_status') != 'APPLIED_FROZEN' or saved.get('history_success') is not True \
            or saved.get('apply_executed') != 1 or saved.get('repeat_executed') != 0 \
            or saved.get('history_script') != 'V53__report_publication_journal.sql' \
            or actual.get('version') != '53' or actual.get('success') is not True \
            or actual.get('resource_sha256') != V53_SHA256 or type(actual.get('flyway_checksum')) is not int \
            or actual.get('flyway_checksum') != saved.get('flyway_checksum') \
            or dt.datetime.fromisoformat(actual['installed_at']) != dt.datetime.fromisoformat(saved['installed_on']) \
            or not re.fullmatch(r'[a-f0-9]{64}', stage.get('observed_history_sha256', '')):
        raise ValueError('R02_FROZEN_VERSION_OR_TIME_MISMATCH')
    return stage, hashlib.sha256(raw).hexdigest()


def metadata(chain, pg, stage: dict) -> dict:
    with pg.connection(stage['database'], readonly=True) as connection:
        pg.assert_owned(connection, stage)
        history, capabilities = chain.metadata(connection, 53)
        expected = stage['observed_candidates'][0]
        row = history[-1]
        if chain.digest(history) != stage['observed_history_sha256'] \
                or row[4] != 'V53__report_publication_journal.sql' or row[5] != expected['flyway_checksum'] \
                or row[7] != dt.datetime.fromisoformat(expected['installed_at']):
            raise chain.Refused('R02_RETAINED_HISTORY_OR_INSTALL_TIME_CHANGED')
        with connection.cursor() as cursor:
            cursor.execute(HISTORY_HASH_SQL)
            canonical = cursor.fetchone()[0]
        return {'history_rows': len(history), 'history_sha256': chain.digest(history), 'canonical_history_sha256': canonical,
                'capabilities_sha256': chain.digest(capabilities), 'v53_checksum': row[5],
                'v53_installed_on': row[7].isoformat(timespec='microseconds')}


def existing_scope(pg, stage: dict) -> dict:
    with pg.connection(stage['database'], readonly=True) as connection:
        pg.assert_owned(connection, stage)
        with connection.cursor() as cursor:
            cursor.execute('SELECT id::text FROM public.mop_tenant ORDER BY id')
            tenants = [row[0] for row in cursor.fetchall()]
            cursor.execute('SELECT id::text FROM public.mop_user ORDER BY id')
            actors = [row[0] for row in cursor.fetchall()]
    return {'tenants': tenants, 'actors': actors}


def protected_scope(pg, stage: dict, original: dict) -> dict:
    result = {}
    with pg.connection(stage['database'], readonly=True) as connection:
        pg.assert_owned(connection, stage)
        with connection.cursor() as cursor:
            cursor.execute("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                           "WHERE n.nspname='public' AND c.relkind IN ('r','p') AND EXISTS "
                           "(SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped) "
                           "ORDER BY c.relname")
            for table, field, ids, include_null in [(row[0], 'tenant_id', original['tenants'], True) for row in cursor.fetchall()] \
                    + [('mop_user', 'id', original['actors'], False), ('mop_identity_security', 'user_id', original['actors'], False)]:
                predicate = pg.sql.SQL('{}=ANY(%s::uuid[])').format(pg.sql.Identifier(field))
                if include_null:
                    predicate = pg.sql.SQL('({} IS NULL OR {})').format(pg.sql.Identifier(field), predicate)
                query = pg.sql.SQL("SELECT count(*),md5(COALESCE(string_agg(digest,'' ORDER BY digest),'')) "
                                   "FROM (SELECT md5(to_jsonb(t)::text) digest FROM public.{} t WHERE {}) scoped")
                cursor.execute(query.format(pg.sql.Identifier(table), predicate), (ids,))
                count, digest = cursor.fetchone()
                result[table] = {'count': count, 'md5': digest}
    return result


def canonical_uuid(value) -> bool:
    return isinstance(value, str) and re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', value) is not None \
        and not value.startswith('55000000-')


def checkpoint(path: Path, stage: dict, original: dict) -> dict | None:
    if not path.exists():
        return None
    if path.is_symlink() or path.absolute() != path.resolve() or path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('R02_CHECKPOINT_PATH_OR_SIZE_INVALID')
    value = json.loads(path.read_text(encoding='utf-8'))
    tenants, actors, assignments = value.get('test_tenants'), value.get('test_actors'), value.get('test_actor_memberships')
    if value.get('database') != stage['database'] or value.get('owner_marker') != stage['owner_marker'] \
            or value.get('schema_mode') != 'PREAPPLIED_V53' or value.get('schema_migrations') != 0 \
            or value.get('identity_source') != IDENTITY_SOURCE \
            or not isinstance(tenants, list) or len(tenants) != 2 or len(set(tenants)) != 2 \
            or not isinstance(actors, list) or len(actors) != 6 or len(set(actors)) != 6 \
            or not all(canonical_uuid(item) for item in tenants + actors) \
            or set(tenants) & set(original['tenants']) or set(actors) & set(original['actors']) \
            or not isinstance(assignments, list) or len(assignments) != 6:
        raise ValueError('R02_CHECKPOINT_SCOPE_INVALID')
    seen = set()
    roles = []
    for assignment in assignments:
        actor, tenant, role = assignment.get('actor_id'), assignment.get('tenant_id'), assignment.get('role')
        if actor not in actors or actor in seen or tenant not in tenants or role not in ('padmin', 'viewer') \
                or assignment.get('subject') != 'SYNTHETIC_R02_' + actor:
            raise ValueError('R02_CHECKPOINT_MEMBERSHIP_INVALID')
        seen.add(actor)
        roles.append(role)
    if seen != set(actors) or roles.count('padmin') != 4 or roles.count('viewer') != 2 \
            or sum(item['tenant_id'] == tenants[0] for item in assignments) != 5 \
            or sum(item['tenant_id'] == tenants[1] for item in assignments) != 1:
        raise ValueError('R02_CHECKPOINT_ROLE_SCOPE_INVALID')
    return value


def deactivate_exact_scope(pg, stage: dict, value: dict) -> dict:
    pg.locks.check()
    tenants, actors, assignments = value['test_tenants'], value['test_actors'], value['test_actor_memberships']
    by_actor = {item['actor_id']: item for item in assignments}
    with pg.connection(stage['database'], readonly=False) as connection:
        pg.assert_owned(connection, stage)
        with connection.transaction(), connection.cursor() as cursor:
            cursor.execute('SELECT id::text,name FROM public.mop_tenant WHERE id=ANY(%s::uuid[]) ORDER BY id FOR UPDATE', (tenants,))
            found_tenants = cursor.fetchall()
            cursor.execute("SELECT id::text,identity_source,subject,role_code,"
                           "EXISTS(SELECT 1 FROM public.mop_local_account WHERE user_id=u.id),"
                           "EXISTS(SELECT 1 FROM public.mop_external_identity_binding WHERE user_id=u.id) "
                           "FROM public.mop_user u WHERE id=ANY(%s::uuid[]) ORDER BY id FOR UPDATE OF u", (actors,))
            found_actors = cursor.fetchall()
            if not found_tenants and not found_actors:
                return {'status': 'NOT_CREATED_VERIFIED', 'users_changed': 0, 'memberships_changed': 0, 'records_deleted': 0}
            if len(found_tenants) != 2 or {row[0] for row in found_tenants} != set(tenants) \
                    or any(row[1] != 'SYNTHETIC_R02_' + row[0] for row in found_tenants) \
                    or len(found_actors) != 6 or {row[0] for row in found_actors} != set(actors) \
                    or any(row[1] != IDENTITY_SOURCE or row[2] != by_actor[row[0]]['subject'] \
                           or row[3] != by_actor[row[0]]['role'] or row[4] or row[5] for row in found_actors):
                raise RuntimeError('R02_OWN_IDENTITY_CLEANUP_SCOPE_REJECTED')
            cursor.execute('SELECT user_id::text,tenant_id::text,role_code FROM public.mop_membership '
                           'WHERE user_id=ANY(%s::uuid[]) ORDER BY user_id,tenant_id FOR UPDATE', (actors,))
            actual = set(cursor.fetchall())
            expected = {(item['actor_id'], item['tenant_id'], item['role']) for item in assignments}
            if actual != expected:
                raise RuntimeError('R02_OWN_MEMBERSHIP_CLEANUP_SCOPE_REJECTED')
            cursor.execute('UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 '
                           'WHERE user_id=ANY(%s::uuid[]) AND tenant_id=ANY(%s::uuid[]) AND active', (actors, tenants))
            members = cursor.rowcount
            cursor.execute('UPDATE public.mop_user SET active=false WHERE id=ANY(%s::uuid[]) AND active', (actors,))
            users = cursor.rowcount
            cursor.execute('SELECT count(*) FROM public.mop_membership WHERE user_id=ANY(%s::uuid[]) AND active', (actors,))
            if cursor.fetchone()[0] != 0:
                raise RuntimeError('R02_MEMBERSHIPS_STILL_ACTIVE')
        return {'status': 'DEACTIVATED', 'users_changed': users, 'memberships_changed': members,
                'declared_actors': 6, 'declared_memberships': 6, 'records_deleted': 0}


def connections(pg, stage: dict) -> int:
    with pg.connection('postgres', readonly=True) as connection, connection.cursor() as cursor:
        pg.assert_owned(connection, stage)
        cursor.execute('SELECT count(*) FROM pg_stat_activity WHERE datname=%s', (stage['database'],))
        return cursor.fetchone()[0]


def font_identity(args) -> dict:
    if args.pdf_mode == 'HTML_ONLY':
        return {'status': 'NOT_REQUIRED_FOR_EXPLICIT_HTML_ONLY', 'copied': False}
    path = args.font_path
    if not path.is_absolute() or path.is_symlink() or not path.is_file() or not 12 <= path.stat().st_size <= 32 * 1024 * 1024 \
            or not re.fullmatch(r'[a-f0-9]{64}', args.font_sha256):
        raise ValueError('R02_FONT_PATH_OR_EXPECTED_HASH_INVALID')
    actual = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual != args.font_sha256:
        raise ValueError('R02_FONT_HASH_MISMATCH')
    renderer_identity = hashlib.sha256((actual + '\n').encode('utf-8')).hexdigest()
    return {'status': 'READ_ONLY_VERIFIED', 'path': str(path), 'sha256': actual,
            'renderer_font_identity_sha256': renderer_identity, 'copied': False}


def maven_test(chain, pg, stage: dict, before: dict, output: Path, args, summary: dict):
    pg.locks.check()
    environment = os.environ.copy()
    for key in list(environment):
        if key.startswith(('MOP_', 'SPRING_DATASOURCE_', 'PG')) or key in (
                'MAVEN_OPTS', 'MAVEN_ARGS', 'MAVEN_DEBUG_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
            environment.pop(key, None)
    prefix = 'MOP_REPORT_PUBLICATION_TEST_'
    environment['MOP_RUN_REPORT_PUBLICATION_BUSINESS_PG_TESTS'] = 'true'
    values = {'JDBC_URL': f"jdbc:postgresql://127.0.0.1:{pg.settings['port']}/{stage['database']}",
              'DATABASE': stage['database'], 'OWNER_MARKER': stage['owner_marker'],
              'RUNTIME_PASSWORD': pg.runtime['runtime_password'], 'MIGRATOR_PASSWORD': pg.runtime['migrator_password'],
              'HISTORY_SHA256': before['canonical_history_sha256'], 'V53_CHECKSUM': str(before['v53_checksum']),
              'REPOSITORY_ROOT': str(ROOT), 'EVIDENCE_PATH': str(output / 'r02-business-evidence.json'),
              'PDF_MODE': args.pdf_mode}
    if args.pdf_mode == 'REQUIRED':
        values.update(FONT_PATH=str(args.font_path), FONT_SHA256=args.font_sha256)
    environment.update({prefix + key: value for key, value in values.items()})
    command = ['mvn.cmd' if os.name == 'nt' else 'mvn', '-B', '-pl', 'server/api', '-am', 'test',
               '-Dtest=' + CLASS, '-Dsurefire.failIfNoSpecifiedTests=false']
    started = time.time_ns()
    masks = chain.secret_masks(pg)
    summary.update(java_started=False, java_process_completed=False, test_class=CLASS)
    with chain.open_evidence_log(output / 'maven.log') as log:
        process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', errors='replace')
        summary['java_started'] = True

        def consume():
            try:
                for line in process.stdout:
                    for secret in masks:
                        line = line.replace(secret, '[REDACTED]')
                    log.write(line)
                    log.flush()
            except (OSError, ValueError):
                summary['java_log_incomplete'] = True

        reader = threading.Thread(target=consume, daemon=True)
        reader.start()
        try:
            summary['maven_exit_code'] = process.wait(timeout=args.timeout)
            summary['java_process_completed'] = True
        except subprocess.TimeoutExpired:
            summary['java_child_processes_may_remain'] = True
            process.terminate()
            raise chain.Refused('R02_OWN_JAVA_TIMEOUT_DATABASE_AND_RECORDS_RETAINED')
        finally:
            reader.join(timeout=15)
            if reader.is_alive() or summary.get('java_log_incomplete'):
                summary['java_process_completed'] = False
                summary['java_child_processes_may_remain'] = True
                raise chain.Refused('R02_OWN_JAVA_OUTPUT_NOT_CLOSED')
    reports = list((ROOT / 'server/api/target/surefire-reports').glob('TEST-*' + CLASS + '.xml'))
    if len(reports) != 1 or reports[0].stat().st_mtime_ns < started - 2_000_000_000:
        raise chain.Refused('R02_FRESH_SELECTED_TEST_REPORT_MISSING')
    report = ET.parse(reports[0]).getroot()
    summary['junit'] = {key: int(report.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
    if summary['maven_exit_code'] != 0 or summary['junit']['tests'] != 1 \
            or any(summary['junit'][key] for key in ('failures', 'errors', 'skipped')):
        raise chain.Refused('R02_BUSINESS_SCENARIO_NOT_PASSED')


def validate_outputs(chain, output: Path, value: dict, before: dict, args) -> list[dict]:
    if value.get('status') != 'PASSED_WITH_EXPLICIT_SCOPE_LIMITS' or value.get('pools_closed') is not True \
            or value.get('seed_committed') is not True \
            or value.get('database_retained') is not True or value.get('history_before') != before['canonical_history_sha256'] \
            or value.get('history_after') != value.get('history_before') or value.get('external_dispatch_enabled') is not False \
            or value.get('formal_contract_acceptance') != 'NOT_RUN' or value.get('customer_template_confirmation') is not None \
            or value.get('formal_fee_configuration') is not None \
            or value.get('captured_source_publication') != 'BLOCKED_BY_UNVERIFIED_SOURCE_AND_TEMPLATE':
        raise chain.Refused('R02_JAVA_SCOPE_OR_SCHEMA_EVIDENCE_INVALID')
    checks = value.get('checks', [])
    if not isinstance(checks, list) or len({item.get('id') for item in checks}) != len(checks):
        raise chain.Refused('R02_CHECKS_MISSING_OR_DUPLICATE')
    status = {item['id']: item.get('status') for item in checks}
    for name in ('A_REAL_CAPTURE_AND_PREPARE', 'A_HTML_FAILURE_AND_IDEMPOTENT_REPLAY'):
        if status.get(name) != 'PASSED':
            raise chain.Refused('R02_CAPTURE_OR_REPLAY_CHECK_NOT_PASSED')
    manifest = []
    stems = ['synthetic-captured-quarter']
    if args.pdf_mode == 'REQUIRED':
        if status.get('A_REAL_PDF_RETURN_AND_APPROVAL_GATE') != 'PASSED' \
                or status.get('B_ISOLATED_PUBLICATION_WORKFLOW') != 'PASSED' \
                or not str(value.get('isolated_fixture_scope', '')).startswith('ISOLATED_TEST;'):
            raise chain.Refused('R02_REQUIRED_PDF_OR_ISOLATED_PUBLICATION_NOT_PASSED')
        if any(item.get('status') != 'PASSED' for item in checks):
            raise chain.Refused('R02_REQUIRED_CHECKS_NOT_FULLY_PASSED')
        stems.append('synthetic-isolated-publication')
        # The renderer binds the ordered primary/fallback font hashes, not just the primary file hash.
        # This entry point supplies no fallback; preserve its explicit empty second component.
        expected_font_identity = hashlib.sha256((args.font_sha256 + '\n').encode('utf-8')).hexdigest()
        for stem in stems:
            record = value.get(stem, {})
            if record.get('font_identity_sha256') != expected_font_identity or type(record.get('pages')) is not int \
                    or not 1 <= record['pages'] <= 200 or record.get('visual_review') != 'NOT_RUN' \
                    or not all(isinstance(record.get(key), str) and re.fullmatch(r'[a-f0-9]{64}', record[key])
                               for key in ('document_sha256', 'html_sha256', 'pdf_sha256')):
                raise chain.Refused('R02_PDF_IDENTITY_OR_PAGE_EVIDENCE_INVALID')
            for suffix in ('html', 'pdf'):
                manifest.append(output_file(chain, output, stem + '.' + suffix, record.get(suffix + '_sha256')))
    else:
        if status.get('A_RENDER_RETRY') != 'PASSED' or status.get('PDF_RENDERING') != 'PDF_NOT_RUN' \
                or status.get('B_ISOLATED_PUBLICATION_WORKFLOW') != 'NOT_RUN' or list(output.glob('*.pdf')):
            raise chain.Refused('R02_HTML_ONLY_SCOPE_MISMATCH')
        manifest.append(output_file(chain, output, 'synthetic-captured-quarter.html', None))
    failure = output_file(chain, output, 'synthetic-render-failure.html', None)
    captured = next(item for item in manifest if item['file'] == 'synthetic-captured-quarter.html')
    if failure['sha256'] != captured['sha256']:
        raise chain.Refused('R02_RETRY_CHANGED_ORIGINAL_HTML')
    manifest.append(failure)
    return manifest


def output_file(chain, folder: Path, name: str, expected: str | None) -> dict:
    path = folder / name
    if path.is_symlink() or path.resolve() != path.absolute() or not path.is_file() or not 1 <= path.stat().st_size <= 8 * 1024 * 1024:
        raise chain.Refused('R02_REQUIRED_ARTIFACT_MISSING_OR_INVALID')
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if expected is not None and digest != expected:
        raise chain.Refused('R02_ARTIFACT_HASH_MISMATCH')
    if name.endswith('.pdf') and not (raw.startswith(b'%PDF-') and raw.rstrip().endswith(b'%%EOF')):
        raise chain.Refused('R02_PDF_ENVELOPE_INVALID')
    return {'file': name, 'bytes': len(raw), 'sha256': digest, 'visual_review': 'NOT_RUN'}


def run(args) -> int:
    chain = module('r02_candidate_chain', 'verify-candidate-schema-chain.py')
    retained = module('r02_retained_helpers', 'verify-notification-routing-retained.py')
    fingerprints = module('r02_active_fingerprints', 'verify-report-capture-isolated.py')
    stage, source_hash = source_scope(args.source_summary)
    resource = ROOT / 'server/persistence/src/main/resources/db/migration/V53__report_publication_journal.sql'
    if hashlib.sha256(resource.read_bytes()).hexdigest() != V53_SHA256:
        raise chain.Refused('R02_FROZEN_V53_RESOURCE_CHANGED')
    output = chain.reserve_output(args.output)
    font = None
    run_id = uuid.uuid4().hex
    locks = retained.EnvironmentLock(args.session, run_id, stage['database'])
    locks.document['operation'] = 'report-publication-retained-business'
    summary = {'schema_version': 1, 'status': 'NOT_RUN', 'run_id': run_id, 'environment': 'lan-test-20261008',
               'database': stage['database'], 'owner_marker': stage['owner_marker'], 'schema_mode': 'PREAPPLIED_V53',
               'source_summary': str(args.source_summary.resolve().relative_to(ROOT)), 'source_summary_sha256': source_hash,
               'schema_migrations': 0, 'database_created': False, 'database_removed': False, 'records_deleted': 0,
               'actual_tenant_written': False, 'external_messages': 0, 'production_approved': False,
               'pdf_mode': args.pdf_mode, 'font': {'status': 'NOT_CHECKED'}, 'started_at': chain.now(),
               'formal_contract_acceptance': 'NOT_RUN',
               'business_validation': {'status': 'NOT_RUN', 'junit_scenarios': 0, 'visual_review': 'NOT_RUN'}}
    pg = None
    before = active_before = original = protected_before = None
    java = None
    try:
        font = font_identity(args)
        summary['font'] = font
        locks.acquire()
        pg = chain.DesignatedPostgres(locks)
        pg.open()
        active_before = active_baseline(pg, chain, fingerprints, args.expected_active_version)
        summary['postgresql_version'] = active_before['postgresql_version']
        summary['postgresql_version_observed_before'] = active_before['postgresql_version']
        summary['expected_active_version'] = args.expected_active_version
        before = metadata(chain, pg, stage)
        original = existing_scope(pg, stage)
        protected_before = protected_scope(pg, stage, original)
        summary['retained_metadata_before'] = before
        summary['active_history_before_sha256'] = active_before['history_sha256']
        summary['active_protected_before'] = active_before['protected_fingerprints']
        maven_test(chain, pg, stage, before, output, args, summary)
        java = checkpoint(output / 'r02-business-evidence.json', stage, original)
        if java is None:
            raise chain.Refused('R02_JAVA_EVIDENCE_MISSING')
        summary['artifacts'] = validate_outputs(chain, output, java, before, args)
        with pg.connection(stage['database'], readonly=True) as connection, connection.cursor() as cursor:
            pg.assert_owned(connection, stage)
            cursor.execute('SELECT count(*) FROM public.mop_report_publication_delivery_intent '
                           'WHERE tenant_id=ANY(%s::uuid[]) AND external_dispatch_enabled IS DISTINCT FROM false', (java['test_tenants'],))
            if cursor.fetchone()[0] != 0:
                raise chain.Refused('R02_EXTERNAL_DISPATCH_NOT_EXPLICITLY_DISABLED')
        summary['business_validation'].update(status='PASSED_WITH_EXPLICIT_SCOPE_LIMITS', junit_scenarios=1,
            layer_a='REAL_CAPTURE_REMAINS_APPROVAL_GATED',
            layer_b='PASSED_ISOLATED_TEST_INITIAL_STATE' if args.pdf_mode == 'REQUIRED' else 'NOT_RUN',
            pdf='PASSED_ACTUAL_FOP_OUTPUT' if args.pdf_mode == 'REQUIRED' else 'NOT_RUN',
            limitations=['NO_CUSTOMER_SOURCE_OR_TEMPLATE_APPROVAL', 'NO_REAL_EXTERNAL_DELIVERY',
                         'NO_HTTP_BROWSER_OR_VISUAL_ACCEPTANCE', 'NO_FORMAL_FEE_CONFIGURATION'])
        summary['status'] = 'PASSED_WITH_EXPLICIT_SCOPE_LIMITS' if args.pdf_mode == 'REQUIRED' else 'HTML_ONLY_VALIDATED_PDF_AND_PUBLICATION_NOT_RUN'
    except BaseException as failure:
        summary.update(status='FAILED', failure_type=type(failure).__name__,
                       reason=failure.code if isinstance(failure, chain.Refused) else 'DETAILS_WITHHELD')
    finally:
        if original is not None:
            try:
                java = checkpoint(output / 'r02-business-evidence.json', stage, original)
                if java is not None:
                    summary['java_business_evidence'] = java
            except BaseException as failure:
                java = None
                summary.update(status='FAILED', checkpoint_failure_type=type(failure).__name__)
        if pg is not None and pg.settings is not None:
            lifecycle_closed = (not summary.get('java_started') or summary.get('java_process_completed') is True) \
                and not summary.get('java_child_processes_may_remain') and not summary.get('java_log_incomplete')
            summary['java_lifecycle_confirmed_closed'] = lifecycle_closed
            try:
                summary['connections_before_actor_cleanup'] = connections(pg, stage)
                if lifecycle_closed and summary['connections_before_actor_cleanup'] == 0:
                    if java is not None:
                        summary['actor_cleanup'] = deactivate_exact_scope(pg, stage, java)
                    elif original is not None and existing_scope(pg, stage) == original and not summary.get('checkpoint_failure_type'):
                        summary['actor_cleanup'] = {'status': 'NOT_CREATED_VERIFIED', 'records_deleted': 0}
                    elif not summary.get('java_started'):
                        summary['actor_cleanup'] = {'status': 'JAVA_NOT_STARTED', 'records_deleted': 0}
                    else:
                        raise chain.Refused('R02_EXACT_NAMESPACE_MANIFEST_UNAVAILABLE')
                else:
                    raise chain.Refused('R02_JAVA_OR_CONNECTIONS_NOT_CONFIRMED_CLOSED')
            except BaseException as failure:
                summary.update(status='FAILED', actor_cleanup_failure_type=type(failure).__name__,
                               required_action='Confirm this run JVM and exact manifest; preserve actors/data and environment lock')
            try:
                if before is not None:
                    after = metadata(chain, pg, stage)
                    summary['retained_metadata_after'] = after
                    summary['retained_metadata_unchanged'] = after == before
                    summary['prior_retained_scope_unchanged'] = protected_scope(pg, stage, original) == protected_before
                    if not summary['retained_metadata_unchanged'] or not summary['prior_retained_scope_unchanged']:
                        raise chain.Refused('R02_PRIOR_SCOPE_OR_HISTORY_CHANGED')
                if active_before is not None:
                    after = active_baseline(pg, chain, fingerprints, args.expected_active_version)
                    summary['postgresql_version_observed_after'] = after['postgresql_version']
                    summary['active_history_unchanged'] = active_before['history'] == after['history']
                    summary['active_capabilities_unchanged'] = active_before['capabilities'] == after['capabilities']
                    summary['active_protected_unchanged'] = active_before['protected_fingerprints'] == after['protected_fingerprints']
                    summary['active_protected_after'] = after['protected_fingerprints']
                    if active_before['postgresql_version'] != after['postgresql_version'] \
                            or not all(summary[key] for key in ('active_history_unchanged', 'active_capabilities_unchanged', 'active_protected_unchanged')):
                        raise chain.Refused('R02_ACTIVE_READONLY_PROTECTION_CHECK_FAILED')
                summary['retained_database_open_connections'] = connections(pg, stage)
                if summary['retained_database_open_connections'] != 0:
                    raise chain.Refused('R02_TARGET_CONNECTIONS_REMAIN')
            except BaseException as failure:
                summary.update(status='FAILED', protection_failure_type=type(failure).__name__,
                               protection_reason=failure.code if isinstance(failure, chain.Refused) else 'DETAILS_WITHHELD')
        if pg is not None:
            try:
                pg.close()
            except BaseException as failure:
                summary.update(status='FAILED', connection_cleanup_failure_type=type(failure).__name__)
        try:
            summary['source_summary_unchanged'] = hashlib.sha256(args.source_summary.resolve().read_bytes()).hexdigest() == source_hash
            summary['v53_resource_unchanged'] = hashlib.sha256(resource.read_bytes()).hexdigest() == V53_SHA256
            summary['font_unchanged'] = font_identity(args) == font if font is not None else None
            if not summary['source_summary_unchanged'] or not summary['v53_resource_unchanged'] \
                    or font is not None and summary['font_unchanged'] is not True:
                raise chain.Refused('R02_IMMUTABLE_INPUT_CHANGED')
        except BaseException as failure:
            summary.update(status='FAILED', immutable_input_failure_type=type(failure).__name__)
        summary['finished_at'] = chain.now()
        summary['environment_lock_released'] = False
        chain.write_json(output / 'summary.json', summary)
        cleaned = summary.get('actor_cleanup', {}).get('status') in ('DEACTIVATED', 'NOT_CREATED_VERIFIED', 'JAVA_NOT_STARTED')
        closed = summary.get('retained_database_open_connections') == 0
        never_started = not summary.get('java_started') and original is None
        safe_release = not summary.get('java_child_processes_may_remain') and not summary.get('connection_cleanup_failure_type') \
            and ((cleaned and closed) or never_started)
        if locks.held and safe_release:
            locks.release()
            summary['environment_lock_released'] = True
            chain.write_json(output / 'summary.json', summary)
        elif locks.held:
            summary['required_action'] = 'Own environment lock retained until exact namespace and this run process lifecycle are resolved'
            chain.write_json(output / 'summary.json', summary)
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary['status'] in ('PASSED_WITH_EXPLICIT_SCOPE_LIMITS', 'HTML_ONLY_VALIDATED_PDF_AND_PUBLICATION_NOT_RUN') else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-summary', type=Path, default=INPUTS[0])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--session', default='local-astra-ut')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--pdf-mode', choices=('REQUIRED', 'HTML_ONLY'), default='REQUIRED')
    parser.add_argument('--expected-active-version', type=int, choices=(50, 55), default=50)
    parser.add_argument('--font-path', type=Path, default=FONT_PATH)
    parser.add_argument('--font-sha256', default=FONT_SHA256)
    arguments = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9._:-]{1,64}', arguments.session) or not 30 <= arguments.timeout <= 1800:
        parser.error('Explicit bounded session and timeout required')
    raise SystemExit(run(arguments))

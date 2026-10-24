"""Run the existing six notification-routing PG business tests in the retained N01 database.

The input is restricted to this checkout's first reviewed candidate-chain summary
or its corresponding public evidence copy. This tool never migrates, repairs,
creates or drops a database, sends external notifications, or changes a service.
Only new synthetic tenants and credential-free actors are prepared in the exact
owned N01 database. At completion only those actors/memberships are deactivated;
all databases and immutable records remain. Active mop_test connections are read-only.
Secrets are loaded by the existing reviewed designated-connection helper only
after this run owns the environment lock. Importing this module reads no secrets.
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
COORDINATION = Path('E:/work/wxw9/zhonganzaixian/mop-platform/.local/parallel-launch-20261023')
INPUTS = (
    ROOT / '.local/candidate-schema-chain-attempt-1/summary.json',
    ROOT / 'docs/evidence/local-astra-ut-20261023/schema-chain/summary.json',
)
V51_SHA256 = '51f26c2d0d74903a10ea04525335fff8abfa8eecf1e048e1a623a535e609d37b'
HISTORY_HASH_SQL = (
    "SELECT encode(sha256(convert_to(COALESCE(jsonb_agg(to_jsonb(h) "
    "ORDER BY installed_rank)::text,'[]'),'UTF8')),'hex') FROM public.flyway_schema_history h"
)


def module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    if spec is None or spec.loader is None:
        raise RuntimeError('Local reviewed helper unavailable')
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def load_scope(path: Path) -> tuple[dict, str]:
    absolute = path.absolute()
    resolved = path.resolve(strict=True)
    if absolute != resolved or resolved not in INPUTS:
        raise ValueError('UNAPPROVED_RETAINED_SUMMARY_PATH')
    raw = resolved.read_bytes()
    if len(raw) > 4 * 1024 * 1024:
        raise ValueError('RETAINED_SUMMARY_TOO_LARGE')
    document = json.loads(raw)
    if document.get('schema_version') != 1 or document.get('environment') != 'lan-test-20261008' \
            or document.get('status') != 'SCHEMA_CHAIN_VALIDATED_BUSINESS_NOT_RUN':
        raise ValueError('RETAINED_CHAIN_NOT_VALIDATED')
    run_id = document.get('run_id')
    if not isinstance(run_id, str) or not re.fullmatch(r'[a-f0-9]{32}', run_id):
        raise ValueError('RETAINED_CHAIN_ID_INVALID')
    stages = [item for item in document.get('stages', []) if item.get('stage') == 'N01']
    if len(stages) != 1:
        raise ValueError('RETAINED_NOTIFICATION_STAGE_AMBIGUOUS')
    stage = dict(stages[0])
    if not re.fullmatch(r'mop_notification_test_[a-f0-9]{12}', stage.get('database', '')) \
            or stage.get('prefix') != 'mop_notification_test_' or stage.get('versions') != [51] \
            or stage.get('database_owner') != 'mop_test_migrator' or stage.get('created') is not True \
            or stage.get('status') != 'SCHEMA_VALIDATED_BUSINESS_NOT_RUN' \
            or stage.get('database_removed') is not False or stage.get('retained_owner_marker_verified') is not True \
            or stage.get('pools_closed') is not True or stage.get('retention_policy') != 'RETAIN_DATABASES_NO_DROP' \
            or not re.fullmatch(r'MOP_CANDIDATE_CHAIN:' + run_id + r':N01:[a-f0-9]{32}', stage.get('owner_marker', '')):
        raise ValueError('RETAINED_N01_SCOPE_OR_OWNER_INVALID')
    migrations = stage.get('migrations', [])
    observed = stage.get('observed_candidates', [])
    if len(migrations) != 1 or len(observed) != 1:
        raise ValueError('RETAINED_V51_EVIDENCE_AMBIGUOUS')
    migration, actual = migrations[0], observed[0]
    if migration.get('version') != '51' or migration.get('sha256') != V51_SHA256 \
            or migration.get('apply_executed') != 1 or migration.get('repeat_executed') != 0 \
            or migration.get('history_status') != 'APPLIED_FROZEN' or migration.get('history_success') is not True \
            or migration.get('history_script') != 'V51__notification_routing_control.sql' \
            or actual.get('version') != '51' or actual.get('success') is not True \
            or actual.get('resource_sha256') != V51_SHA256 \
            or actual.get('flyway_checksum') != migration.get('flyway_checksum') \
            or not isinstance(actual.get('flyway_checksum'), int):
        raise ValueError('RETAINED_V51_NOT_FROZEN')
    if dt.datetime.fromisoformat(actual['installed_at']) != dt.datetime.fromisoformat(migration['installed_on']):
        raise ValueError('RETAINED_V51_FREEZE_TIMESTAMPS_DISAGREE')
    if not re.fullmatch(r'[a-f0-9]{64}', stage.get('observed_history_sha256', '')):
        raise ValueError('RETAINED_HISTORY_DIGEST_MISSING')
    return stage, hashlib.sha256(raw).hexdigest()


class EnvironmentLock:
    """Own only this run's environment lock; never steal another session's lock."""

    def __init__(self, session: str, run_id: str, database: str):
        self.path = COORDINATION / 'environment.lock'
        self.document = {'owner': run_id, 'session': session, 'operation': 'notification-retained-business',
                         'database': database, 'pid': os.getpid(), 'started_at': dt.datetime.now(dt.timezone.utc).isoformat()}
        self.identity = None
        self.held = False

    def acquire(self):
        with self.path.open('x', encoding='utf-8') as output:
            self.held = True
            stat = self.path.stat()
            self.identity = (stat.st_dev, stat.st_ino)
            json.dump(self.document, output)
            output.flush()
            os.fsync(output.fileno())
        self.check()

    def check(self):
        if not self.held or self.path.is_symlink():
            raise RuntimeError('ENVIRONMENT_LOCK_NOT_OWNED')
        stat = self.path.stat()
        if (stat.st_dev, stat.st_ino) != self.identity or json.loads(self.path.read_text(encoding='utf-8')) != self.document:
            raise RuntimeError('ENVIRONMENT_LOCK_CHANGED')

    def release(self):
        self.check()
        self.path.unlink()
        self.held = False


def retained_metadata(chain, pg, stage: dict) -> dict:
    with pg.connection(stage['database'], readonly=True) as connection:
        pg.assert_owned(connection, stage)
        history, capabilities = chain.metadata(connection, 51)
        expected = stage['observed_candidates'][0]
        row = history[-1]
        if chain.digest(history) != stage['observed_history_sha256'] \
                or row[4] != 'V51__notification_routing_control.sql' or row[5] != expected['flyway_checksum'] \
                or row[7] != dt.datetime.fromisoformat(expected['installed_at']):
            raise chain.Refused('RETAINED_HISTORY_OR_FIRST_INSTALL_CHANGED')
        with connection.cursor() as cursor:
            cursor.execute(HISTORY_HASH_SQL)
            canonical_hash = cursor.fetchone()[0]
        return {'history_sha256': chain.digest(history), 'canonical_history_sha256': canonical_hash,
                'capabilities_sha256': chain.digest(capabilities), 'v51_checksum': row[5],
                'v51_installed_on': row[7].isoformat(timespec='microseconds'), 'history_rows': len(history)}


def prior_scope_fingerprints(pg, stage: dict, tenants: list[str], actors: list[str]) -> dict:
    """Protect earlier retained evidence without exposing its tenant or record bodies."""
    result = {}
    with pg.connection(stage['database'], readonly=True) as connection:
        pg.assert_owned(connection, stage)
        with connection.cursor() as cursor:
            cursor.execute("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                           "WHERE n.nspname='public' AND c.relkind IN ('r','p') AND EXISTS "
                           "(SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped) "
                           "ORDER BY c.relname")
            tables = [row[0] for row in cursor.fetchall()]
            for table in tables:
                query = pg.sql.SQL("SELECT count(*),md5(COALESCE(string_agg(digest,'' ORDER BY digest),'')) "
                                   "FROM (SELECT md5(to_jsonb(t)::text) digest FROM public.{} t "
                                   "WHERE tenant_id IS NULL OR NOT(tenant_id=ANY(%s::uuid[]))) scoped")
                cursor.execute(query.format(pg.sql.Identifier(table)), (tenants,))
                count, digest = cursor.fetchone()
                result[table] = {'count': count, 'md5': digest}
            for table, field in (('mop_user', 'id'), ('mop_identity_security', 'user_id')):
                query = pg.sql.SQL("SELECT count(*),md5(COALESCE(string_agg(digest,'' ORDER BY digest),'')) "
                                   "FROM (SELECT md5(to_jsonb(t)::text) digest FROM public.{} t "
                                   "WHERE NOT({}=ANY(%s::uuid[]))) scoped")
                cursor.execute(query.format(pg.sql.Identifier(table), pg.sql.Identifier(field)), (actors,))
                count, digest = cursor.fetchone()
                result[table] = {'count': count, 'md5': digest}
    return result


def seed(pg, stage: dict, tenants: list[str], actors: list[str]):
    pg.locks.check()
    with pg.connection(stage['database'], readonly=False) as connection:
        pg.assert_owned(connection, stage)
        with connection.transaction(), connection.cursor() as cursor:
            for actor, role in zip(actors, ('padmin', 'viewer')):
                cursor.execute("INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) "
                               "VALUES(%s,'isolated_notification_routing',%s,%s,%s)",
                               (actor, 'SYNTHETIC_NOTIFICATION_' + actor, 'SYNTHETIC_NOTIFICATION_' + role, role))
            for tenant in tenants:
                cursor.execute('INSERT INTO public.mop_tenant(id,name) VALUES(%s,%s)',
                               (tenant, 'SYNTHETIC_NOTIFICATION_' + tenant))
                for actor, role in zip(actors, ('padmin', 'viewer')):
                    cursor.execute('INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(%s,%s,%s)',
                                   (tenant, actor, role))


def deactivate_own_actors(pg, stage: dict, tenants: list[str], actors: list[str]) -> dict:
    pg.locks.check()
    with pg.connection(stage['database'], readonly=False) as connection:
        pg.assert_owned(connection, stage)
        with connection.transaction(), connection.cursor() as cursor:
            cursor.execute("SELECT id::text,identity_source,subject,EXISTS(SELECT 1 FROM public.mop_local_account WHERE user_id=u.id),"
                           "EXISTS(SELECT 1 FROM public.mop_external_identity_binding WHERE user_id=u.id),"
                           "EXISTS(SELECT 1 FROM public.mop_membership WHERE user_id=u.id AND NOT(tenant_id=ANY(%s::uuid[]))) "
                           "FROM public.mop_user u WHERE id=ANY(%s::uuid[]) ORDER BY id", (tenants, actors))
            rows = cursor.fetchall()
            if len(rows) != 2 or {row[0] for row in rows} != set(actors) or any(
                    row[1] != 'isolated_notification_routing' or row[2] != 'SYNTHETIC_NOTIFICATION_' + row[0]
                    or any(row[3:]) for row in rows):
                raise RuntimeError('OWN_ACTOR_DEACTIVATION_SCOPE_REJECTED')
            cursor.execute('UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 '
                           'WHERE user_id=ANY(%s::uuid[]) AND tenant_id=ANY(%s::uuid[]) AND active', (actors, tenants))
            members = cursor.rowcount
            cursor.execute('UPDATE public.mop_user SET active=false WHERE id=ANY(%s::uuid[]) AND active', (actors,))
            users = cursor.rowcount
            cursor.execute('SELECT count(*) FROM public.mop_membership WHERE user_id=ANY(%s::uuid[]) AND active', (actors,))
            if cursor.fetchone()[0] != 0:
                raise RuntimeError('OWN_MEMBERSHIPS_STILL_ACTIVE')
        return {'status': 'DEACTIVATED', 'users_changed': users, 'memberships_changed': members,
                'records_deleted': 0, 'database_removed': False}


def maven_test(chain, pg, stage: dict, before: dict, output: Path, tenants: list[str], actors: list[str],
               summary: dict, timeout: int):
    pg.locks.check()
    environment = os.environ.copy()
    for key in list(environment):
        if key.startswith(('MOP_', 'SPRING_DATASOURCE_', 'PG')) or key in (
                'MAVEN_OPTS', 'MAVEN_ARGS', 'MAVEN_DEBUG_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
            environment.pop(key, None)
    environment.update({
        'MOP_NOTIFICATION_ROUTING_PG_TEST': 'isolated-database',
        'MOP_NOTIFICATION_ROUTING_SCHEMA_MODE': 'PREAPPLIED_V51',
        'MOP_NOTIFICATION_EXPECTED_DATABASE': stage['database'],
        'MOP_NOTIFICATION_EXPECTED_OWNER_MARKER': stage['owner_marker'],
        'MOP_NOTIFICATION_EXPECTED_V51_CHECKSUM': str(before['v51_checksum']),
        'MOP_NOTIFICATION_EXPECTED_HISTORY_SHA256': before['canonical_history_sha256'],
        'MOP_NOTIFICATION_REPOSITORY_ROOT': str(ROOT),
        'MOP_NOTIFICATION_RETAINED_EVIDENCE_PATH': str(output / 'java-retained-evidence.json'),
        'SPRING_DATASOURCE_URL': f"jdbc:postgresql://127.0.0.1:{pg.settings['port']}/{stage['database']}",
        'SPRING_DATASOURCE_USERNAME': 'mop_test_runtime', 'SPRING_DATASOURCE_PASSWORD': pg.runtime['runtime_password'],
        'MOP_MIGRATION_PASSWORD': pg.runtime['migrator_password'],
        'MOP_NOTIFICATION_TEST_TENANT': tenants[0], 'MOP_NOTIFICATION_TEST_FOREIGN_TENANT': tenants[1],
        'MOP_NOTIFICATION_TEST_ACTOR': actors[0], 'MOP_NOTIFICATION_TEST_RECEIVER': actors[1],
    })
    command = ['mvn.cmd' if os.name == 'nt' else 'mvn', '-B', '-pl', 'server/worker', '-am', 'test',
               '-Dtest=NotificationRoutingPostgresTest', '-Dsurefire.failIfNoSpecifiedTests=false']
    masks = chain.secret_masks(pg)
    started = time.time_ns()
    summary.update(java_started=False, java_process_completed=False, test_class='NotificationRoutingPostgresTest')
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
            summary['maven_exit_code'] = process.wait(timeout=timeout)
            summary['java_process_completed'] = True
        except subprocess.TimeoutExpired:
            summary['java_child_processes_may_remain'] = True
            process.terminate()  # This child launcher only; never another session, PostgreSQL backend or database.
            raise chain.Refused('OWN_JAVA_TIMEOUT_RETAINED_DATABASE_UNCHANGED')
        finally:
            reader.join(timeout=15)
            if reader.is_alive() or summary.get('java_log_incomplete'):
                summary['java_process_completed'] = False
                summary['java_child_processes_may_remain'] = True
                raise chain.Refused('OWN_JAVA_OUTPUT_NOT_CLOSED')
    reports = list((ROOT / 'server/worker/target/surefire-reports').glob('TEST-*NotificationRoutingPostgresTest.xml'))
    if len(reports) != 1 or reports[0].stat().st_mtime_ns < started - 2_000_000_000:
        raise chain.Refused('FRESH_NOTIFICATION_TEST_REPORT_MISSING')
    report = ET.parse(reports[0]).getroot()
    summary['junit'] = {key: int(report.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
    if summary['maven_exit_code'] != 0 or summary['junit']['tests'] != 6 \
            or any(summary['junit'][key] for key in ('failures', 'errors', 'skipped')):
        raise chain.Refused('FULL_NOTIFICATION_BUSINESS_SUITE_NOT_PASSED')
    evidence = json.loads((output / 'java-retained-evidence.json').read_text(encoding='utf-8'))
    if evidence.get('status') != 'PASSED' or evidence.get('schema_mode') != 'PREAPPLIED_V51' \
            or evidence.get('database') != stage['database'] or evidence.get('owner_marker') != stage['owner_marker'] \
            or evidence.get('migration_calls') != 0 or evidence.get('schema_mutations') != 0 \
            or evidence.get('pools_closed') is not True or evidence.get('history_unchanged') is not True \
            or evidence.get('history_before') != evidence.get('history_after') \
            or evidence.get('history_before', {}).get('sha256') != before['canonical_history_sha256'] \
            or evidence.get('history_before', {}).get('rows') != 51 \
            or evidence.get('history_before', {}).get('v51_checksum') != before['v51_checksum'] \
            or evidence.get('v51_resource_sha256') != V51_SHA256:
        raise chain.Refused('RETAINED_JAVA_GUARD_EVIDENCE_INVALID')
    if dt.datetime.fromisoformat(evidence['history_before']['v51_installed_on']) \
            != dt.datetime.fromisoformat(before['v51_installed_on']):
        raise chain.Refused('RETAINED_JAVA_FIRST_INSTALL_TIMESTAMP_MISMATCH')
    summary['java_schema_evidence'] = evidence


def run(args) -> int:
    chain = module('notification_retained_chain', 'verify-candidate-schema-chain.py')
    fingerprints = module('notification_retained_fingerprints', 'verify-report-capture-isolated.py')
    stage, source_digest = load_scope(args.source_summary)
    resource = ROOT / 'server/persistence/src/main/resources/db/migration/V51__notification_routing_control.sql'
    if hashlib.sha256(resource.read_bytes()).hexdigest() != V51_SHA256:
        raise chain.Refused('FROZEN_V51_RESOURCE_CHANGED')
    output = chain.reserve_output(args.output)
    run_id = uuid.uuid4().hex
    tenants = [str(uuid.uuid4()), str(uuid.uuid4())]
    actors = [str(uuid.uuid4()), str(uuid.uuid4())]
    locks = EnvironmentLock(args.session, run_id, stage['database'])
    summary = {'schema_version': 1, 'status': 'NOT_RUN', 'run_id': run_id, 'environment': 'lan-test-20261008',
               'schema_mode': 'PREAPPLIED_V51', 'database': stage['database'], 'owner_marker': stage['owner_marker'],
               'source_summary': str(args.source_summary.resolve().relative_to(ROOT)), 'source_summary_sha256': source_digest,
               'schema_migrations': 0, 'database_created': False, 'database_removed': False,
               'records_deleted': 0, 'actual_tenant_written': False, 'external_messages': 0, 'production_approved': False,
               'synthetic_tenants': tenants, 'synthetic_actors': actors, 'started_at': chain.now(),
               'business_validation': {'status': 'NOT_RUN', 'test_count': 0,
                                       'scope': 'SYNTHETIC_ISOLATED_DURABLE_ROUTING_NO_EXTERNAL_DISPATCH'}}
    pg = None
    seeded = False
    active_before = None
    retained_before = None
    protected_before = None
    try:
        locks.acquire()
        pg = chain.DesignatedPostgres(locks)
        pg.open()
        active_before = chain.active_snapshot(pg, fingerprints)
        if not active_before['exact_v50'] or not active_before['candidate_objects_absent']:
            raise chain.Refused('ACTIVE_V50_BASELINE_CHANGED')
        retained_before = retained_metadata(chain, pg, stage)
        protected_before = prior_scope_fingerprints(pg, stage, tenants, actors)
        summary['retained_metadata_before'] = retained_before
        summary['active_history_before_sha256'] = active_before['history_sha256']
        summary['active_protected_before'] = active_before['protected_fingerprints']
        seed(pg, stage, tenants, actors)
        seeded = True
        maven_test(chain, pg, stage, retained_before, output, tenants, actors, summary, args.timeout)
        with pg.connection(stage['database'], readonly=True) as connection:
            pg.assert_owned(connection, stage)
            with connection.cursor() as cursor:
                cursor.execute("SELECT count(*) FROM public.mop_notification_control WHERE tenant_id=ANY(%s::uuid[]) "
                               "AND kind='ROUTE' AND state->>'external_dispatch_enabled'='true'", (tenants,))
                if cursor.fetchone()[0] != 0:
                    raise chain.Refused('EXTERNAL_DISPATCH_UNEXPECTEDLY_ENABLED')
        summary['business_validation'].update(status='PASSED', test_count=6, limitations=[
            'NO_REAL_PROVIDER_SEND_OR_DELIVERY_ACCEPTANCE', 'SYNTHETIC_POLICY_AND_VERIFIED_BINDING_FIXTURES',
            'NO_CUSTOMER_PRODUCTION_APPROVAL'])
        summary['status'] = 'PASSED'
    except BaseException as failure:
        summary.update(status='FAILED', failure_type=type(failure).__name__,
                       reason=failure.code if isinstance(failure, chain.Refused) else 'DETAILS_WITHHELD')
    finally:
        if pg is not None and pg.settings is not None:
            if seeded:
                lifecycle_closed = (not summary.get('java_started') or summary.get('java_process_completed') is True) \
                    and not summary.get('java_child_processes_may_remain') and not summary.get('java_log_incomplete')
                summary['java_lifecycle_confirmed_closed'] = lifecycle_closed
                try:
                    with pg.connection('postgres', readonly=True) as connection, connection.cursor() as cursor:
                        pg.assert_owned(connection, stage)
                        cursor.execute('SELECT count(*) FROM pg_stat_activity WHERE datname=%s', (stage['database'],))
                        summary['connections_before_actor_cleanup'] = cursor.fetchone()[0]
                    if lifecycle_closed and summary['connections_before_actor_cleanup'] == 0:
                        summary['actor_cleanup'] = deactivate_own_actors(pg, stage, tenants, actors)
                    else:
                        summary['status'] = 'FAILED'
                        summary['actor_cleanup'] = {'status': 'DEFERRED_JAVA_OR_CONNECTIONS_UNCONFIRMED',
                                                    'records_deleted': 0, 'database_removed': False}
                        summary['required_action'] = ('Confirm this run JVM has exited and retained database has no '
                                                      'connections before deactivating only this run actors')
                except BaseException as failure:
                    summary.update(status='FAILED', actor_cleanup_failure_type=type(failure).__name__,
                                   required_action='Inspect this run lifecycle and own actors; environment lock is retained')
            try:
                if retained_before is not None:
                    after = retained_metadata(chain, pg, stage)
                    summary['retained_metadata_after'] = after
                    summary['retained_metadata_unchanged'] = retained_before == after
                    summary['prior_retained_scope_unchanged'] = protected_before == prior_scope_fingerprints(pg, stage, tenants, actors)
                    if not summary['retained_metadata_unchanged'] or not summary['prior_retained_scope_unchanged']:
                        raise chain.Refused('RETAINED_PRIOR_DATA_OR_HISTORY_CHANGED')
                if active_before is not None:
                    after = chain.active_snapshot(pg, fingerprints)
                    summary['active_history_unchanged'] = active_before['history'] == after['history']
                    summary['active_capabilities_unchanged'] = active_before['capabilities'] == after['capabilities']
                    summary['active_protected_unchanged'] = active_before['protected_fingerprints'] == after['protected_fingerprints']
                    summary['active_protected_after'] = after['protected_fingerprints']
                    if not after['exact_v50'] or not after['candidate_objects_absent'] or not all(summary[key] for key in (
                            'active_history_unchanged', 'active_capabilities_unchanged', 'active_protected_unchanged')):
                        raise chain.Refused('ACTIVE_READONLY_PROTECTION_CHECK_FAILED')
                with pg.connection('postgres', readonly=True) as connection, connection.cursor() as cursor:
                    pg.assert_owned(connection, stage)
                    cursor.execute('SELECT count(*) FROM pg_stat_activity WHERE datname=%s', (stage['database'],))
                    summary['retained_database_open_connections'] = cursor.fetchone()[0]
                    if summary['retained_database_open_connections'] != 0:
                        summary['status'] = 'FAILED'
                        summary['required_action'] = 'Inspect this run JVM; no sessions or databases were terminated'
            except BaseException as failure:
                summary.update(status='FAILED', protection_failure_type=type(failure).__name__,
                               protection_reason=failure.code if isinstance(failure, chain.Refused) else 'DETAILS_WITHHELD')
        if pg is not None:
            try:
                pg.close()
            except BaseException as failure:
                summary.update(status='FAILED', connection_cleanup_failure_type=type(failure).__name__)
        try:
            summary['source_summary_unchanged'] = hashlib.sha256(args.source_summary.resolve().read_bytes()).hexdigest() == source_digest
            summary['v51_resource_unchanged'] = hashlib.sha256(resource.read_bytes()).hexdigest() == V51_SHA256
            if not summary['source_summary_unchanged'] or not summary['v51_resource_unchanged']:
                summary.update(status='FAILED', required_action='Reconcile changed immutable input evidence or V51 resource')
        except BaseException as failure:
            summary.update(status='FAILED', immutable_input_check_failure_type=type(failure).__name__)
        summary['finished_at'] = chain.now()
        summary['environment_lock_released'] = False
        chain.write_json(output / 'summary.json', summary)
        connections_verified_closed = summary.get('retained_database_open_connections') == 0
        no_fixture_or_java_started = not seeded and not summary.get('java_started')
        actor_cleanup_verified = not seeded or summary.get('actor_cleanup', {}).get('status') == 'DEACTIVATED'
        safe_release = not summary.get('java_child_processes_may_remain') \
            and not summary.get('connection_cleanup_failure_type') and actor_cleanup_verified \
            and (connections_verified_closed or no_fixture_or_java_started)
        if locks.held and safe_release:
            locks.release()
            summary['environment_lock_released'] = True
            chain.write_json(output / 'summary.json', summary)
        elif locks.held:
            summary['required_action'] = 'Own environment lock retained until this run JVM and database connections are confirmed closed'
            chain.write_json(output / 'summary.json', summary)
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if summary['status'] == 'PASSED' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source-summary', type=Path, default=INPUTS[0])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--session', default='local-astra-ut')
    parser.add_argument('--timeout', type=int, default=600)
    arguments = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9._:-]{1,64}', arguments.session) or not 30 <= arguments.timeout <= 1800:
        parser.error('Explicit bounded session and timeout required')
    raise SystemExit(run(arguments))

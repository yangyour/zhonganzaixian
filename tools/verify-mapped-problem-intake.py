"""Verify raw-to-business transactions in the retained owned M02/V55 database.

The explicitly selected scope is either the retained database or fresh isolated tenants in active mop_test.
No automatic scope fallback, migrations, database creation/deletion, device contact, external notification or deployment.
Only this run's new synthetic namespaces are writable; old retained rows are fingerprinted.
Private configuration is loaded by the reviewed helper after owning the environment lock.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/evidence/local-astra-ut-20261023/schema-chain/summary.json'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def assert_target(pg, connection, stage):
    if not stage.get('active_isolated_tenants'):
        pg.assert_owned(connection, stage)
        return
    with connection.cursor() as cursor:
        cursor.execute("SELECT current_database(),current_user,session_user")
        assert cursor.fetchone() == ('mop_test', 'postgres', 'postgres')


def protected_scope(pg, stage, tenants, actor):
    result = {}
    with pg.connection(stage['database'], readonly=True) as connection:
        assert_target(pg, connection, stage)
        with connection.cursor() as cursor:
            cursor.execute("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                           "WHERE n.nspname='public' AND c.relkind IN ('r','p') AND EXISTS "
                           "(SELECT 1 FROM pg_attribute a WHERE a.attrelid=c.oid AND a.attname='tenant_id' AND NOT a.attisdropped) ORDER BY c.relname")
            tables = [(row[0], 'tenant_id') for row in cursor.fetchall()]
            tables += [('mop_monitoring_quarantine', 'claimed_tenant_id'), ('mop_user', 'id'), ('mop_identity_security', 'user_id')]
            for table, column in tables:
                ids = [actor] if table in ('mop_user', 'mop_identity_security') else tenants
                query = pg.sql.SQL("SELECT count(*),md5(COALESCE(string_agg(digest,'' ORDER BY digest),'')) "
                                   "FROM (SELECT md5(to_jsonb(t)::text) digest FROM public.{} t "
                                   "WHERE {} IS NULL OR NOT({}=ANY(%s::uuid[]))) scoped")
                cursor.execute(query.format(pg.sql.Identifier(table), pg.sql.Identifier(column), pg.sql.Identifier(column)), (ids,))
                count, digest = cursor.fetchone()
                result[table] = {'count': count, 'md5': digest}
    return result


def run(output, scope):
    raw = SOURCE.read_bytes()
    source = json.loads(raw)
    assert source['environment'] == 'lan-test-20261008'
    assert source['status'] == 'SCHEMA_CHAIN_VALIDATED_BUSINESS_NOT_RUN'
    stages = [stage for stage in source['stages'] if stage['stage'] == 'M02']
    assert len(stages) == 1
    stage = stages[0]
    assert stage['created'] and stage['database_removed'] is False and stage['versions'] == [54, 55]
    assert re.fullmatch(r'mop_monitoring_test_[a-f0-9]{12}', stage['database'])
    assert stage['prefix'] == 'mop_monitoring_test_' and stage['database_owner'] == 'mop_test_migrator'
    assert re.fullmatch('MOP_CANDIDATE_CHAIN:' + source['run_id'] + ':M02:[a-f0-9]{32}', stage['owner_marker'])
    if scope == 'active-isolated-tenants':
        stage = {'database': 'mop_test', 'owner_marker': None, 'active_isolated_tenants': True}
    output = output.resolve()
    assert output.is_relative_to((ROOT / '.local').resolve())
    output.mkdir(parents=True, exist_ok=False)
    chain = module('mapped_problem_chain', 'verify-candidate-schema-chain.py')
    retained = module('mapped_problem_retained', 'verify-notification-routing-retained.py')
    locks = retained.EnvironmentLock('remaining-gaps-20261024', uuid.uuid4().hex, stage['database'])
    tenants, actor, asset = [str(uuid.uuid4()), str(uuid.uuid4())], str(uuid.uuid4()), str(uuid.uuid4())
    report = {'environment': 'lan-test-20261008', 'database': stage['database'], 'owner_marker': stage['owner_marker'],
              'source_summary_sha256': hashlib.sha256(raw).hexdigest(), 'status': 'NOT_RUN', 'database_scope': scope,
              'schema_migrations': 0, 'database_created': False, 'database_removed': False,
              'actual_tenant_written': False, 'source_protocol': 'ISOLATED_SYNTHETIC', 'devices_contacted': 0,
              'external_messages': 0, 'formal_contract_acceptance': 'NOT_RUN', 'started_at': chain.now()}
    pg = None
    seeded = False
    process = None
    exited = False
    try:
        locks.acquire()
        pg = chain.DesignatedPostgres(locks)
        pg.open()
        with pg.connection(stage['database'], readonly=True) as c:
            assert_target(pg, c, stage)
            history, capabilities = chain.metadata(c, 55)
            if scope == 'retained':
                assert chain.digest(history) == stage['observed_history_sha256']
            with c.cursor() as cursor:
                cursor.execute('SHOW server_version')
                report['postgresql_version'] = cursor.fetchone()[0]
        with pg.connection('mop_test', readonly=True) as c:
            active_history, _ = chain.metadata(c, 55)
        before = protected_scope(pg, stage, tenants, actor)
        with pg.connection(stage['database'], readonly=False) as c:
            assert_target(pg, c, stage)
            with c.transaction(), c.cursor() as cursor:
                cursor.execute("INSERT INTO public.mop_user(id,identity_source,subject,display_name,role_code) "
                               "VALUES(%s,'isolated_mapped_problem',%s,'SYNTHETIC_MAPPED_PROBLEM','padmin')",
                               (actor, 'SYNTHETIC_MAPPED_PROBLEM_' + actor))
                for tenant in tenants:
                    cursor.execute("INSERT INTO public.mop_tenant(id,name) VALUES(%s,%s)",
                                   (tenant, 'SYNTHETIC_MAPPED_PROBLEM_' + tenant))
                    cursor.execute("INSERT INTO public.mop_membership(tenant_id,user_id,role_code) VALUES(%s,%s,'padmin')",
                                   (tenant, actor))
                cursor.execute("INSERT INTO public.mop_site(tenant_id,id,name) VALUES(%s,'mapped-problem-test','SYNTHETIC_MAPPED_PROBLEM')", (tenants[0],))
                cursor.execute("INSERT INTO public.mop_asset(id,tenant_id,name,type,management_ip,site_id,source,created_by,updated_by) "
                               "VALUES(%s,%s,'SYNTHETIC_MAPPED_PROBLEM','other','192.0.2.86','mapped-problem-test','manual',%s,%s)",
                               (asset, tenants[0], actor, actor))
        seeded = True
        env = os.environ.copy()
        for key in list(env):
            if key.startswith(('MOP_', 'SPRING_DATASOURCE_', 'PG')) or key in (
                    'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', 'MAVEN_OPTS', 'MAVEN_ARGS', 'MAVEN_DEBUG_OPTS'):
                env.pop(key, None)
        env.update(MOP_MAPPED_PROBLEM_PG_TEST='retained-v55' if scope == 'retained' else scope, MOP_MAPPED_PROBLEM_DATABASE=stage['database'],
                   MOP_MAPPED_PROBLEM_OWNER_MARKER=stage['owner_marker'] or 'ACTIVE_ISOLATED_TENANTS', MOP_MAPPED_PROBLEM_TENANT=tenants[0],
                   MOP_MAPPED_PROBLEM_FOREIGN_TENANT=tenants[1], MOP_MAPPED_PROBLEM_ACTOR=actor,
                   MOP_MAPPED_PROBLEM_ASSET=asset, SPRING_DATASOURCE_USERNAME='mop_test_runtime',
                   SPRING_DATASOURCE_PASSWORD=pg.runtime['runtime_password'],
                   SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{pg.settings['port']}/{stage['database']}")
        started = time.time_ns()
        masks = chain.secret_masks(pg)
        command = ['mvn.cmd' if os.name == 'nt' else 'mvn', '-B', '-pl', 'server/api', '-am', 'test',
                   '-Dtest=MappedProblemIntakePostgresTest', '-Dsurefire.failIfNoSpecifiedTests=false']
        with chain.open_evidence_log(output / 'maven.log') as log:
            process = subprocess.Popen(command, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding='utf-8', errors='replace')
            # Bound the child separately so log streaming never waits indefinitely on a stalled Maven process.
            import threading
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
            assert not reader.is_alive(), 'JAVA_LOG_NOT_CLOSED'
            exited = True
        reports = list((ROOT / 'server/api/target/surefire-reports').glob('TEST-*MappedProblemIntakePostgresTest.xml'))
        assert len(reports) == 1 and reports[0].stat().st_mtime_ns >= started - 2_000_000_000
        suite = ET.parse(reports[0]).getroot()
        report['junit'] = {key: int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
        assert report['maven_exit_code'] == 0 and report['junit'] == dict(tests=6, failures=0, errors=0, skipped=0)
        after = protected_scope(pg, stage, tenants, actor)
        assert before == after, 'PRIOR_RETAINED_ROWS_CHANGED'
        with pg.connection(stage['database'], readonly=True) as c:
            assert_target(pg, c, stage)
            after_history, after_capabilities = chain.metadata(c, 55)
            assert history == after_history and capabilities == after_capabilities
        with pg.connection('mop_test', readonly=True) as c:
            assert active_history == chain.metadata(c, 55)[0]
        report.update(status='PASSED_REAL_PG_WITH_SYNTHETIC_SOURCE', prior_retained_rows_unchanged=True,
                      schema_history_unchanged=True, active_schema_history_unchanged=True,
                      test_tenants=tenants, test_actor=actor, test_asset=asset)
    except Exception as failure:
        report.update(status='FAILED', failure_type=type(failure).__name__)
        if getattr(failure, 'diag', None):
            report['database_error'] = {key: getattr(failure.diag, key, None) for key in ('table_name', 'column_name', 'constraint_name')}
    finally:
        if pg is not None:
            if seeded and (process is None or exited):
                locks.check()
                with pg.connection(stage['database'], readonly=False) as c:
                    assert_target(pg, c, stage)
                    with c.transaction(), c.cursor() as cursor:
                        cursor.execute("SELECT identity_source,subject FROM public.mop_user WHERE id=%s", (actor,))
                        assert cursor.fetchone() == ('isolated_mapped_problem', 'SYNTHETIC_MAPPED_PROBLEM_' + actor)
                        cursor.execute("UPDATE public.mop_membership SET active=false,permission_version=permission_version+1 "
                                       "WHERE tenant_id=ANY(%s::uuid[]) AND user_id=%s AND active", (tenants, actor))
                        cursor.execute('UPDATE public.mop_user SET active=false WHERE id=%s', (actor,))
                        report['own_actor_deactivated'] = True
            pg.close()
        if locks.held and (process is None or exited):
            locks.release()
            report['environment_lock_released'] = True
        report['finished_at'] = chain.now()
        chain.write_json(output / 'summary.json', report)
    print(json.dumps({key: report.get(key) for key in ('status', 'junit', 'postgresql_version', 'failure_type')}, ensure_ascii=False))
    return 0 if report['status'] == 'PASSED_REAL_PG_WITH_SYNTHETIC_SOURCE' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--scope', choices=('retained', 'active-isolated-tenants'), default='retained')
    args = parser.parse_args()
    raise SystemExit(run(args.output, args.scope))

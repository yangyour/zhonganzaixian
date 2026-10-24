"""Validate candidate V51-V55 on separately named databases of the designated PG.

This tool must be reviewed before execution. It never migrates active mop_test,
changes roles, starts services, sends notifications, or terminates PG connections.
Each successor copies only public schema, exact Flyway history and the capability
limit dictionary. Inherited history is provenance, not a repeated migration run.
Java owns synthetic test fixtures and must distinguish schema/ACL/row-lock checks
from completed business integration. All output remains under this checkout's .local.
Databases are retained by default. Destructive cleanup requires an explicit future
--cleanup-databases invocation authorized by the human user.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import threading
import time
import urllib.parse
import uuid
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
PRIVATE = Path('E:/work/wxw9/zhonganzaixian/mop-platform/deploy/private')
COORDINATION = Path('E:/work/wxw9/zhonganzaixian/mop-platform/.local/parallel-launch-20261023')
ENVIRONMENT = 'lan-test-20261008'
HOST = '192.168.1.215'
MIGRATOR = 'mop_test_migrator'
RUNTIME = 'mop_test_runtime'
V51_SHA256 = '51f26c2d0d74903a10ea04525335fff8abfa8eecf1e048e1a623a535e609d37b'
HISTORY_COLUMNS = ('installed_rank', 'version', 'description', 'type', 'script', 'checksum',
                   'installed_by', 'installed_on', 'execution_time', 'success')
METADATA_TABLES = frozenset(('flyway_schema_history', 'mop_role_capability_limit'))
STAGES = (
    ('N01', 'mop_notification_test_', (51,)),
    ('P02', 'mop_attachment_test_', (52,)),
    ('R02', 'mop_report_test_', (53,)),
    ('M02', 'mop_monitoring_test_', (54, 55)),
)
# A failed evidence/log write permanently disables destructive cleanup for this run.
EVIDENCE_WRITE_FAILURES: list[dict[str, str]] = []


class Refused(RuntimeError):
    """A public reason code, without underlying connection or command details."""

    def __init__(self, code: str):
        self.code = code
        super().__init__(code)


def now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat()


def digest(value) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(',', ':'), default=str).encode()
    return hashlib.sha256(encoded).hexdigest()


def evidence_write_failed(path: Path, failure: BaseException) -> None:
    EVIDENCE_WRITE_FAILURES.append({'file': path.name, 'failure_type': type(failure).__name__})


def write_json(path: Path, value) -> dict:
    """Atomically persist, fsync and verify bytes; a write failure latches cleanup off."""
    try:
        absolute, resolved = path.absolute(), path.resolve()
        if absolute != resolved or not resolved.is_relative_to((ROOT / '.local').resolve()):
            raise Refused('OUTPUT_PATH_CHANGED_OR_ESCAPED')
        payload = (json.dumps(value, indent=2, ensure_ascii=False, default=str) + '\n').encode('utf-8')
        expected = hashlib.sha256(payload).hexdigest()
        temporary = path.with_name(path.name + '.tmp-' + uuid.uuid4().hex)
        with temporary.open('xb') as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        # Flush the final named file after replacement as well as its initial temporary inode.
        with path.open('r+b') as stream:
            actual = stream.read()
            os.fsync(stream.fileno())
            opened = os.fstat(stream.fileno())
            named = path.stat()
            if (opened.st_dev, opened.st_ino) != (named.st_dev, named.st_ino):
                raise Refused('EVIDENCE_FILE_IDENTITY_CHANGED')
        if actual != payload or hashlib.sha256(actual).hexdigest() != expected or json.loads(actual) != json.loads(payload):
            raise Refused('EVIDENCE_READBACK_MISMATCH')
        if os.name != 'nt':
            directory = os.open(path.parent, os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0))
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        return {'file': path.name, 'sha256': expected, 'bytes': len(payload), 'fsynced_and_readback_verified': True}
    except BaseException as failure:
        evidence_write_failed(path, failure)
        raise


@contextlib.contextmanager
def open_evidence_log(path: Path):
    try:
        stream = path.open('w', encoding='utf-8')
    except BaseException as failure:
        evidence_write_failed(path, failure)
        raise
    try:
        yield stream
    finally:
        try:
            stream.flush()
            os.fsync(stream.fileno())
            stream.close()
        except BaseException as failure:
            evidence_write_failed(path, failure)
            raise


def load_module(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'tools' / filename)
    if spec is None or spec.loader is None:
        raise Refused('LOCAL_HELPER_UNAVAILABLE')
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        if sys.modules.get(name) is module:
            del sys.modules[name]
        raise
    return module


class OwnershipLocks:
    def __init__(self, owner: str, session: str, run_id: str):
        self.owner, self.session = owner, session
        self.migration = COORDINATION / 'migration.lock'
        self.environment = COORDINATION / 'environment.lock'
        self.migration_bytes: bytes | None = None
        self.migration_identity: tuple[int, int] | None = None
        self.environment_identity: tuple[int, int] | None = None
        self.held = False
        self.document = {'owner': run_id, 'session': session, 'operation': 'candidate-schema-chain',
                         'migration_owner': owner, 'started_at': now(), 'pid': os.getpid()}

    @staticmethod
    def identity(path: Path) -> tuple[int, int]:
        stat = path.stat()
        return stat.st_dev, stat.st_ino

    def check_migration(self) -> None:
        if self.migration.is_symlink():
            raise Refused('MIGRATION_LOCK_SYMLINK_REFUSED')
        raw = self.migration.read_bytes()
        value = json.loads(raw.decode('utf-8-sig'))
        if not isinstance(value, dict) or value.get('owner') != self.owner or value.get('session') != self.session:
            raise Refused('MIGRATION_LOCK_OWNER_OR_SESSION_MISMATCH')
        identity = self.identity(self.migration)
        if self.migration_bytes is None:
            self.migration_bytes, self.migration_identity = raw, identity
        elif raw != self.migration_bytes or identity != self.migration_identity:
            raise Refused('MIGRATION_LOCK_CHANGED')

    def acquire(self) -> None:
        self.check_migration()
        with self.environment.open('x', encoding='utf-8') as stream:
            self.held = True
            self.environment_identity = self.identity(self.environment)
            json.dump(self.document, stream)
            stream.flush()
            os.fsync(stream.fileno())
        self.check()

    def check(self) -> None:
        self.check_migration()
        if not self.held or self.environment.is_symlink() or self.identity(self.environment) != self.environment_identity:
            raise Refused('ENVIRONMENT_LOCK_OWNERSHIP_LOST')
        if json.loads(self.environment.read_text(encoding='utf-8')) != self.document:
            raise Refused('ENVIRONMENT_LOCK_CHANGED')

    def release(self) -> bool:
        if not self.held:
            return False
        if self.environment.is_symlink() or not self.environment.exists():
            return False
        if self.identity(self.environment) != self.environment_identity:
            return False
        if json.loads(self.environment.read_text(encoding='utf-8')) != self.document:
            return False
        self.environment.unlink()
        self.held = False
        return True


def reserve_output(requested: Path) -> Path:
    local = (ROOT / '.local').resolve()
    if not local.is_relative_to(ROOT.resolve()):
        raise Refused('LOCAL_OUTPUT_ROOT_ESCAPES_CHECKOUT')
    path = requested.resolve()
    if path == local or not path.is_relative_to(local):
        raise Refused('OUTPUT_MUST_BE_INSIDE_CHECKOUT_LOCAL')
    path.mkdir(parents=True, exist_ok=False)
    return path


def migration_manifest() -> dict[str, dict]:
    result = {}
    directory = ROOT / 'server/persistence/src/main/resources/db/migration'
    for version in range(1, 56):
        matches = list(directory.glob(f'V{version}__*.sql'))
        if len(matches) != 1:
            raise Refused('AMBIGUOUS_OR_MISSING_MIGRATION_RESOURCE')
        path = matches[0]
        result[str(version)] = {'script': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    if result['51']['sha256'] != V51_SHA256:
        raise Refused('FROZEN_V51_RESOURCE_CHANGED')
    return result


def check_manifest(expected: dict) -> None:
    if migration_manifest() != expected:
        raise Refused('MIGRATION_RESOURCES_CHANGED_DURING_RUN')


def class_overrides(values: list[str]) -> dict[str, str]:
    classes = {stage: 'CandidateSchemaChainPostgresTest' for stage, _, _ in STAGES}
    seen = set()
    for value in values:
        stage, separator, name = value.partition('=')
        if not separator or stage not in classes or stage in seen or not re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*', name):
            raise Refused('INVALID_TEST_CLASS_OVERRIDE')
        seen.add(stage)
        classes[stage] = name
    return classes


def source_test_exists(name: str) -> bool:
    return len(list((ROOT / 'server/persistence/src/test/java').rglob(name + '.java'))) == 1


def safe_secret(value) -> str:
    if not isinstance(value, str) or not value or any(character in value for character in ('\x00', '\r', '\n')):
        raise Refused('PRIVATE_CONNECTION_VALUE_INVALID')
    return value


class DesignatedPostgres:
    def __init__(self, locks: OwnershipLocks):
        # Imports and private configuration are deliberately deferred until run() owns both guards.
        import paramiko
        import psycopg
        from psycopg import sql
        self.paramiko, self.psycopg, self.sql = paramiko, psycopg, sql
        self.locks = locks
        self.ssh = paramiko.SSHClient()
        self.tunnel = None
        self.settings = None
        self.runtime = None
        self.cfg = None

    def open(self) -> None:
        self.locks.check()
        cfg = json.loads((PRIVATE / 'lan-test-connection.json').read_text(encoding='utf-8-sig'))
        runtime = json.loads((PRIVATE / 'lan-test-runtime.json').read_text(encoding='utf-8-sig'))
        if cfg.get('environment') != ENVIRONMENT or cfg.get('host') != HOST:
            raise Refused('DESIGNATED_DESTINATION_MISMATCH')
        for key in ('postgres_admin_password', 'runtime_password', 'migrator_password'):
            safe_secret(runtime.get(key))
        safe_secret(cfg.get('username'))
        safe_secret(cfg.get('password'))
        self.cfg, self.runtime = cfg, runtime
        self.ssh.load_host_keys(str(PRIVATE / 'lan-test-known_hosts'))
        self.ssh.set_missing_host_key_policy(self.paramiko.RejectPolicy())
        self.ssh.connect(HOST, username=cfg['username'], password=cfg['password'], look_for_keys=False,
                         allow_agent=False, timeout=15, banner_timeout=15, auth_timeout=15)
        transport = self.ssh.get_transport()
        if transport is None or not transport.is_active():
            raise Refused('SSH_TRANSPORT_NOT_READY')
        fingerprint = base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip('=')
        if fingerprint != cfg.get('expected_host_key_sha256'):
            raise Refused('PINNED_HOST_KEY_MISMATCH')
        owner = json.loads(self.remote(['cat', '/opt/mop-lan-test/owner.json'], max_bytes=65536))
        if owner.get('project') != 'mop-lan-test' or owner.get('environment') != ENVIRONMENT:
            raise Refused('REMOTE_PROJECT_OWNERSHIP_MISMATCH')
        transport.set_keepalive(15)
        module = load_module('candidate_chain_tunnel', 'wecom-dev-tunnel.py')
        self.tunnel = module.Server(('127.0.0.1', 0), module.handler(transport, False))
        threading.Thread(target=self.tunnel.serve_forever, daemon=True).start()
        self.settings = {'host': '127.0.0.1', 'port': self.tunnel.server_address[1], 'user': 'postgres',
                         'password': runtime['postgres_admin_password'], 'connect_timeout': 10, 'autocommit': True}
        with self.connection('postgres', readonly=True) as connection, connection.cursor() as cursor:
            cursor.execute('SELECT current_user,session_user')
            if cursor.fetchone() != ('postgres', 'postgres'):
                raise Refused('ADMIN_CONNECTION_IDENTITY_MISMATCH')
            cursor.execute('SELECT rolname,rolsuper,rolbypassrls,rolcreaterole,rolcreatedb,rolreplication '
                           'FROM pg_roles WHERE rolname IN (%s,%s) ORDER BY rolname', (MIGRATOR, RUNTIME))
            roles = cursor.fetchall()
            if len(roles) != 2 or {row[0] for row in roles} != {MIGRATOR, RUNTIME} or any(any(row[1:]) for row in roles):
                raise Refused('EXISTING_PROJECT_ROLES_NOT_RESTRICTED')
            cursor.execute("SELECT pg_has_role(%s,%s,'MEMBER')", (RUNTIME, MIGRATOR))
            if cursor.fetchone()[0]:
                raise Refused('RUNTIME_MIGRATOR_MEMBERSHIP_REFUSED')

    def connection(self, database: str, *, readonly: bool):
        if self.settings is None:
            raise Refused('PG_TUNNEL_NOT_READY')
        connection = self.psycopg.connect(dbname=database, **self.settings,
            options='-c default_transaction_read_only=' + ('on' if readonly else 'off')
                    + ' -c timezone=UTC -c statement_timeout=60000 -c lock_timeout=5000',
            application_name='mop-candidate-schema-chain')
        try:
            with connection.cursor() as cursor:
                cursor.execute('SELECT current_database(),current_user,session_user,current_setting(\'default_transaction_read_only\')')
                if cursor.fetchone() != (database, 'postgres', 'postgres', 'on' if readonly else 'off'):
                    raise Refused('PG_CONNECTION_SCOPE_MISMATCH')
            return connection
        except BaseException:
            connection.close()
            raise

    def remote(self, arguments: list[str], *, privileged=False, max_bytes=64 * 1024 * 1024) -> str:
        command = shlex.join(arguments)
        if privileged:
            command = "sudo -S -p '' -- " + command
        stdin, stdout, _stderr = self.ssh.exec_command(command, timeout=120)
        if privileged:
            stdin.write(self.cfg['password'] + '\n')
            stdin.flush()
        stdin.channel.shutdown_write()
        channel = stdout.channel
        data = bytearray()
        deadline = time.monotonic() + 120
        try:
            while True:
                if channel.recv_ready():
                    data.extend(channel.recv(65536))
                    if len(data) > max_bytes:
                        raise Refused('REMOTE_SCHEMA_OUTPUT_LIMIT_EXCEEDED')
                if channel.recv_stderr_ready():
                    channel.recv_stderr(65536)  # Never log remote diagnostic strings or private input.
                if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
                    break
                if time.monotonic() > deadline:
                    raise Refused('REMOTE_READ_TIMEOUT')
                time.sleep(0.01)
            if channel.recv_exit_status() != 0:
                raise Refused('REMOTE_READ_FAILED')
            return data.decode('utf-8')
        finally:
            channel.close()

    def assert_owned(self, connection, owned: dict) -> None:
        if not owned.get('created') or not re.fullmatch(re.escape(owned['prefix']) + r'[a-f0-9]{12}', owned['database']):
            raise Refused('UNOWNED_DATABASE_OPERATION_REFUSED')
        with connection.cursor() as cursor:
            cursor.execute("SELECT pg_get_userbyid(datdba),shobj_description(oid,'pg_database') "
                           'FROM pg_database WHERE datname=%s', (owned['database'],))
            if cursor.fetchone() != (MIGRATOR, owned['owner_marker']):
                raise Refused('DATABASE_OWNER_OR_MARKER_MISMATCH')

    def dump(self, database: str, owned: dict | None) -> str:
        self.locks.check()
        with self.connection(database, readonly=True) as connection:
            if owned is not None:
                self.assert_owned(connection, owned)
            elif database != 'mop_test':
                raise Refused('SCHEMA_SOURCE_NOT_ACTIVE_OR_OWNED')
        dump = self.remote(['docker', 'exec', 'mop-lan-postgres', 'pg_dump', '-U', 'postgres',
                            '--dbname', database, '--schema-only', '--schema=public'], privileged=True)
        helper = load_module('candidate_schema_text', 'postgres_schema_text.py')
        try:
            return helper.filter_schema_dump(dump)
        except helper.SchemaTextError as failure:
            raise Refused(str(failure)) from None

    def close(self) -> None:
        if self.tunnel is not None:
            self.tunnel.shutdown()
            self.tunnel.server_close()
        self.ssh.close()


def metadata(connection, expected_version: int | None) -> tuple[list, list]:
    with connection.cursor() as cursor:
        cursor.execute('SELECT ' + ','.join(HISTORY_COLUMNS) + ' FROM public.flyway_schema_history ORDER BY installed_rank')
        history = cursor.fetchall()
        if expected_version is not None and (len(history) != expected_version or [row[1] for row in history] != [str(version) for version in range(1, expected_version + 1)] or not all(row[-1] for row in history)):
            raise Refused('MIGRATION_BASELINE_NOT_EXACT')
        cursor.execute('SELECT base_role,capability FROM public.mop_role_capability_limit ORDER BY base_role,capability')
        capabilities = cursor.fetchall()
        if not capabilities:
            raise Refused('CAPABILITY_DICTIONARY_MISSING')
    return history, capabilities


def active_snapshot(pg: DesignatedPostgres, fingerprint_module) -> dict:
    with pg.connection('mop_test', readonly=True) as connection:
        history, capabilities = metadata(connection, None)
        with connection.cursor() as cursor:
            cursor.execute('SHOW server_version')
            version = cursor.fetchone()[0]
            cursor.execute('SELECT to_regclass(name) IS NULL FROM unnest(%s::text[]) AS name',
                           (['public.mop_notification_control', 'public.mop_attachment_security_revision',
                             'public.mop_report_publication_document', 'public.mop_monitoring_enrollment',
                             'public.mop_monitoring_object_binding'],))
            absent = all(row[0] for row in cursor.fetchall())
        exact_v50 = len(history) == 50 and [row[1] for row in history] == [str(value) for value in range(1, 51)] and all(row[-1] for row in history)
        return {'history': history, 'history_sha256': digest(history), 'capabilities': capabilities,
                'capabilities_sha256': digest(capabilities), 'postgresql_version': version,
                'protected_fingerprints': fingerprint_module.protected_fingerprints(connection),
                'candidate_objects_absent': absent, 'exact_v50': exact_v50,
                'latest_version': history[-1][1] if history else None}


def ensure_empty_business_tables(pg: DesignatedPostgres, connection) -> int:
    with connection.cursor() as cursor:
        cursor.execute("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                       "WHERE n.nspname='public' AND c.relkind IN ('r','p') ORDER BY c.relname")
        tables = [row[0] for row in cursor.fetchall() if row[0] not in METADATA_TABLES]
        for table in tables:
            cursor.execute(pg.sql.SQL('SELECT EXISTS(SELECT 1 FROM public.{} LIMIT 1)').format(pg.sql.Identifier(table)))
            if cursor.fetchone()[0]:
                raise Refused('SCHEMA_CLONE_CONTAINS_BUSINESS_ROWS')
    return len(tables)


def create_clone(pg: DesignatedPostgres, stage: dict, predecessor: dict | None, base_version: int) -> None:
    helper = load_module('candidate_schema_text', 'postgres_schema_text.py')
    source_name = predecessor['database'] if predecessor else 'mop_test'
    with pg.connection(source_name, readonly=True) as source:
        if predecessor:
            pg.assert_owned(source, predecessor)
        history, capabilities = metadata(source, base_version)
        source_literals = helper.catalog_literal_fingerprints(source)
    schema = pg.dump(source_name, predecessor)
    with pg.connection(source_name, readonly=True) as source:
        if metadata(source, base_version) != (history, capabilities) or helper.catalog_literal_fingerprints(source) != source_literals:
            raise Refused('CLONE_SOURCE_METADATA_OR_LITERALS_CHANGED')
    stage.update(schema_source=source_name, inherited_versions=base_version, inherited_history_sha256=digest(history),
                 inherited_history_reexecuted=False, schema_only_sha256=hashlib.sha256(schema.encode()).hexdigest(),
                 metadata_tables_copied=sorted(METADATA_TABLES), business_rows_copied=0)
    pg.locks.check()
    with pg.connection('postgres', readonly=False) as admin, admin.cursor() as cursor:
        cursor.execute(pg.sql.SQL('CREATE DATABASE {} OWNER {} TEMPLATE template0').format(
            pg.sql.Identifier(stage['database']), pg.sql.Identifier(MIGRATOR)))
        stage['created'] = True
        cursor.execute(pg.sql.SQL('COMMENT ON DATABASE {} IS {}').format(pg.sql.Identifier(stage['database']), pg.sql.Literal(stage['owner_marker'])))
        pg.assert_owned(admin, stage)
        cursor.execute(pg.sql.SQL('REVOKE ALL PRIVILEGES ON DATABASE {} FROM PUBLIC').format(pg.sql.Identifier(stage['database'])))
        cursor.execute(pg.sql.SQL('GRANT CONNECT ON DATABASE {} TO {},{}').format(
            pg.sql.Identifier(stage['database']), pg.sql.Identifier(RUNTIME), pg.sql.Identifier(MIGRATOR)))
        cursor.execute("SELECT acl.grantee,pg_get_userbyid(acl.grantee) FROM pg_database d "
                       "CROSS JOIN LATERAL aclexplode(COALESCE(d.datacl,acldefault('d',d.datdba))) acl "
                       "WHERE d.datname=%s AND acl.privilege_type='CONNECT'", (stage['database'],))
        connect_grants = cursor.fetchall()
        if any(grantee == 0 or role not in (MIGRATOR, RUNTIME) for grantee, role in connect_grants) \
                or {role for _grantee, role in connect_grants} != {MIGRATOR, RUNTIME}:
            raise Refused('ISOLATED_DATABASE_CONNECT_ACL_MISMATCH')
    pg.locks.check()
    with pg.connection(stage['database'], readonly=False) as target:
        pg.assert_owned(target, stage)
        with target.transaction(), target.cursor() as cursor:
            cursor.execute("SELECT EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='public')")
            if cursor.fetchone()[0]:
                cursor.execute("SELECT (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public') "
                               "+ (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public') "
                               "+ (SELECT count(*) FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public')")
                if cursor.fetchone()[0] != 0:
                    raise Refused('NEW_DATABASE_PUBLIC_SCHEMA_NOT_EMPTY')
            else:
                cursor.execute('CREATE SCHEMA public AUTHORIZATION mop_test_migrator')
            # template0 may already provide an empty public schema. Never DROP it or any business objects.
            try:
                restored_schema = helper.reuse_empty_public_schema(schema)
            except helper.SchemaTextError as failure:
                raise Refused(str(failure)) from None
            stage['empty_public_schema_reused_without_drop'] = True
            stage['restored_schema_sha256'] = hashlib.sha256(restored_schema.encode()).hexdigest()
            cursor.execute(restored_schema, prepare=False)
            cursor.executemany('INSERT INTO public.flyway_schema_history(' + ','.join(HISTORY_COLUMNS)
                               + ') VALUES(' + ','.join(['%s'] * len(HISTORY_COLUMNS)) + ')', history)
            cursor.executemany('INSERT INTO public.mop_role_capability_limit(base_role,capability) VALUES(%s,%s)', capabilities)
        if metadata(target, base_version) != (history, capabilities):
            raise Refused('CLONED_METADATA_NOT_IDENTICAL')
        restored_literals = helper.catalog_literal_fingerprints(target)
        stage['source_literal_catalogue_sha256'] = digest(source_literals)
        stage['restored_literal_catalogue_sha256'] = digest(restored_literals)
        stage['literal_catalogue_scope'] = 'EXPRESSION_LITERAL_VALUES_AND_EXACT_FUNCTION_SOURCE_NOT_FULL_RUNTIME_SCHEMA_APPROVAL'
        if restored_literals != source_literals:
            raise Refused('CLONED_LITERAL_OR_FUNCTION_SOURCE_CHANGED')
        stage['literal_and_function_source_preserved'] = True
        stage['empty_business_tables_verified'] = ensure_empty_business_tables(pg, target)
    stage['clone_status'] = 'PASSED'


def secret_masks(pg: DesignatedPostgres) -> list[str]:
    masks = set()
    for value in (pg.cfg['password'], pg.runtime['postgres_admin_password'], pg.runtime['runtime_password'], pg.runtime['migrator_password']):
        masks.update((value, urllib.parse.quote(value, safe=''), urllib.parse.quote_plus(value),
                      json.dumps(value)[1:-1], base64.b64encode(value.encode()).decode()))
    return sorted((value for value in masks if value), key=len, reverse=True)


def maven_test(pg: DesignatedPostgres, stage: dict, folder: Path, test_class: str, timeout: int) -> None:
    pg.locks.check()
    environment = os.environ.copy()
    # Do not inherit unrelated integration-test switches, connection destinations or JVM logging flags.
    for key in list(environment):
        if key.startswith(('MOP_', 'SPRING_DATASOURCE_', 'PG')) or key in ('MAVEN_OPTS', 'MAVEN_ARGS', 'MAVEN_DEBUG_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'):
            environment.pop(key, None)
    environment.update({
        'MOP_RUN_CANDIDATE_SCHEMA_CHAIN_PG_TESTS': 'true', 'MOP_CANDIDATE_STAGE': stage['stage'],
        'MOP_CANDIDATE_VERSIONS': ','.join(map(str, stage['versions'])),
        'MOP_CANDIDATE_JDBC_URL': f"jdbc:postgresql://127.0.0.1:{pg.settings['port']}/{stage['database']}",
        'MOP_CANDIDATE_DATABASE': stage['database'], 'MOP_CANDIDATE_OWNER_MARKER': stage['owner_marker'],
        'MOP_CANDIDATE_RUNTIME_PASSWORD': pg.runtime['runtime_password'],
        'MOP_CANDIDATE_MIGRATOR_PASSWORD': pg.runtime['migrator_password'],
        'MOP_CANDIDATE_REPOSITORY_ROOT': str(ROOT), 'MOP_CANDIDATE_EVIDENCE_PATH': str(folder / 'java-evidence.json'),
    })
    command = ['mvn.cmd' if os.name == 'nt' else 'mvn', '-B', '-pl', 'server/persistence', '-am', 'test',
               '-Dtest=' + test_class, '-Dsurefire.failIfNoSpecifiedTests=false']
    stage.update(test_class=test_class, test_module='server/persistence', java_launch_requested=True,
                 java_started=False, java_process_completed=False)
    started_ns = time.time_ns()
    masks = secret_masks(pg)
    with open_evidence_log(folder / 'maven.log') as log:
        process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8', errors='replace')
        stage['java_started'] = True
        def consume():
            try:
                for line in process.stdout:
                    for secret in masks:
                        line = line.replace(secret, '[REDACTED]')
                    log.write(line)
                    log.flush()
            except (OSError, ValueError) as failure:
                stage['log_reader_interrupted'] = True
                evidence_write_failed(folder / 'maven.log', failure)
        reader = threading.Thread(target=consume, daemon=True)
        reader.start()
        try:
            stage['maven_exit_code'] = process.wait(timeout=timeout)
            stage['java_process_completed'] = True
        except subprocess.TimeoutExpired:
            stage['test_timeout'] = True
            stage['java_child_processes_may_remain'] = True
            stage['required_action'] = 'Inspect this run JVM and retained database; lock release does not prove JVM termination'
            # Stop only the child launcher, never terminate PG sessions or force-drop its database.
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                stage['launcher_still_running'] = True
            raise Refused('OWN_TEST_PROCESS_TIMEOUT_DATABASE_RETAINED')
        finally:
            reader.join(timeout=15)
            if reader.is_alive():
                stage['log_reader_incomplete'] = True
                stage['java_process_completed'] = False
                stage['java_child_processes_may_remain'] = True
                raise Refused('OWN_TEST_OUTPUT_STILL_OPEN_DATABASE_RETAINED')
            if stage.get('log_reader_interrupted'):
                raise Refused('OWN_TEST_LOG_INCOMPLETE')
    reports = list((ROOT / 'server/persistence/target/surefire-reports').glob('TEST-*' + test_class + '.xml'))
    if len(reports) != 1 or reports[0].stat().st_mtime_ns < started_ns - 2_000_000_000:
        raise Refused('FRESH_SELECTED_TEST_REPORT_MISSING')
    report = ET.parse(reports[0]).getroot()
    stage['junit'] = {key: int(report.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')}
    if stage['maven_exit_code'] or stage['junit']['tests'] < 1 or any(stage['junit'][key] for key in ('failures', 'errors', 'skipped')):
        raise Refused('SELECTED_JAVA_TEST_NOT_FULLY_PASSED')


def observe_stage_history(pg: DesignatedPostgres, stage: dict, manifest: dict) -> None:
    with pg.connection(stage['database'], readonly=True) as connection:
        pg.assert_owned(connection, stage)
        with connection.cursor() as cursor:
            cursor.execute('SELECT ' + ','.join(HISTORY_COLUMNS) + ' FROM public.flyway_schema_history ORDER BY installed_rank')
            history = cursor.fetchall()
        stage['observed_history_sha256'] = digest(history)
        stage['observed_history_rows'] = len(history)
        stage['observed_candidates'] = [
            {'version': row[1], 'script': row[4], 'flyway_checksum': row[5], 'installed_at': str(row[7]),
             'success': row[9], 'resource_sha256': manifest[str(row[1])]['sha256']}
            for row in history if row[1] in {str(version) for version in stage['versions']}
        ]
        stage['freeze_required_versions'] = [item['version'] for item in stage['observed_candidates'] if item['success']]
        expected_total = max(stage['versions'])
        checked, _capabilities = metadata(connection, expected_total)
        if digest(checked[:stage['inherited_versions']]) != stage['inherited_history_sha256']:
            raise Refused('INHERITED_MIGRATION_HISTORY_CHANGED')
        for row in checked:
            if row[4] != manifest[str(row[1])]['script']:
                raise Refused('MIGRATION_HISTORY_RESOURCE_MISMATCH')


def validate_java_evidence(stage: dict, folder: Path, manifest: dict) -> None:
    path = folder / 'java-evidence.json'
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 1024 * 1024:
        raise Refused('JAVA_EVIDENCE_MISSING_OR_UNBOUNDED')
    value = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(value, dict) or type(value.get('schema_version')) is not int or value.get('schema_version') != 1 or value.get('status') != 'PASSED' or value.get('stage') != stage['stage'] \
            or value.get('database') != stage['database'] or value.get('owner_marker') != stage['owner_marker'] \
            or value.get('pools_closed') is not True:
        raise Refused('JAVA_EVIDENCE_SCOPE_OR_CLOSE_MISMATCH')
    migrations = value.get('migrations')
    if not isinstance(migrations, list) or len(migrations) != len(stage['versions']):
        raise Refused('JAVA_MIGRATION_EVIDENCE_INCOMPLETE')
    actual = {item['version']: item for item in stage['observed_candidates']}
    for version, item in zip(stage['versions'], migrations):
        if not isinstance(item, dict) or str(item.get('version')) != str(version) or type(item.get('apply_executed')) is not int \
                or item.get('apply_executed') != 1 or type(item.get('repeat_executed')) is not int or item.get('repeat_executed') != 0 \
                or item.get('sha256') != manifest[str(version)]['sha256'] or type(item.get('flyway_checksum')) is not int \
                or item.get('flyway_checksum') != actual.get(str(version), {}).get('flyway_checksum'):
            raise Refused('JAVA_APPLY_REPEAT_OR_HASH_EVIDENCE_MISMATCH')
        if item.get('history_status') != 'APPLIED_FROZEN' or item.get('history_success') is not True \
                or item.get('history_script') != manifest[str(version)]['script'] or not isinstance(item.get('installed_on'), str):
            raise Refused('JAVA_FROZEN_HISTORY_EVIDENCE_INCOMPLETE')
        if dt.datetime.fromisoformat(item['installed_on']) != dt.datetime.fromisoformat(actual[str(version)]['installed_at']):
            raise Refused('JAVA_HISTORY_INSTALL_TIME_MISMATCH')
    business = value.get('business_validation')
    if not isinstance(business, dict) or business.get('status') != 'NOT_RUN' or type(business.get('test_count')) is not int or business.get('test_count') != 0 \
            or not isinstance(business.get('limitations'), list) or not business['limitations']:
        raise Refused('SCHEMA_CHECKS_MUST_NOT_CLAIM_BUSINESS_ACCEPTANCE')
    schema = value.get('schema_validation')
    if not isinstance(schema, dict) or schema.get('status') != 'PASSED':
        raise Refused('JAVA_SCHEMA_VALIDATION_NOT_PASSED')
    stage.update(java_evidence=path.name, pools_closed=True, migrations=migrations,
                 schema_validation=schema, business_validation=business)


def capture_java_failure_evidence(stage: dict, folder: Path) -> None:
    """Retain successful first applications even when a later Java assertion fails."""
    path = folder / 'java-evidence.json'
    if not path.is_file() or path.is_symlink() or path.stat().st_size > 1024 * 1024:
        stage['failure_java_evidence'] = 'NOT_AVAILABLE'
        return
    value = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(value, dict) or value.get('stage') != stage['stage'] or value.get('database') != stage['database'] \
            or value.get('owner_marker') != stage['owner_marker']:
        stage['failure_java_evidence'] = 'SCOPE_REJECTED'
        return
    stage['failure_java_evidence'] = path.name
    stage['failure_java_pools_closed'] = value.get('pools_closed') is True
    for key in ('failed_phase', 'failure_type', 'sql_state'):
        if isinstance(value.get(key), str) and re.fullmatch(r'[A-Za-z0-9_]{1,128}', value[key]):
            stage['java_' + key] = value[key]
    reported = []
    for item in value.get('migrations', []) if isinstance(value.get('migrations'), list) else []:
        if not isinstance(item, dict) or str(item.get('version')) not in {str(version) for version in stage['versions']}:
            continue
        safe = {'version': str(item['version'])}
        for key in ('apply_executed', 'repeat_executed', 'flyway_checksum'):
            if type(item.get(key)) is int:
                safe[key] = item[key]
        if isinstance(item.get('sha256'), str) and re.fullmatch('[0-9a-f]{64}', item['sha256']):
            safe['sha256'] = item['sha256']
        if item.get('history_status') in ('APPLIED_FROZEN', 'NOT_APPLIED', 'UNKNOWN_HISTORY_PROBE_FAILED', 'NOT_PROBED', 'FAILED_HISTORY_ROW'):
            safe['history_status'] = item['history_status']
        if type(item.get('history_success')) is bool:
            safe['history_success'] = item['history_success']
        if isinstance(item.get('history_script'), str) and re.fullmatch(r'V[0-9]+__[A-Za-z0-9_]+\.sql', item['history_script']):
            safe['history_script'] = item['history_script']
        if isinstance(item.get('installed_on'), str) and re.fullmatch(r'[0-9]{4}-[0-9]{2}-[0-9]{2}[ T][0-9:.+ Z-]{1,80}', item['installed_on']):
            safe['installed_on'] = item['installed_on']
        reported.append(safe)
    stage['migrations_reported_before_failure'] = reported
    stage['freeze_required_reported_versions'] = [item['version'] for item in reported if item.get('history_status') == 'APPLIED_FROZEN']


def capture_cleanup_history(pg: DesignatedPostgres, stage: dict, folder: Path, manifest: dict) -> dict:
    """A fresh database observation, not Java memory, authorizes evidence-preserving cleanup."""
    evidence = {'schema_version': 1, 'stage': stage['stage'], 'database': stage['database'],
                'database_owner': MIGRATOR, 'owner_marker': stage['owner_marker'], 'observed_at': now(),
                'source': 'FINAL_READ_ONLY_DATABASE_HISTORY', 'migrations': [], 'status': 'UNKNOWN',
                'business_validation': 'NOT_RUN', 'history_determinate_for_cleanup': False,
                'history_gate_is_not_user_cleanup_authorization': True}
    for version in stage['versions']:
        resource = manifest.get(str(version), {})
        evidence['migrations'].append({'version': str(version), 'history_status': 'UNKNOWN_NOT_READ',
            'resource_sha256': resource.get('sha256'), 'resource_script': resource.get('script'),
            'resource_hash_basis': 'PRE_RUN_PINNED_WORKSPACE_RESOURCE', 'history_rows': []})
    try:
        pg.locks.check()
        with pg.connection(stage['database'], readonly=True) as connection:
            pg.assert_owned(connection, stage)
            with connection.transaction(), connection.cursor() as cursor:
                cursor.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY')
                cursor.execute("SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")
                if not cursor.fetchone()[0]:
                    raise Refused('CLEANUP_HISTORY_TABLE_MISSING')
                for item in evidence['migrations']:
                    cursor.execute('SELECT ' + ','.join(HISTORY_COLUMNS)
                                   + ' FROM public.flyway_schema_history WHERE version=%s ORDER BY installed_rank',
                                   (item['version'],))
                    rows = cursor.fetchall()
                    item['history_rows'] = [dict(zip(HISTORY_COLUMNS, row)) for row in rows]
                    if not rows:
                        item['history_status'] = 'NOT_APPLIED'
                    elif len(rows) == 1 and rows[0][9] is True and rows[0][3] == 'SQL' \
                            and rows[0][4] == item['resource_script'] and type(rows[0][5]) is int \
                            and rows[0][7] is not None and isinstance(item['resource_sha256'], str) \
                            and re.fullmatch('[0-9a-f]{64}', item['resource_sha256']):
                        item['history_status'] = 'APPLIED_FROZEN'
                    else:
                        item['history_status'] = 'UNKNOWN_AMBIGUOUS_OR_FAILED_HISTORY'
    except Exception as failure:
        evidence['read_failure_type'] = type(failure).__name__
        evidence['read_failure_code'] = getattr(failure, 'code', 'PRIVATE_DETAILS_WITHHELD')
    determinate = 'read_failure_type' not in evidence and len(evidence['migrations']) == len(stage['versions']) and all(
        item['history_status'] in ('APPLIED_FROZEN', 'NOT_APPLIED') for item in evidence['migrations'])
    evidence['status'] = 'DETERMINATE' if determinate else 'UNKNOWN_RETAIN_DATABASE'
    evidence['freeze_required_versions'] = [item['version'] for item in evidence['migrations'] if item['history_status'] == 'APPLIED_FROZEN']
    evidence['history_determinate_for_cleanup'] = determinate
    receipt = write_json(folder / 'cleanup-history-evidence.json', evidence)
    stage.update(cleanup_history_evidence=receipt,
                 cleanup_history_status=evidence['status'],
                 cleanup_freeze_required_versions=evidence['freeze_required_versions'])
    if not determinate:
        raise Refused('CLEANUP_HISTORY_UNKNOWN_DATABASE_RETAINED')
    return receipt


def verify_cleanup_history_file(stage: dict, folder: Path, receipt: dict) -> None:
    path = folder / 'cleanup-history-evidence.json'
    if path.absolute() != path.resolve() or not path.is_file() or path.stat().st_size != receipt['bytes']:
        raise Refused('CLEANUP_HISTORY_DURABLE_FILE_CHANGED')
    payload = path.read_bytes()
    if hashlib.sha256(payload).hexdigest() != receipt['sha256']:
        raise Refused('CLEANUP_HISTORY_DURABLE_DIGEST_CHANGED')
    value = json.loads(payload)
    if value.get('database') != stage['database'] or value.get('owner_marker') != stage['owner_marker'] \
            or value.get('stage') != stage['stage'] or value.get('status') != 'DETERMINATE' \
            or value.get('history_determinate_for_cleanup') is not True \
            or [item.get('version') for item in value.get('migrations', [])] != [str(version) for version in stage['versions']] \
            or any(item.get('history_status') not in ('APPLIED_FROZEN', 'NOT_APPLIED') for item in value.get('migrations', [])):
        raise Refused('CLEANUP_HISTORY_DURABLE_CONTENT_REJECTED')


def cleanup_owned(pg: DesignatedPostgres, stages: list[dict], output: Path, manifest: dict | None) -> bool:
    clean = True
    for stage in reversed(stages):
        if not stage.get('created'):
            stage['cleanup'] = 'NOT_CREATED'
            continue
        dropped = False
        try:
            pg.locks.check()
            if EVIDENCE_WRITE_FAILURES:
                raise Refused('EVIDENCE_PERSISTENCE_FAILED_DATABASE_RETAINED')
            if manifest is None or any(str(version) not in manifest for version in stage['versions']):
                raise Refused('CLEANUP_RESOURCE_MANIFEST_MISSING')
            if stage.get('java_started'):
                if not stage.get('java_process_completed'):
                    raise Refused('OWN_JAVA_LIFECYCLE_UNCONFIRMED')
                if not stage.get('pools_closed') and not stage.get('failure_java_pools_closed'):
                    raise Refused('OWN_JAVA_CONNECTION_CLOSE_EVIDENCE_MISSING')
            if stage.get('log_reader_interrupted') or stage.get('log_reader_incomplete'):
                raise Refused('OWN_TEST_LOG_INCOMPLETE_DATABASE_RETAINED')
            folder = output / stage['stage']
            with pg.connection('postgres', readonly=False) as admin:
                pg.assert_owned(admin, stage)
                with admin.cursor() as cursor:
                    cursor.execute('SELECT count(*) FROM pg_stat_activity WHERE datname=%s', (stage['database'],))
                    if cursor.fetchone()[0] != 0:
                        raise Refused('DATABASE_HAS_OPEN_CONNECTIONS_NO_FORCE_CLEANUP')
                # The final source read closes its own read-only connection before the DROP gate.
                receipt = capture_cleanup_history(pg, stage, folder, manifest)
                stage['cleanup'] = 'DURABLE_HISTORY_VERIFIED_PENDING_DROP'
                write_json(folder / 'stage-summary.json', stage)
                if EVIDENCE_WRITE_FAILURES:
                    raise Refused('EVIDENCE_PERSISTENCE_FAILED_DATABASE_RETAINED')
                verify_cleanup_history_file(stage, folder, receipt)
                pg.locks.check()
                pg.assert_owned(admin, stage)
                with admin.cursor() as cursor:
                    cursor.execute('SELECT count(*) FROM pg_stat_activity WHERE datname=%s', (stage['database'],))
                    if cursor.fetchone()[0] != 0:
                        raise Refused('DATABASE_RECONNECTED_NO_FORCE_CLEANUP')
                    cursor.execute(pg.sql.SQL('DROP DATABASE {}').format(pg.sql.Identifier(stage['database'])))
                    dropped = True
            stage['cleanup'] = 'OWNED_DATABASE_REMOVED'
            write_json(folder / 'stage-summary.json', stage)
        except Exception as failure:
            clean = False
            stage.update(cleanup='OWNED_DATABASE_REMOVED_REPORT_WRITE_FAILED' if dropped else 'RETAINED_REQUIRES_OWNER_REVIEW',
                         database_removed=dropped, cleanup_failure_type=type(failure).__name__,
                         cleanup_failure_code=getattr(failure, 'code', 'PRIVATE_DETAILS_WITHHELD'),
                         required_action='Inspect this run marker and durable freeze evidence; do not alter applied migration bytes')
    return clean


def retain_owned(pg: DesignatedPostgres, stages: list[dict], output: Path, manifest: dict | None) -> bool:
    """Final read-only ownership/history checks; planned retention is not a validation failure."""
    verified = True
    for stage in stages:
        if not stage.get('created'):
            stage['cleanup'] = 'NOT_CREATED'
            continue
        stage['retention_policy'] = 'RETAIN_DATABASES_NO_DROP'
        stage['database_removed'] = False
        try:
            pg.locks.check()
            with pg.connection('postgres', readonly=True) as admin:
                pg.assert_owned(admin, stage)
                with admin.cursor() as cursor:
                    cursor.execute('SELECT count(*) FROM pg_stat_activity WHERE datname=%s', (stage['database'],))
                    stage['open_connections_at_retention_check'] = cursor.fetchone()[0]
            stage['retained_owner_marker_verified'] = True
            if manifest is None:
                raise Refused('RETENTION_RESOURCE_MANIFEST_MISSING')
            capture_cleanup_history(pg, stage, output / stage['stage'], manifest)
            stage['own_java_connections_closed'] = not stage.get('java_started') or bool(stage.get('java_process_completed')
                and (stage.get('pools_closed') or stage.get('failure_java_pools_closed')))
            if not stage['own_java_connections_closed']:
                raise Refused('OWN_JAVA_LIFECYCLE_UNCONFIRMED_DATABASE_RETAINED')
            stage['cleanup'] = 'RETAINED_BY_POLICY'
            stage['required_action'] = 'Keep this owned validation database; no cleanup was requested or performed'
        except Exception as failure:
            verified = False
            stage.update(cleanup='RETAINED_REQUIRES_OWNER_REVIEW', retention_check_failure_type=type(failure).__name__,
                         retention_check_failure_code=getattr(failure, 'code', 'PRIVATE_DETAILS_WITHHELD'),
                         required_action='Inspect this run JVM, exact owner marker and freeze evidence; database was not dropped')
    return verified


def run(args) -> int:
    EVIDENCE_WRITE_FAILURES.clear()
    output = reserve_output(args.output)
    run_id = uuid.uuid4().hex
    locks = OwnershipLocks(args.lock_owner, args.lock_session, run_id)
    selected = STAGES[:next(index + 1 for index, stage in enumerate(STAGES) if stage[0] == args.stage)]
    summary = {'schema_version': 1, 'run_id': run_id, 'environment': ENVIRONMENT, 'started_at': now(),
               'status': 'NOT_RUN', 'stop_after_stage': args.stage, 'stages': [], 'live_database_migrated': False,
               'active_database': 'mop_test', 'business_rows_copied': 0, 'external_messages': 0,
               'new_services': 0, 'roles_created_or_changed': 0, 'business_integration_status': 'NOT_RUN',
               'coordination_scope': 'LOCAL_HOST_LOCKS_ONLY; no claim of coordination with other hosts',
               'environment_lock_release_confirms_jvm_termination': False,
               'retention_policy': 'RETAIN_DATABASES_NO_DROP' if args.retain_databases else 'EXPLICIT_OWNED_DATABASE_CLEANUP',
               'formal_contract_acceptance': 'NOT_RUN', 'cleanup_complete': False, 'environment_lock_released': False}
    pg = None
    baseline = None
    fingerprint_module = None
    manifest = None
    try:
        classes = class_overrides(args.test_class)
        if any(not source_test_exists(classes[stage]) for stage, _, _ in selected):
            raise Refused('REQUIRED_JAVA_ENTRY_NOT_PRESENT')
        manifest = migration_manifest()
        summary['migration_resources'] = manifest
        locks.acquire()
        summary['migration_lock_verified'] = {'owner': args.lock_owner, 'session': args.lock_session,
                                               'document_sha256': hashlib.sha256(locks.migration_bytes).hexdigest()}
        fingerprint_module = load_module('candidate_chain_project_fingerprints', 'verify-report-capture-isolated.py')
        if fingerprint_module.ROOT.resolve() != ROOT.resolve() or fingerprint_module.ACTUAL_TENANT != '55000000-0000-4000-8000-000000000001':
            raise Refused('FINGERPRINT_HELPER_PROJECT_SCOPE_MISMATCH')
        pg = DesignatedPostgres(locks)
        pg.open()
        baseline = active_snapshot(pg, fingerprint_module)
        summary.update(postgresql_version=baseline['postgresql_version'], active_history_before_sha256=baseline['history_sha256'],
                       active_schema_version_before=baseline['latest_version'], active_candidate_objects_absent_before=baseline['candidate_objects_absent'])
        summary['protected_fingerprint_scope'] = {'tenant_id': fingerprint_module.ACTUAL_TENANT,
            'tables': sorted(baseline['protected_fingerprints']), 'other_tenants_included': False,
            'comparison_kind': 'BEFORE_AFTER_POINT_IN_TIME_DIGESTS',
            'difference_attribution': 'UNDETERMINED_IF_DIFFERENT; concurrent external changes are not attributed to this tool'}
        write_json(output / 'protected-before.json', baseline['protected_fingerprints'])
        if not baseline['exact_v50'] or not baseline['candidate_objects_absent']:
            raise Refused('ACTIVE_BASELINE_MUST_BE_EXACT_V50_WITHOUT_CANDIDATES')
        predecessor = None
        base_version = 50
        for name, prefix, versions in selected:
            locks.check()
            check_manifest(manifest)
            stage = {'stage': name, 'prefix': prefix, 'database': prefix + uuid.uuid4().hex[:12],
                     'owner_marker': f'MOP_CANDIDATE_CHAIN:{run_id}:{name}:{uuid.uuid4().hex}',
                     'database_owner': MIGRATOR, 'versions': list(versions), 'status': 'NOT_RUN',
                     'created': False, 'started_at': now(), 'business_validation': {'status': 'NOT_RUN', 'test_count': 0}}
            summary['stages'].append(stage)
            folder = output / name
            folder.mkdir(exist_ok=False)
            write_json(folder / 'stage-summary.json', stage)
            write_json(output / 'summary.json', summary)
            try:
                create_clone(pg, stage, predecessor, base_version)
                write_json(folder / 'stage-summary.json', stage)
                write_json(output / 'summary.json', summary)
                maven_test(pg, stage, folder, classes[name], args.timeout)
                observe_stage_history(pg, stage, manifest)
                validate_java_evidence(stage, folder, manifest)
                check_manifest(manifest)
                stage['status'] = 'SCHEMA_VALIDATED_BUSINESS_NOT_RUN'
            except Exception as failure:
                stage.update(status='FAILED', failure_type=type(failure).__name__,
                             failure_code=getattr(failure, 'code', 'PRIVATE_DETAILS_WITHHELD'))
                try:
                    capture_java_failure_evidence(stage, folder)
                except Exception as evidence_failure:
                    stage['failure_java_evidence'] = 'UNREADABLE_' + type(evidence_failure).__name__
                if stage['created']:
                    try:
                        observe_stage_history(pg, stage, manifest)
                    except Exception as observation_failure:
                        stage['history_observation_failure'] = getattr(observation_failure, 'code', type(observation_failure).__name__)
                raise
            finally:
                stage['finished_at'] = now()
                write_json(folder / 'stage-summary.json', stage)
                write_json(output / 'summary.json', summary)
            predecessor, base_version = stage, max(versions)
        summary['status'] = 'SCHEMA_CHAIN_VALIDATED_BUSINESS_NOT_RUN'
    except Exception as failure:
        summary.update(status='FAILED', failure_type=type(failure).__name__,
                       failure_code=getattr(failure, 'code', 'PRIVATE_DETAILS_WITHHELD'))
    finally:
        if pg is not None and pg.settings is not None and fingerprint_module is not None:
            try:
                after = active_snapshot(pg, fingerprint_module)
                summary.update(active_schema_version_after=after['latest_version'], active_history_after_sha256=after['history_sha256'],
                               active_candidate_objects_absent_after=after['candidate_objects_absent'], active_exact_v50_after=after['exact_v50'])
                write_json(output / 'protected-after.json', after['protected_fingerprints'])
                if baseline is None:
                    raise Refused('ACTIVE_BASELINE_COMPARISON_NOT_AVAILABLE')
                summary.update(active_history_unchanged=after['history'] == baseline['history'],
                               active_capabilities_unchanged=after['capabilities'] == baseline['capabilities'],
                               protected_fingerprints_unchanged=after['protected_fingerprints'] == baseline['protected_fingerprints'])
                if not all(summary[key] for key in ('active_history_unchanged', 'active_capabilities_unchanged', 'protected_fingerprints_unchanged', 'active_candidate_objects_absent_after', 'active_exact_v50_after')):
                    raise Refused('ACTIVE_PROJECT_CHANGED_DURING_VALIDATION')
            except Exception as failure:
                summary.update(status='FAILED', active_recheck_failure=getattr(failure, 'code', type(failure).__name__))
        if pg is not None:
            if args.retain_databases:
                summary['retained_database_checks_passed'] = retain_owned(pg, summary['stages'], output, manifest)
                summary['cleanup_complete'] = not any(stage.get('created') for stage in summary['stages'])
                if not summary['retained_database_checks_passed']:
                    summary['status'] = 'FAILED'
            else:
                summary['cleanup_complete'] = cleanup_owned(pg, summary['stages'], output, manifest)
                if not summary['cleanup_complete']:
                    summary['status'] = 'FAILED'
            try:
                pg.close()
            except Exception as failure:
                summary.update(status='FAILED', connection_cleanup_failure=type(failure).__name__)
        else:
            summary['cleanup_complete'] = not any(stage['created'] for stage in summary['stages'])
        try:
            summary['environment_lock_released'] = locks.release()
            if locks.held:
                summary['status'] = 'FAILED'
        except Exception as failure:
            summary.update(status='FAILED', lock_cleanup_failure=type(failure).__name__)
        summary['finished_at'] = now()
        summary['possible_live_jvm_stages'] = [stage['stage'] for stage in summary['stages']
            if stage.get('java_child_processes_may_remain') or stage.get('java_started') and not stage.get('java_process_completed')]
        summary['retained_owned_databases'] = [
            {'stage': stage['stage'], 'database': stage['database'], 'owner_marker': stage['owner_marker'],
             'reason': 'RETAINED_BY_POLICY' if stage.get('cleanup') == 'RETAINED_BY_POLICY'
                else stage.get('cleanup_failure_code', stage.get('retention_check_failure_code', 'CLEANUP_NOT_CONFIRMED'))}
            for stage in summary['stages'] if stage.get('created') and not stage.get('database_removed')
            and stage.get('cleanup') != 'OWNED_DATABASE_REMOVED']
        if summary['possible_live_jvm_stages'] or any(stage['reason'] != 'RETAINED_BY_POLICY' for stage in summary['retained_owned_databases']):
            summary['required_action'] = 'Review this run JVMs, exact database markers and persisted freeze evidence; lock release is not JVM termination'
        elif summary['retained_owned_databases']:
            summary['required_action'] = 'Databases intentionally retained for review; no deletion was requested or performed'
        final_persistence_ok = True
        for stage in summary['stages']:
            try:
                write_json(output / stage['stage'] / 'stage-summary.json', stage)
            except Exception:
                final_persistence_ok = False
                summary['status'] = 'FAILED'
        summary['evidence_write_failures'] = list(EVIDENCE_WRITE_FAILURES)
        if EVIDENCE_WRITE_FAILURES:
            summary['status'] = 'FAILED'
        try:
            write_json(output / 'summary.json', summary)
        except Exception:
            final_persistence_ok = False
            summary['status'] = 'FAILED'
        if not final_persistence_ok:
            # A full disk may make the final file impossible. Preserve actionable, non-secret scope on stdout.
            print(json.dumps({'status': 'FAILED', 'final_evidence_persistence': 'FAILED',
                              'retained_owned_databases': summary['retained_owned_databases'],
                              'possible_live_jvm_stages': summary['possible_live_jvm_stages'],
                              'environment_lock_released': summary['environment_lock_released'],
                              'lock_release_does_not_confirm_jvm_termination': True,
                              'required_action': 'Inspect exact retained database names/markers before cleanup; applied migration bytes remain frozen'}), flush=True)
    print(json.dumps({'status': summary['status'], 'stages': len(summary['stages']),
                      'active_history_unchanged': summary.get('active_history_unchanged'),
                      'cleanup_complete': summary['cleanup_complete'], 'retention_policy': summary['retention_policy'],
                      'evidence': str(output / 'summary.json')}))
    return 0 if summary['status'] == 'SCHEMA_CHAIN_VALIDATED_BUSINESS_NOT_RUN' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--lock-owner', required=True)
    parser.add_argument('--lock-session', required=True)
    parser.add_argument('--stage', choices=[stage[0] for stage in STAGES], default='M02', help='Run from V50 through this stage, inclusive')
    parser.add_argument('--test-class', action='append', default=[], metavar='STAGE=CLASS')
    parser.add_argument('--timeout', type=int, default=1200, help='Per-stage Maven timeout in seconds')
    retention = parser.add_mutually_exclusive_group()
    retention.add_argument('--retain-databases', dest='retain_databases', action='store_true',
                           help='Keep owned validation databases (default); no DROP is executed')
    retention.add_argument('--cleanup-databases', dest='retain_databases', action='store_false',
                           help='Only after explicit human cleanup authorization: remove exact owned databases after durable history gates')
    parser.set_defaults(retain_databases=True)
    arguments = parser.parse_args()
    try:
        if arguments.timeout < 30 or arguments.timeout > 7200 or not all(re.fullmatch(r'[A-Za-z0-9_.:-]{1,128}', value)
                for value in (arguments.lock_owner, arguments.lock_session)):
            raise Refused('INVALID_PUBLIC_RUN_PARAMETERS')
        raise SystemExit(run(arguments))
    except Exception as failure:
        print(json.dumps({'status': 'FAILED', 'failure_type': type(failure).__name__,
                          'failure_code': getattr(failure, 'code', 'PRIVATE_DETAILS_WITHHELD')}))
        raise SystemExit(1)

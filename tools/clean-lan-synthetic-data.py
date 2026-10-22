"""Review and archive synthetic LAN data before an explicit, hash-bound cleanup.

Default plan mode is read-only. Application is restricted to the owned LAN project,
requires the reviewed private plan hash, backs up mop_test, rehearses with ROLLBACK,
and verifies every foreign key before committing. Never logs row contents/secrets.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shlex
import subprocess
import uuid

ROOT = Path(__file__).resolve().parents[1]
TENANT = '55000000-0000-4000-8000-000000000001'
USER = '8a91f5b3-2ca4-4cc0-8484-bd3885ac36a9'
DEVICE_NAMES = {
    '192.169.11.1': '深信服 AF-1000-B1800 防火墙',
    '192.168.1.170': '深信服 aServer-P-2200 超融合',
    '192.168.1.2': '华为 S5731-S24P4X 交换机',
    '192.168.1.125': '信锐 aRS6300-50Q-EI-24X 交换机',
}
PRESERVE_TABLES = {
    'mop_audit', 'flyway_schema_history', 'mop_configuration', 'mop_identity_policy',
    'mop_identity_security', 'mop_local_account', 'mop_local_password_history',
    'mop_user', 'mop_site', 'mop_tenant', 'mop_role_capability_limit', 'mop_wecom_login_state',
}
FAMILIES = (
    ('mop_backup_plan_draft', 'mop_backup_plan_revision', 'draft_id'),
    ('mop_change_notice_draft', 'mop_change_notice_revision', 'draft_id'),
    ('mop_working_calendar_draft', 'mop_working_calendar_revision', 'draft_id'),
    ('mop_inspection_template', 'mop_inspection_template_revision', 'template_id'),
    ('mop_inspection_worksheet', 'mop_inspection_worksheet_revision', 'worksheet_id'),
    ('mop_customer_contact', 'mop_customer_contact_revision', 'contact_id'),
    ('mop_worklog', 'mop_worklog_revision', 'worklog_id'),
)


def require(condition, reason):
    if not condition:
        raise RuntimeError(reason)


def ident(value):
    require(bool(re.fullmatch(r'[a-z][a-z0-9_]*', value)), 'Invalid database identifier')
    return '"' + value + '"'


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def private_path(path):
    path = path.resolve()
    require(path.is_relative_to(ROOT / 'deploy/private'), 'Plan must remain in deploy/private')
    require(subprocess.run(['git', 'check-ignore', '--quiet', str(path)], cwd=ROOT).returncode == 0,
            'Private plan must be Git ignored')
    return path


def server_instance():
    spec = importlib.util.spec_from_file_location('lan_update', ROOT / 'tools/update-lan-test-server.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.Server()


def sql(server, command):
    args = ['docker', 'exec', '-i', 'mop-lan-postgres', 'psql', '-X', '-qAt',
            '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'mop_test']
    stdin, stdout, stderr = server.client.exec_command('sudo -S -p \'\' -- ' + shlex.join(args), timeout=60)
    stdin.write(server.cfg['password'] + '\n')
    stdin.flush()
    stdin.write(command)
    stdin.flush()
    stdin.channel.shutdown_write()
    output = stdout.read().decode('utf-8')
    error = stderr.read().decode('utf-8', 'replace')
    if stdout.channel.recv_exit_status():
        # Only primary error lines, never DETAIL/row values or SQL with parameters.
        safe = '\n'.join(line for line in error.splitlines() if line.startswith('ERROR:'))
        (ROOT / 'deploy/private/real-data-cleanup-error.txt').write_text(safe, encoding='utf-8')
        raise RuntimeError('SQL transaction failed; rolled back on connection close')
    return output


def query(server, statement):
    return json.loads(sql(server, "BEGIN READ ONLY; SET LOCAL timezone='UTC'; "
                         "SELECT coalesce(json_agg(row_to_json(q)),'[]') FROM (" + statement + ') q; COMMIT;'))


def metadata(server):
    primary = query(server, """SELECT c.conrelid::regclass::text AS name,
        array(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num,ord)
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY k.ord) AS columns
        FROM pg_constraint c WHERE c.contype='p' AND c.connamespace='public'::regnamespace""")
    foreign = query(server, """SELECT c.conname,c.condeferrable AS deferred,
        c.conrelid::regclass::text AS child,c.confrelid::regclass::text AS parent,
        array(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num,ord)
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY k.ord) AS child_columns,
        array(SELECT a.attname FROM unnest(c.confkey) WITH ORDINALITY k(num,ord)
        JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num ORDER BY k.ord) AS parent_columns
        FROM pg_constraint c WHERE c.contype='f' AND c.connamespace='public'::regnamespace""")
    return {r['name']: r['columns'] for r in primary}, foreign


def row_text(row):
    text = json.dumps(row, ensure_ascii=False)
    for value in row.values():
        if isinstance(value, str) and value.startswith('\\x'):
            try:
                text += ' ' + bytes.fromhex(value[2:]).decode('utf-8', 'ignore')
            except ValueError:
                pass
    return text


def synthetic(row):
    return bool(re.search(r'SYNTHETIC|SYNTH_|MOP-FAKE|虚构|验收角色', row_text(row), re.I))


def protected(table, row):
    return (table in PRESERVE_TABLES
            or table == 'mop_asset' and row.get('management_ip') in DEVICE_NAMES
            or table == 'mop_project_role' and row.get('builtin') is True
            or table in ('mop_wecom_binding', 'mop_email_binding') and row.get('user_id') == USER
            or table == 'mop_membership' and row.get('user_id') == USER
            or table == 'mop_member_role_assignment' and row.get('user_id') == USER
            or table == 'mop_wecom_delivery' and row.get('id') == 'fd7d3842-a9ac-40fb-8f14-7b29901024df')


def classify(data, foreign):
    selected = {table: {} for table in data if table not in PRESERVE_TABLES}

    def mark(table, index, reason):
        if table not in selected or index in selected[table]:
            return False
        require(not protected(table, data[table][index]), 'Cleanup would touch a protected real object')
        selected[table][index] = reason
        return True

    fake_users = {r['id'] for r in data.get('mop_user', [])
                  if r['id'] != USER and (r.get('identity_source') == 'test_oidc' or synthetic(r))}
    for table, rows in data.items():
        if table not in selected:
            continue
        for index, row in enumerate(rows):
            if protected(table, row):
                continue
            if synthetic(row) or table == 'mop_asset' and row.get('source') == 'test_seed':
                mark(table, index, 'explicit_synthetic_marker_or_frozen_fixture_seed')
            if table in ('mop_wecom_binding', 'mop_email_binding') and row.get('user_id') in fake_users:
                mark(table, index, 'synthetic_identity_channel_binding')
            if table == 'mop_membership' and row.get('tenant_id') == TENANT and row.get('user_id') in fake_users:
                mark(table, index, 'remove_synthetic_identity_from_real_data_tenant')

    for _ in range(100):
        changed = False
        for fk in foreign:
            child, parent = fk['child'], fk['parent']
            if child not in selected or parent not in selected:
                continue
            keys = {tuple(data[parent][i].get(k) for k in fk['parent_columns']) for i in selected[parent]}
            for i, row in enumerate(data[child]):
                values = tuple(row.get(k) for k in fk['child_columns'])
                if all(v is not None for v in values) and values in keys:
                    changed = mark(child, i, 'references_synthetic:' + parent) or changed
        for head, revisions, field in FAMILIES:
            ids = {data[revisions][i].get(field) for i in selected.get(revisions, {})}
            for i, row in enumerate(data.get(head, [])):
                if row.get('id') in ids:
                    changed = mark(head, i, 'synthetic_revision:' + revisions) or changed
        edges = data.get('mop_asset_dependency_edge', [])
        for i, row in enumerate(data.get('mop_asset_dependency_revision', [])):
            matching = [j for j, edge in enumerate(edges) if edge['tenant_id'] == row['tenant_id']
                        and edge['revision_version'] == row['version']]
            if matching and all(j in selected['mop_asset_dependency_edge'] for j in matching):
                changed = mark('mop_asset_dependency_revision', i, 'all_dependency_edges_synthetic') or changed
        for i, row in enumerate(data.get('mop_asset_dependency_graph', [])):
            revisions = data.get('mop_asset_dependency_revision', [])
            matching = [j for j, revision in enumerate(revisions) if revision['tenant_id'] == row['tenant_id']]
            if matching and all(j in selected['mop_asset_dependency_revision'] for j in matching):
                changed = mark('mop_asset_dependency_graph', i, 'all_dependency_revisions_synthetic') or changed
        if not changed:
            break
    else:
        raise RuntimeError('Dependency closure did not converge')
    return selected, fake_users


def make_plan(server):
    primary, foreign = metadata(server)
    disabled = query(server, "SELECT tgname FROM pg_trigger WHERE NOT tgisinternal AND tgenabled<>'O'")
    require(not disabled, 'Existing trigger enablement requires explicit review')
    data = {}
    for table in primary:
        if table in PRESERVE_TABLES and table not in ('mop_user', 'mop_tenant', 'mop_site'):
            continue
        data[table] = query(server, 'SELECT t.*,md5(to_jsonb(t)::text) AS __hash FROM public.' + ident(table) + ' t')
    account = query(server, "SELECT user_id,username FROM mop_local_account WHERE username_key='jiangfaming'")
    require(len(account) == 1 and account[0]['user_id'] == USER, 'Real account identity changed')
    devices = [r for r in data['mop_asset'] if r['management_ip'] in DEVICE_NAMES]
    require(len(devices) == 4 and len({r['management_ip'] for r in devices}) == 4
            and all(r['tenant_id'] == TENANT for r in devices), 'Real device inventory changed')
    drafts = query(server, 'SELECT key FROM mop_configuration WHERE draft_json IS NOT NULL')
    require(not drafts, 'Review configuration drafts separately before cleaning')
    selected, fake_users = classify(data, foreign)
    deletions = {}
    for table, indexes in selected.items():
        if indexes:
            deletions[table] = [{'key': {k: data[table][i][k] for k in primary[table]},
                                 'hash': data[table][i]['__hash'], 'reason': reason}
                                for i, reason in indexes.items()]
    updates = []
    for row in devices:
        if row['name'] != DEVICE_NAMES[row['management_ip']]:
            updates.append({'table': 'mop_asset', 'key': {'id': row['id']}, 'hash': row['__hash'],
                            'set': {'name': DEVICE_NAMES[row['management_ip']]}, 'increment_version': True})
    for table, key, condition, value in (
        ('mop_tenant', 'id', lambda r: r['id'] == TENANT, '实际设备对接环境'),
        ('mop_site', 'id', lambda r: r['tenant_id'] == TENANT and r['id'] == 'site-a-main', '实际设备接入站点'),
    ):
        for row in data[table]:
            if condition(row) and row['name'] != value:
                updates.append({'table': table, 'key': {k: row[k] for k in primary[table]},
                                'hash': row['__hash'], 'set': {'name': value}})
    for row in data['mop_user']:
        if row['id'] in fake_users and row['active']:
            updates.append({'table': 'mop_user', 'key': {'id': row['id']}, 'hash': row['__hash'],
                            'set': {'active': False}})
    remaining = {t: len(rows) - len(selected.get(t, {})) for t, rows in data.items() if rows}
    return {'format': 1, 'environment': 'lan-test-20261008', 'database': 'mop_test', 'user_id': USER,
            'tenant_id': TENANT, 'created_at': dt.datetime.now(dt.timezone.utc).isoformat(),
            'schema_history': server.schema(), 'primary_keys': primary, 'foreign_keys': foreign,
            'deletions': deletions, 'updates': updates, 'remaining_counts': remaining,
            'real_devices': [{'id': r['id'], 'ip': r['management_ip']} for r in devices]}


def where(key, alias='t'):
    return ' AND '.join(alias + '.' + ident(k) + '::text=' + literal(v) for k, v in key.items())


def fingerprint_sql(table):
    return "SELECT count(*)::text||':'||md5(coalesce(string_agg(md5(to_jsonb(t)::text),'' ORDER BY md5(to_jsonb(t)::text)),'')) FROM public." + ident(table) + ' t'


def transaction(plan, commit, plan_hash):
    mutations = set(plan['deletions']) | {u['table'] for u in plan['updates']}
    all_tables = sorted(plan['primary_keys'])
    parts = ["BEGIN; SET LOCAL timezone='UTC'; SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='55s';",
             "SELECT pg_advisory_xact_lock(hashtextextended('mop-real-data-cleanup',0));",
             'LOCK TABLE ' + ','.join('public.' + ident(t) for t in all_tables) + ' IN SHARE ROW EXCLUSIVE MODE;',
             'SET CONSTRAINTS ALL DEFERRED;',
             "DO $$ BEGIN IF current_database()<>'mop_test' OR current_user<>'postgres' THEN RAISE EXCEPTION 'Wrong cleanup database'; END IF; END $$;",
             'CREATE TEMP TABLE cleanup_preserved(name text PRIMARY KEY,fingerprint text) ON COMMIT DROP;']
    for table in all_tables:
        if table not in mutations:
            parts.append('INSERT INTO cleanup_preserved SELECT ' + literal(table) + ',(' + fingerprint_sql(table) + ');')
    # Application-level immutable history triggers are suspended only on reviewed tables,
    # inside this transaction. PostgreSQL foreign key triggers remain active throughout.
    for table in sorted(mutations):
        parts.append('ALTER TABLE public.' + ident(table) + ' DISABLE TRIGGER USER;')
    ctes, counts = [], []
    for index, table in enumerate(sorted(plan['deletions'])):
        rows = plan['deletions'][table]
        conditions = ['(' + where(r['key']) + ' AND md5(to_jsonb(t)::text)=' + literal(r['hash']) + ')' for r in rows]
        ctes.append('removed_' + str(index) + ' AS (DELETE FROM public.' + ident(table)
                    + ' t WHERE ' + ' OR '.join(conditions) + ' RETURNING 1)')
        counts.append('(' + literal(table) + ',(SELECT count(*) FROM removed_' + str(index) + '))')
    if ctes:
        # Source/operation/artifact pointers are cyclic. One data-modifying statement
        # removes the reviewed set before immediate NO ACTION FK checks run.
        expected = json.dumps({t: len(r) for t, r in plan['deletions'].items()})
        parts.append('DO $$ DECLARE actual jsonb; BEGIN WITH ' + ','.join(ctes)
                     + ' SELECT jsonb_object_agg(name,amount) INTO actual FROM (VALUES '
                     + ','.join(counts) + ') counts(name,amount); IF actual<>' + literal(expected)
                     + "::jsonb THEN RAISE EXCEPTION 'Reviewed deletion rows changed'; END IF; END $$;")
    for update in plan['updates']:
        values = [ident(k) + '=' + ('false' if v is False else literal(v)) for k, v in update['set'].items()]
        if update.get('increment_version'):
            values += ['version=version+1', 'updated_at=statement_timestamp()']
        parts.append('DO $$ DECLARE affected bigint; BEGIN UPDATE public.' + ident(update['table'])
                     + ' t SET ' + ','.join(values) + ' WHERE ' + where(update['key'])
                     + ' AND md5(to_jsonb(t)::text)=' + literal(update['hash'])
                     + "; GET DIAGNOSTICS affected=ROW_COUNT; IF affected<>1 THEN RAISE EXCEPTION 'Reviewed update changed'; END IF; END $$;")
    parts.append('SET CONSTRAINTS ALL IMMEDIATE;')
    for table in sorted(mutations):
        parts.append('ALTER TABLE public.' + ident(table) + ' ENABLE TRIGGER USER;')
    for fk in plan['foreign_keys']:
        nullable = ' AND '.join('c.' + ident(k) + ' IS NOT NULL' for k in fk['child_columns'])
        join = ' AND '.join('c.' + ident(c) + '=p.' + ident(p) for c, p in zip(fk['child_columns'], fk['parent_columns']))
        parts.append('DO $$ BEGIN IF EXISTS(SELECT 1 FROM public.' + ident(fk['child'])
                     + ' c WHERE ' + nullable + ' AND NOT EXISTS(SELECT 1 FROM public.' + ident(fk['parent'])
                     + ' p WHERE ' + join + ")) THEN RAISE EXCEPTION 'Foreign key invalid: "
                     + fk['conname'] + "'; END IF; END $$;")
    parts += ['SET CONSTRAINTS ALL IMMEDIATE;',
              "DO $$ DECLARE r record; actual text; BEGIN FOR r IN SELECT * FROM cleanup_preserved LOOP "
              "EXECUTE format('SELECT count(*)::text||'':''||md5(coalesce(string_agg(md5(to_jsonb(t)::text),'''' ORDER BY md5(to_jsonb(t)::text)),'''')) FROM public.%I t',r.name) INTO actual; "
              "IF actual<>r.fingerprint THEN RAISE EXCEPTION 'Preserved table changed: %',r.name; END IF; END LOOP; END $$;"]
    # Operational audit uses the same sorted UTF-8 JSON hash algorithm as the repository.
    parts.append(audit_sql(plan_hash))
    parts += ["SELECT json_build_object('status','VERIFIED','foreign_keys'," + str(len(plan['foreign_keys']))
              + ",'deleted'," + str(sum(map(len, plan['deletions'].values()))) + ",'commit',"
              + ('true' if commit else 'false') + ');', 'COMMIT;' if commit else 'ROLLBACK;']
    return '\n'.join(parts)


def audit_sql(plan_hash):
    return """DO $$ DECLARE seq bigint; prev text; body json; canonical text; entry text; uid uuid:=gen_random_uuid(); BEGIN
      SELECT sequence,entry_hash INTO seq,prev FROM mop_audit WHERE namespace=""" + literal(TENANT) + """ ORDER BY sequence DESC LIMIT 1;
      seq:=coalesce(seq,0)+1; prev:=coalesce(prev,repeat('0',64));
      body:=json_build_object('id',uid::text,'occurred_at',to_char(statement_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS.MS"Z"'),
        'actor_id',null,'actor_name','授权数据维护','tenant_id',""" + literal(TENANT) + """,
        'action','environment.synthetic_data.cleanup','resource_type','maintenance_plan','resource_id',""" + literal(plan_hash) + """,
        'result','SUCCEEDED','reason','USER_REQUEST_REAL_DATA_ONLY;BACKUP_AND_ROLLBACK_VERIFIED',
        'trace_id',uid::text,'source','postgresql','sequence',seq,'previous_hash',prev);
      SELECT '{'||string_agg(to_json(key)::text||':'||value::text,',' ORDER BY key)||'}' INTO canonical FROM json_each(body);
      entry:=encode(sha256(convert_to(prev||canonical,'UTF8')),'hex');
      INSERT INTO mop_audit(id,namespace,tenant_id,sequence,previous_hash,entry_hash,payload)
        VALUES(uid,""" + literal(TENANT) + ',' + literal(TENANT) + """,seq,prev,entry,body::jsonb||jsonb_build_object('entry_hash',entry));
    END $$;"""


def backup(server, folder, plan_bytes):
    server.write(folder + '/reviewed-plan.json', plan_bytes)
    remote_dump = folder + '/mop_test.dump'
    command = 'umask 077; docker exec mop-lan-postgres pg_dump -U postgres -d mop_test -Fc > ' + shlex.quote(remote_dump)
    server.run(['sh', '-c', command], True, timeout=60)
    # TOC parsing verifies archive readability without restoring or touching a database.
    command = 'docker exec -i mop-lan-postgres pg_restore --list < ' + shlex.quote(remote_dump) + ' > /dev/null'
    server.run(['sh', '-c', command], True, timeout=60)
    checksum = server.run(['sha256sum', remote_dump], True).split()[0]
    size = int(server.run(['stat', '-c', '%s', remote_dump], True).strip())
    require(size > 10000, 'Unexpectedly small backup')
    return {'remote_path': remote_dump, 'sha256': checksum, 'bytes': size, 'archive_readable': True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'apply'])
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--sha256')
    args = parser.parse_args()
    path = private_path(args.plan)
    server = server_instance()
    try:
        if args.mode == 'plan':
            require(not path.exists(), 'Refuse to overwrite reviewed plan')
            plan = make_plan(server)
            raw = (json.dumps(plan, ensure_ascii=False, indent=2) + '\n').encode()
            path.write_bytes(raw)
            report = {'status': 'READ_ONLY_PLAN', 'sha256': hashlib.sha256(raw).hexdigest(),
                      'delete_counts': {t: len(rows) for t, rows in plan['deletions'].items()},
                      'update_count': len(plan['updates']), 'remaining_counts': plan['remaining_counts']}
        else:
            raw = path.read_bytes()
            digest = hashlib.sha256(raw).hexdigest()
            require(args.sha256 == digest, 'Reviewed plan SHA256 mismatch')
            plan = json.loads(raw)
            require(plan['environment'] == 'lan-test-20261008' and plan['database'] == 'mop_test'
                    and plan['user_id'] == USER and plan['tenant_id'] == TENANT, 'Wrong reviewed scope')
            require(plan['schema_history'] == server.schema(), 'Migration history changed')
            primary, foreign = metadata(server)
            require(primary == plan['primary_keys'] and foreign == plan['foreign_keys'], 'Schema changed')
            # Refuse a manually expanded plan touching immutable audit/identity/contract tables.
            require(not (set(plan['deletions']) & PRESERVE_TABLES), 'Protected table selected')
            folder = '/opt/mop-lan-test/private/real-data-' + dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
            server.run(['mkdir', '-m', '700', folder])
            saved = backup(server, folder, raw)
            rehearsal = sql(server, transaction(plan, False, digest))
            require('VERIFIED' in rehearsal, 'Rollback rehearsal failed')
            result = sql(server, transaction(plan, True, digest))
            require('VERIFIED' in result, 'Commit did not verify')
            require(plan['schema_history'] == server.schema(), 'Unexpected schema history change')
            report = {'status': 'APPLIED', 'plan_sha256': digest, 'backup': saved,
                      'rollback_rehearsal': True, 'foreign_keys_verified': len(foreign),
                      'delete_counts': {t: len(rows) for t, rows in plan['deletions'].items()},
                      'update_count': len(plan['updates']), 'schema_history_unchanged': True,
                      'verified_at': dt.datetime.now(dt.timezone.utc).isoformat()}
            path.with_suffix('.result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=True))
    finally:
        server.client.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Database/configuration errors are deliberately not echoed with row contents.
        print(json.dumps({'status': 'FAILED', 'error_type': type(error).__name__,
                          'details': 'withheld; inspect reviewed private plan and transaction outcome'}))
        raise SystemExit(1) from None

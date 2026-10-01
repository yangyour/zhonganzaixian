"""Create one explicitly fictional registry entry; preserve all existing registry/draft rows."""
import importlib.util
import json
import logging
import re
import sys
import uuid
from inspect_test_server import ROOT, connect

REQUEST = ROOT / '.local/configuration-diagnostic-http-request.json'
STATE = ROOT / '.local/configuration-diagnostic-http-fixture.json'


def main(action):
    logging.getLogger('paramiko').setLevel(logging.CRITICAL)
    client = None
    try:
        request = json.loads(REQUEST.read_text(encoding='utf-8'))
        run = str(uuid.UUID(request['run_id']))
        key = 'mop.fake.diagnostic.http.' + run.replace('-', '')
        assert request['key'] == key
        marker = request['fake_marker']
        assert re.fullmatch('MOP-FAKE-DIAGNOSTIC-[0-9a-f-]{36}', marker)
        spec = importlib.util.spec_from_file_location('diagnostic_scoped_fixture', ROOT / 'tools/prepare-test-namespace.py')
        namespace = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(namespace)
        client = connect()
        assert namespace.sql(client, 'SELECT current_database();', 'mop_test').strip() == 'mop_test'

        def one(sql):
            rows = [json.loads(line) for line in namespace.sql(client, sql, 'mop_test').splitlines() if line.startswith('{')]
            if len(rows) != 1:
                raise RuntimeError('Expected exactly one scoped row')
            return rows[0]

        literal = namespace.literal
        # Compute only a server-side digest/count; no old registry or draft body crosses SSH.
        fingerprint = "SELECT json_build_object('count',count(*),'digest',encode(sha256(convert_to(COALESCE(string_agg(json_build_array(key,version,registry_json,draft_json,lifecycle_status,updated_at)::text,E'\\n' ORDER BY key),''),'UTF8')),'hex')) FROM public.mop_configuration WHERE key<>" + literal(key) + ';'
        if action == 'seed':
            baseline = one("SELECT json_build_object('existing_registry_count',count(*),'new_key_absent',NOT EXISTS(SELECT 1 FROM public.mop_configuration WHERE key=" + literal(key) + ')) FROM public.mop_configuration;')
            assert baseline['new_key_absent']
            registry = {'key': key, 'description': 'MOP-FAKE配置诊断HTTP测试，非客户业务规则', 'type': 'object', 'unit': None, 'scope': '租户', 'source': 'MOP-FAKE-HTTP-DIAGNOSTIC', 'decision_status': 'pending', 'contract_value_kind': 'configurable_default', 'question_ids': ['Q28'], 'value': {'fake_body': marker + '-registry'}, 'notes': 'MOP-FAKE test input; no publication or activation'}
            state = {**request, 'baseline_registry_count': baseline['existing_registry_count'], 'baseline_existing_rows': one(fingerprint)}
            STATE.write_text(json.dumps(state) + '\n', encoding='utf-8')
            result = one("BEGIN; SET LOCAL ROLE mop_test_migrator; WITH inserted AS (INSERT INTO public.mop_configuration(key,registry_json,draft_json) VALUES (" + literal(key) + ',' + literal(json.dumps(registry, ensure_ascii=False)) + '::jsonb,' + literal(json.dumps({'fake_body': marker + '-draft'})) + "::jsonb) RETURNING key) SELECT json_build_object('inserted_rows',count(*)) FROM inserted; COMMIT;")
            assert result['inserted_rows'] == 1
            return {'status': 'passed', 'action': action, 'created_fictitious_registry_rows': 1, 'existing_rows_modified': False}
        if action == 'verify':
            state = json.loads(STATE.read_text(encoding='utf-8'))
            assert state['run_id'] == run and state['key'] == key
            result = one("SELECT json_build_object('test_registry_present',count(*)=1,'test_registry_version_unchanged',bool_and(version=1),'test_draft_preserved',bool_and(draft_json->>'fake_body'=" + literal(marker + '-draft') + "),'test_registry_value_preserved',bool_and(registry_json->'value'->>'fake_body'=" + literal(marker + '-registry') + ')) FROM public.mop_configuration WHERE key=' + literal(key) + ';')
            assert all(result.values())
            unchanged = one(fingerprint) == state['baseline_existing_rows']
            assert unchanged
            return {'status': 'passed', 'action': action, **result, 'old_registry_and_draft_rows_unchanged_count_and_server_digest': unchanged}
        raise RuntimeError('Unsupported action')
    except Exception:
        return {'status': 'failed', 'action': action, 'details': 'redacted; no SQL, connection or credential output'}
    finally:
        if client is not None:
            try:
                client.close()
            except Exception:
                pass


if __name__ == '__main__':
    action = sys.argv[1] if len(sys.argv) == 2 and sys.argv[1] in {'seed', 'verify'} else 'unsupported'
    result = main(action)
    print(json.dumps(result))
    raise SystemExit(0 if result['status'] == 'passed' else 1)

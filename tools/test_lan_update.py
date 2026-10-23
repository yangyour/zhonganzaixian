"""Deployment failure-path tests. No private files, SSH connections or components."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('lan_update', Path(__file__).with_name('update-lan-test-server.py'))
update = importlib.util.module_from_spec(spec)
spec.loader.exec_module(update)


def application(name='api'):
    return {'Name': '/mop-lan-'+name, 'Id': 'unit-id', 'Image': 'sha256:unit-image', 'RestartCount': 0,
            'State': {'Running': True, 'StartedAt': 'unit-time'},
            'Config': {'Labels': {'mop.project': 'mop-lan-test', 'com.docker.compose.service': name},
                       'Env': ['UNIT_SECRET=not-a-real-secret', 'SERVER_PORT=18010']},
            'Mounts': [{'Type': 'bind', 'Source': update.REMOTE+'/releases/old', 'Destination': '/app', 'RW': False}]}


class DeploymentTests(unittest.TestCase):
    def test_candidate_disables_both_external_dispatchers_without_changing_source(self):
        original={'MOP_EMAIL_DISPATCH_ENABLED':'true','MOP_WECOM_DISPATCH_ENABLED':'true','UNIT_SECRET':'synthetic'}
        candidate=update.candidate_environment(original)
        self.assertEqual(candidate['MOP_EMAIL_DISPATCH_ENABLED'],'false')
        self.assertEqual(candidate['MOP_WECOM_DISPATCH_ENABLED'],'false')
        self.assertEqual(original['MOP_EMAIL_DISPATCH_ENABLED'],'true')
        self.assertEqual(candidate['UNIT_SECRET'],'synthetic')

    def test_email_pending_guard_matches_tenant_and_sender_and_escapes_literal_quote(self):
        server=Mock();server.run.return_value='0\n'
        self.assertEqual(update.pending_email_deliveries(server,'55000000-0000-4000-8000-000000000001',"o'hara@example.test"),0)
        query=server.run.call_args.args[0][-1]
        self.assertIn("AND sender='o''hara@example.test'",query)
        self.assertIn("status IN ('QUEUED','PROCESSING')",query)
        self.assertTrue(query.startswith('BEGIN READ ONLY;'))

    def test_email_pending_guard_rejects_injected_scope_before_network(self):
        server=Mock()
        for sender in ["a@example.test' OR true",'Name <a@example.test>','a@example.test\r\nBcc:other']:
            with self.assertRaises(RuntimeError):update.pending_email_deliveries(server,'55000000-0000-4000-8000-000000000001',sender)
        server.run.assert_not_called()

    def test_partial_up_failure_restores_old_applications_and_checks_ready(self):
        server = Mock(selected=['api', 'web'])
        server.compose.side_effect = [None, RuntimeError('partial recreation'), None, None]
        with self.assertRaisesRegex(RuntimeError, 'partial recreation'):
            update.guarded_switch(server, ['new'], ['old'], Mock(), Mock())
        self.assertEqual(server.compose.call_args_list[-1].args,
                         (['old'], 'up', '-d', '--no-deps', '--pull', 'never', 'api', 'web'))
        server.ready.assert_called_once()

    def test_post_switch_verification_failure_also_rolls_back(self):
        server = Mock(selected=['api'])
        with self.assertRaisesRegex(RuntimeError, 'wrong HTML'):
            update.guarded_switch(server, ['new'], ['old'], Mock(side_effect=RuntimeError('wrong HTML')), Mock())
        self.assertEqual(server.compose.call_count, 4)
        server.ready.assert_called_once()

    def test_invalid_compose_never_recreates_or_rolls_back(self):
        server = Mock(selected=['api'])
        server.compose.side_effect = RuntimeError('invalid config')
        with self.assertRaises(RuntimeError): update.guarded_switch(server, ['new'], ['old'], Mock(), Mock())
        self.assertEqual(server.compose.call_count, 1)
        server.ready.assert_not_called()

    def test_failed_rollback_is_reported_as_unconfirmed(self):
        server = Mock(selected=['api'])
        server.compose.side_effect = [None, RuntimeError('new failed'), None, RuntimeError('old failed')]
        with self.assertRaisesRegex(RuntimeError, 'rollback readiness is unconfirmed'):
            update.guarded_switch(server, ['new'], ['old'], Mock(), Mock())

    def test_success_uses_only_selected_application_services(self):
        server = Mock(selected=['api', 'web'])
        self.assertEqual(update.guarded_switch(server, ['new'], ['old'], lambda: 'ready', Mock()), 'ready')
        self.assertEqual(server.compose.call_count, 2)
        self.assertNotIn('postgres', server.compose.call_args.args)

    def test_overlay_preserves_runtime_and_immutable_image(self):
        item = application()
        desired = update.overlay([item], update.REMOTE+'/releases/new', 'a'*40)['services']['api']
        self.assertEqual(desired['environment']['UNIT_SECRET'], 'not-a-real-secret')
        self.assertEqual(desired['image'], 'sha256:unit-image')
        self.assertEqual(desired['volumes'][0]['source'], update.REMOTE+'/releases/new')
        self.assertTrue(desired['volumes'][0]['read_only'])
        self.assertEqual(item['Mounts'][0]['Source'], update.REMOTE+'/releases/old')

    def test_rollback_clears_new_integration_switch_and_restores_mount(self):
        rollback = update.overlay([application()])['services']['api']
        self.assertEqual(rollback['environment']['MOP_WECOM_ENABLED'], 'false')
        self.assertEqual(rollback['environment']['MOP_EMAIL_ENABLED'], 'false')
        self.assertEqual(rollback['volumes'][0]['source'], update.REMOTE+'/releases/old')
        self.assertEqual(rollback['labels']['mop.source.commit'], '')

    def test_refuses_foreign_or_volume_mounts(self):
        for kind, source in [('bind', '/opt/another-project/app'), ('volume', 'database-data')]:
            item = application(); item['Mounts'][0].update(Type=kind, Source=source)
            with self.assertRaises(RuntimeError): update.overlay([item], update.REMOTE+'/releases/new')

    def test_ownership_mismatch_is_rejected(self):
        items = [application(n) for n in update.SERVICES]
        items[0]['Config']['Labels']['mop.project'] = 'other'
        with self.assertRaisesRegex(RuntimeError, 'ownership'): update.choose(items)

    def test_current_upstream_and_clean_tree_required(self):
        with patch.object(update, 'git', return_value=' M source.java'), patch.object(update.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'committed'): update.synchronize()
            run.assert_not_called()
        with patch.object(update, 'git', side_effect=['', 'feature', 'origin/main']), patch.object(update.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'track'): update.synchronize()
            run.assert_not_called()

    def test_local_only_unpushed_commit_is_rejected(self):
        with patch.object(update, 'git', side_effect=['', 'feature', 'origin/feature', 'a'*40, 'b'*40]), patch.object(update.subprocess, 'run'):
            with self.assertRaisesRegex(RuntimeError, 'pushed'): update.synchronize()

    def test_callback_rejects_injection_and_non_explicit_http(self):
        for url in ['http://evil\";test/api/v1/auth/wecom/callback', 'http://user@site.test/api/v1/auth/wecom/callback',
                    'http://site.test/api/v1/auth/wecom/callback?secret=x', 'http://site.test:10010/api/v1/auth/wecom/callback']:
            with self.assertRaises(RuntimeError): update.callback_parts(url, True)
        with self.assertRaises(RuntimeError): update.callback_parts('http://site.test/api/v1/auth/wecom/callback', False)

    def test_nginx_uses_fixed_allowlist_and_preserves_security_policy(self):
        original = ('http { server { proxy_set_header Host "192.168.1.215:10010"; '
                    'proxy_set_header X-Forwarded-Host "192.168.1.215:10010"; proxy_set_header X-Forwarded-Port 10010; '
                    'proxy_set_header Forwarded ""; add_header X-Frame-Options "DENY" always; } }')
        text = update.domain_nginx(original, 'http://site.test/api/v1/auth/wecom/callback', True)
        self.assertIn('default "192.168.1.215:10010"', text)
        self.assertIn('"site.test" "site.test"', text)
        self.assertNotIn('proxy_set_header Host $http_host', text)
        self.assertIn('proxy_set_header Forwarded ""', text)
        self.assertIn('add_header X-Frame-Options "DENY" always', text)

    def test_restore_path_validation_happens_before_network(self):
        with patch.object(update, 'Server') as server:
            for path in ['/opt/other/transaction.json', update.REMOTE+'/private/update-../../transaction.json']:
                with self.assertRaises(RuntimeError): update.restore(path)
            server.assert_not_called()

    def test_pending_guard_matches_exact_dispatch_scope(self):
        server = Mock(); server.run.return_value = '0\n'
        self.assertEqual(update.pending_deliveries(server, '55000000-0000-4000-8000-000000000001', 'wwSYNTHETIC01', 123), 0)
        sql = server.run.call_args.args[0][-1]
        self.assertIn("tenant_id='55000000-0000-4000-8000-000000000001'::uuid", sql)
        self.assertIn("AND corp_id='wwSYNTHETIC01' AND agent_id=123", sql)
        self.assertIn("AND status IN ('QUEUED','PROCESSING')", sql)
        self.assertTrue(sql.startswith('BEGIN READ ONLY;'))

    def test_invalid_dispatch_scope_rejected_before_database(self):
        server = Mock()
        for corp, agent in [("wwinvalid' OR true", 123), ('wwSYNTHETIC01', True), ('wwSYNTHETIC01', -1)]:
            with self.assertRaises(RuntimeError):
                update.pending_deliveries(server, '55000000-0000-4000-8000-000000000001', corp, agent)
        server.run.assert_not_called()

    def test_compose_snapshot_preserves_literal_dollar_credentials(self):
        text = update.compose_json({'services': {'api': {'environment': {'UNIT_PASSWORD': 'test${HOME}$value$'}}}})
        self.assertIn('test$${HOME}$$value$$', text)

    def recovery(self):
        transaction = update.REMOTE + '/private/update-SYNTHETIC/transaction.json'
        base = update.REMOTE + '/compose.yaml'
        rollback = update.REMOTE + '/private/update-SYNTHETIC/rollback.json'
        files = {base: 'SYNTHETIC_COMPOSE', rollback: 'SYNTHETIC_RECIPE', update.REMOTE + '/.env': 'SYNTHETIC_SECRET'}
        info = {'environment': update.ENVIRONMENT, 'services': ['api', 'web'], 'base_files': [base],
                'rollback_file': rollback, 'schema': [{'version': '50', 'checksum': 123, 'success': True}],
                'recovery_config_sha256': {path: update.digest(value.encode()) for path, value in files.items()}}
        server = Mock(selected=['api', 'web'])
        server.schema.return_value = info['schema']
        server.read.side_effect = files.__getitem__
        return server, info, transaction, files

    def test_matching_recovery_snapshot_allows_config_and_schema_verification_only(self):
        server, info, transaction, _ = self.recovery()
        update.validate_recovery_transaction(info, transaction)
        update.validate_recovery_snapshot(server, info)
        server.compose.assert_not_called()
        server.atomic.assert_not_called()

    def test_migration_after_release_refuses_rollback_before_reading_recipes(self):
        server, info, _, _ = self.recovery()
        server.schema.return_value = info['schema'] + [{'version': '51', 'checksum': 456, 'success': True}]
        with self.assertRaisesRegex(RuntimeError, 'Schema changed since release'):
            update.validate_recovery_snapshot(server, info)
        server.read.assert_not_called()
        server.compose.assert_not_called()

    def test_missing_or_failed_schema_snapshot_is_not_a_safe_legacy_fallback(self):
        for schema in (None, [], [{'success': False}], [{'success': 1}]):
            server, info, _, _ = self.recovery()
            info['schema'] = schema
            with self.assertRaisesRegex(RuntimeError, 'successful schema snapshot'):
                update.validate_recovery_snapshot(server, info)
            server.compose.assert_not_called()

    def test_rotated_credential_or_changed_recipe_blocks_restore_without_leaking_content(self):
        server, info, _, files = self.recovery()
        files[update.REMOTE + '/.env'] = 'ROTATED_SYNTHETIC_SECRET'
        with self.assertRaisesRegex(RuntimeError, 'credential or recipe rollback refused') as raised:
            update.validate_recovery_snapshot(server, info)
        self.assertNotIn('SYNTHETIC_SECRET', str(raised.exception))
        server.compose.assert_not_called()

    def test_legacy_transaction_without_digests_requires_review(self):
        server, info, _, _ = self.recovery()
        del info['recovery_config_sha256']
        with self.assertRaisesRegex(RuntimeError, 'legacy recovery requires review'):
            update.validate_recovery_snapshot(server, info)
        server.read.assert_not_called()

    def test_read_failure_is_redacted_and_never_accepted(self):
        server, info, _, _ = self.recovery()
        server.read.side_effect = OSError('SYNTHETIC_SECRET')
        with self.assertRaisesRegex(RuntimeError, 'configuration could not be verified') as raised:
            update.validate_recovery_snapshot(server, info)
        self.assertNotIn('SYNTHETIC_SECRET', str(raised.exception))

    def test_recovery_transaction_rejects_foreign_files_empty_services_and_wrong_recipe(self):
        for key, value in [('base_files', ['/etc/passwd']), ('base_files', [update.REMOTE + '/../other/compose.yaml']),
                           ('services', []), ('services', ['api', 'api']), ('services', ['postgres']),
                           ('rollback_file', update.REMOTE + '/private/update-OTHER/rollback.json')]:
            server, info, transaction, _ = self.recovery()
            info[key] = value
            with self.assertRaises(RuntimeError):
                update.validate_recovery_transaction(info, transaction)
            server.compose.assert_not_called()

    def test_switch_guard_failure_never_attempts_new_or_old_apps(self):
        server = Mock(selected=['api'])
        guard = Mock(side_effect=RuntimeError('incompatible'))
        with self.assertRaisesRegex(RuntimeError, 'incompatible'):
            update.guarded_switch(server, ['new'], ['old'], Mock(), guard)
        server.compose.assert_not_called()

    def test_schema_drift_after_failed_deploy_prevents_automatic_rollback(self):
        server = Mock(selected=['api'])
        server.compose.side_effect = [None, RuntimeError('partial up')]
        guard = Mock(side_effect=[None, RuntimeError('schema drift')])
        with self.assertRaisesRegex(RuntimeError, 'rollback readiness is unconfirmed'):
            update.guarded_switch(server, ['new'], ['old'], Mock(), guard)
        self.assertEqual(guard.call_count, 2)
        self.assertEqual(server.compose.call_count, 2)
        server.ready.assert_not_called()

    def test_restore_entrypoint_runs_guard_before_compose_and_releases_only_its_lock(self):
        server, info, transaction, _ = self.recovery()
        info['run_id'] = 'SYNTHETIC'
        server.read.side_effect = [__import__('json').dumps(info), __import__('json').dumps(info)]
        server.schema.return_value = []
        with patch.object(update, 'Server', return_value=server):
            with self.assertRaisesRegex(RuntimeError, 'Schema changed since release'):
                update.restore(transaction)
        server.compose.assert_not_called()
        server.atomic.assert_not_called()
        server.client.close.assert_called_once()
        self.assertEqual(server.run.call_args.args[0], ['rmdir', '--', update.REMOTE + '/.update-lock'])


if __name__ == '__main__': unittest.main()

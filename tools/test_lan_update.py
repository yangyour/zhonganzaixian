"""Deployment failure-path tests. No private files, SSH connections or components."""
import importlib.util
import json
from pathlib import Path
import tempfile
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
    def test_partial_up_failure_restores_old_applications_and_checks_ready(self):
        server = Mock(selected=['api', 'web'])
        server.compose.side_effect = [None, RuntimeError('partial recreation'), None, None]
        with self.assertRaisesRegex(RuntimeError, 'partial recreation'):
            update.guarded_switch(server, ['new'], ['old'], Mock())
        self.assertEqual(server.compose.call_args_list[-1].args,
                         (['old'], 'up', '-d', '--no-deps', '--pull', 'never', 'api', 'web'))
        server.ready.assert_called_once()

    def test_post_switch_verification_failure_also_rolls_back(self):
        server = Mock(selected=['api'])
        with self.assertRaisesRegex(RuntimeError, 'wrong HTML'):
            update.guarded_switch(server, ['new'], ['old'], Mock(side_effect=RuntimeError('wrong HTML')))
        self.assertEqual(server.compose.call_count, 4)
        server.ready.assert_called_once()

    def test_invalid_compose_never_recreates_or_rolls_back(self):
        server = Mock(selected=['api'])
        server.compose.side_effect = RuntimeError('invalid config')
        with self.assertRaises(RuntimeError): update.guarded_switch(server, ['new'], ['old'], Mock())
        self.assertEqual(server.compose.call_count, 1)
        server.ready.assert_not_called()

    def test_failed_rollback_is_reported_as_unconfirmed(self):
        server = Mock(selected=['api'])
        server.compose.side_effect = [None, RuntimeError('new failed'), None, RuntimeError('old failed')]
        with self.assertRaisesRegex(RuntimeError, 'rollback readiness is unconfirmed'):
            update.guarded_switch(server, ['new'], ['old'], Mock())

    def test_success_uses_only_selected_application_services(self):
        server = Mock(selected=['api', 'web'])
        self.assertEqual(update.guarded_switch(server, ['new'], ['old'], lambda: 'ready'), 'ready')
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


if __name__ == '__main__': unittest.main()

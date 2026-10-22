"""Safety checks for the reviewed synthetic-data maintenance operation."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('cleanup', Path(__file__).with_name('clean-lan-synthetic-data.py'))
cleanup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleanup)


class CleanupTests(unittest.TestCase):
    def test_unknown_business_content_is_preserved(self):
        selected, _ = cleanup.classify({'mop_ticket': [{'id': 'actual', 'title': '网络中断'}]}, [])
        self.assertFalse(selected['mop_ticket'])

    def test_real_devices_and_real_bindings_are_never_marker_deleted(self):
        data = {'mop_asset': [{'id': 'real', 'management_ip': '192.169.11.1', 'name': 'SYNTHETIC accidental label'}],
                'mop_email_binding': [{'user_id': cleanup.USER, 'note': 'SYNTHETIC'}]}
        selected, _ = cleanup.classify(data, [])
        self.assertFalse(any(selected.values()))

    def test_fk_closure_rejects_a_protected_child(self):
        data = {'mop_ticket': [{'id': 'fake', 'title': 'SYNTHETIC'}],
                'mop_asset': [{'id': 'real', 'management_ip': '192.169.11.1', 'ticket_id': 'fake'}]}
        fk = {'parent': 'mop_ticket', 'child': 'mop_asset', 'parent_columns': ['id'], 'child_columns': ['ticket_id']}
        with self.assertRaises(RuntimeError):
            cleanup.classify(data, [fk])

    def test_synthetic_children_are_removed_without_real_siblings(self):
        data = {'mop_ticket': [{'id': 'fake', 'title': 'SYNTHETIC'}, {'id': 'real', 'title': 'Actual'}],
                'mop_ticket_comment': [{'ticket_id': 'fake'}, {'ticket_id': 'real'}]}
        fk = {'parent': 'mop_ticket', 'child': 'mop_ticket_comment', 'parent_columns': ['id'], 'child_columns': ['ticket_id']}
        selected, _ = cleanup.classify(data, [fk])
        self.assertEqual(set(selected['mop_ticket_comment']), {0})

    def test_binary_synthetic_content_detected(self):
        self.assertTrue(cleanup.synthetic({'command_bytes': '\\x' + '虚构设备'.encode().hex()}))

    def test_identity_scope_preserves_real_and_other_tenant_memberships(self):
        data = {'mop_user': [{'id': 'fixture', 'identity_source': 'test_oidc'}],
                'mop_membership': [{'tenant_id': cleanup.TENANT, 'user_id': 'fixture'},
                                   {'tenant_id': 'other', 'user_id': 'fixture'},
                                   {'tenant_id': cleanup.TENANT, 'user_id': cleanup.USER}]}
        selected, _ = cleanup.classify(data, [])
        self.assertEqual(set(selected['mop_membership']), {0})

    def test_rehearsal_keeps_fk_triggers_and_guards_each_row(self):
        plan = {'deletions': {'a': [{'key': {'id': 'one'}, 'hash': 'hash1'}],
                              'b': [{'key': {'id': 'two'}, 'hash': 'hash2'}]},
                'updates': [], 'primary_keys': {'a': ['id'], 'b': ['id']}, 'foreign_keys': []}
        statement = cleanup.transaction(plan, False, 'planhash')
        self.assertIn('WITH removed_0 AS (DELETE', statement)
        self.assertIn('removed_1 AS (DELETE', statement)
        self.assertIn("md5(to_jsonb(t)::text)='hash1'", statement)
        self.assertIn('DISABLE TRIGGER USER', statement)
        self.assertNotIn('DISABLE TRIGGER ALL', statement)
        self.assertNotIn('session_replication_role', statement)
        self.assertTrue(statement.endswith('ROLLBACK;'))

    def test_sql_identifier_injection_rejected(self):
        with self.assertRaises(RuntimeError):
            cleanup.ident('mop_asset; DROP TABLE mop_user')
        self.assertEqual(cleanup.literal("x'y"), "'x''y'")


if __name__ == '__main__':
    unittest.main()

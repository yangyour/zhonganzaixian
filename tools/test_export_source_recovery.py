import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

spec = importlib.util.spec_from_file_location('recovery', Path(__file__).with_name('export-source-recovery.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class SourceRecoveryExportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run_git('init', '-q')
        (self.root / 'AGENTS.md').write_text('Synthetic unit instructions')
        (self.root / 'image.png').write_bytes(b'\x89PNG\r\n\x1a\nISOLATED_UNIT_BYTES')
        self.commit()

    def run_git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.DEVNULL)

    def commit(self):
        self.run_git('add', '.')
        self.run_git('-c', 'user.name=Unit Test', '-c', 'user.email=unit@example.invalid', 'commit', '-qm', 'isolated')

    def test_exact_tracked_head_and_binary_hash(self):
        result = m.export(self.root)
        with zipfile.ZipFile(self.root / m.OUTPUT / 'source.zip') as archive:
            self.assertEqual({'AGENTS.md', 'image.png'}, set(archive.namelist()))
            for row in result['entries']:
                data = archive.read(row['path'])
                self.assertEqual(row['git_blob_sha1'], hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest())
        self.assertEqual(2, result['file_count'])
        self.assertFalse(result['git_history_included'])

    def test_untracked_private_and_dirty_worktree_not_exported(self):
        (self.root / 'private').mkdir()
        (self.root / 'private/value').write_text('synthetic not a real secret')
        (self.root / 'AGENTS.md').write_text('uncommitted')
        m.export(self.root)
        with zipfile.ZipFile(self.root / m.OUTPUT / 'source.zip') as archive:
            self.assertEqual({'AGENTS.md', 'image.png'}, set(archive.namelist()))
            self.assertEqual(b'Synthetic unit instructions', archive.read('AGENTS.md'))

    def test_tracked_private_path_fails_closed(self):
        (self.root / 'deploy/private').mkdir(parents=True)
        (self.root / 'deploy/private/config').write_text('unit only')
        self.commit()
        with self.assertRaises(ValueError): m.export(self.root)

    def test_symlink_refused(self):
        try:
            (self.root / 'link').symlink_to('AGENTS.md')
        except OSError as failure:
            if os.name == 'nt' and getattr(failure, 'winerror', None) == 1314:
                self.skipTest('Windows host lacks symlink creation privilege; native Linux CI verifies this guard')
            raise
        self.commit()
        with self.assertRaises(ValueError): m.export(self.root)

    def test_existing_output_never_overwritten(self):
        m.export(self.root)
        before = (self.root / m.OUTPUT / 'source.zip').read_bytes()
        with self.assertRaises(FileExistsError): m.export(self.root)
        self.assertEqual(before, (self.root / m.OUTPUT / 'source.zip').read_bytes())

    def test_key_file_refused(self):
        (self.root / 'test.key').write_text('unit only')
        self.commit()
        with self.assertRaises(ValueError): m.export(self.root)

    def test_size_budget(self):
        original = m.MAX_TOTAL_BYTES
        try:
            m.MAX_TOTAL_BYTES = 1
            with self.assertRaises(ValueError): m.export(self.root)
        finally:
            m.MAX_TOTAL_BYTES = original

    def test_manifest_proves_archive_bytes_and_tree(self):
        result = m.export(self.root)
        manifest = json.loads((self.root / m.OUTPUT / 'manifest.json').read_text())
        self.assertEqual(result, manifest)
        self.assertEqual(self.run_git('rev-parse', 'HEAD^{tree}').decode().strip(), result['source_tree'])
        self.assertEqual(hashlib.sha256((self.root / m.OUTPUT / 'source.zip').read_bytes()).hexdigest(), result['archive_sha256'])

    def test_exact_commit_bytes_preserved_without_timezone_reconstruction(self):
        result = m.export(self.root)
        raw = (self.root / m.OUTPUT / 'head-commit.bin').read_bytes()
        self.assertEqual(self.run_git('cat-file', 'commit', 'HEAD'), raw)
        self.assertEqual(result['source_commit'], hashlib.sha1(b'commit ' + str(len(raw)).encode() + b'\0' + raw).hexdigest())
        self.assertTrue(result['head_commit_object_included'])
        self.assertFalse(result['git_history_included'])


    def test_export_ignore_cannot_drop_a_manifest_blob(self):
        (self.root / '.gitattributes').write_text('image.png export-ignore\n')
        self.commit()
        result = m.export(self.root)
        with zipfile.ZipFile(self.root / m.OUTPUT / 'source.zip') as archive:
            self.assertEqual({row['path'] for row in result['entries']}, set(archive.namelist()))
            self.assertEqual(self.run_git('show', 'HEAD:image.png'), archive.read('image.png'))

    def test_export_subst_cannot_rewrite_committed_source_bytes(self):
        (self.root / 'revision.txt').write_text('$Format:%H$\n')
        (self.root / '.gitattributes').write_text('revision.txt export-subst\n')
        self.commit()
        m.export(self.root)
        with zipfile.ZipFile(self.root / m.OUTPUT / 'source.zip') as archive:
            self.assertEqual(b'$Format:%H$\n', archive.read('revision.txt'))

    def test_local_info_attributes_do_not_change_exact_source_export(self):
        (self.root / '.git/info/attributes').write_text('image.png export-ignore\n')
        m.export(self.root)
        with zipfile.ZipFile(self.root / m.OUTPUT / 'source.zip') as archive:
            self.assertIn('image.png', archive.namelist())

    def test_head_movement_during_export_cannot_mix_two_commits(self):
        before = self.run_git('rev-parse', 'HEAD').decode().strip()
        original = m.git
        moved = False
        def move_after_tree(root, *args):
            nonlocal moved
            result = original(root, *args)
            if args[0] == 'ls-tree' and not moved:
                moved = True
                (self.root / 'later.txt').write_text('different later source')
                self.commit()
            return result
        with mock.patch.object(m, 'git', side_effect=move_after_tree):
            result = m.export(self.root)
        self.assertNotEqual(before, self.run_git('rev-parse', 'HEAD').decode().strip())
        self.assertEqual(before, result['source_commit'])
        with zipfile.ZipFile(self.root / m.OUTPUT / 'source.zip') as archive:
            self.assertNotIn('later.txt', archive.namelist())

    def test_tracked_environment_variant_is_refused_before_export(self):
        (self.root / '.env.production').write_text('SYNTHETIC=unit-only')
        self.commit()
        with self.assertRaises(ValueError): m.export(self.root)
        self.assertFalse((self.root / m.OUTPUT).exists())

    def test_case_variant_key_suffix_is_refused(self):
        (self.root / 'unit.PEM').write_text('synthetic, not a real key')
        self.commit()
        with self.assertRaises(ValueError): m.export(self.root)


    def test_exact_executable_mode_and_reproducible_archive(self):
        self.run_git('update-index', '--chmod=+x', 'AGENTS.md')
        self.run_git('-c', 'user.name=Unit Test', '-c', 'user.email=unit@example.invalid', 'commit', '-qm', 'mode only')
        first = m.export(self.root)
        with zipfile.ZipFile(self.root / m.OUTPUT / 'source.zip') as archive:
            self.assertEqual(0o100755, archive.getinfo('AGENTS.md').external_attr >> 16)
        with mock.patch.object(m, 'OUTPUT', '.local/recovery-second'):
            second = m.export(self.root)
        self.assertEqual(first['archive_sha256'], second['archive_sha256'])
        self.assertTrue(first['exact_tree_blobs_verified'])
        self.assertFalse(first['git_export_attributes_applied'])

    def test_corrupt_blob_bytes_never_produce_a_success_manifest(self):
        original = m.git
        def corrupt(root, *args):
            value = original(root, *args)
            return value + b'X' if args[:2] == ('cat-file', 'blob') else value
        with mock.patch.object(m, 'git', side_effect=corrupt):
            with self.assertRaisesRegex(ValueError, 'blob bytes mismatch'): m.export(self.root)
        self.assertFalse((self.root / m.OUTPUT / 'manifest.json').exists())

    def test_corrupt_commit_is_rejected_before_output_creation(self):
        original = m.git
        def corrupt(root, *args):
            value = original(root, *args)
            return value + b'X' if args[:2] == ('cat-file', 'commit') else value
        with mock.patch.object(m, 'git', side_effect=corrupt):
            with self.assertRaisesRegex(ValueError, 'commit bytes mismatch'): m.export(self.root)
        self.assertFalse((self.root / m.OUTPUT).exists())

    def test_hidden_authentication_directory_is_rejected(self):
        (self.root / '.ssh').mkdir()
        (self.root / '.ssh/config').write_text('synthetic unit only')
        self.commit()
        with self.assertRaises(ValueError): m.export(self.root)
        self.assertFalse((self.root / m.OUTPUT).exists())


    def test_only_exact_reviewed_example_blob_is_allowed(self):
        path = '.env.example'
        (self.root / path).write_text('UNIT_PORT=12345\n')
        self.commit()
        sha = self.run_git('rev-parse', 'HEAD:' + path).decode().strip()
        with mock.patch.object(m, 'APPROVED_ENV_EXAMPLE_BLOBS', {path: sha}):
            result = m.export(self.root)
            self.assertIn(path, {row['path'] for row in result['entries']})
            (self.root / path).write_text('UNIT_PORT=23456\n')
            self.run_git('add', path)
            self.run_git('-c', 'user.name=Unit Test', '-c', 'user.email=unit@example.invalid', 'commit', '-qm', 'changed template')
            with self.assertRaises(ValueError): m.export(self.root)

    def test_unreviewed_example_name_is_not_an_export_exception(self):
        (self.root / '.env.example').write_text('SYNTHETIC=unit-only')
        self.commit()
        with self.assertRaises(ValueError): m.export(self.root)


if __name__ == '__main__': unittest.main()

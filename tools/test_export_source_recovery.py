import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
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
        (self.root / 'link').symlink_to('AGENTS.md')
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


if __name__ == '__main__': unittest.main()

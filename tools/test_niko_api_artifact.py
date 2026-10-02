"""Pure file/guard checks with synthetic ZIP bytes; not real API or component acceptance."""
from pathlib import Path
import os, subprocess, sys, tempfile, unittest, zipfile
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'tools/niko-test'))
import api_artifact as artifact

class NikoApiArtifactTest(unittest.TestCase):
    def fixture(self, base):
        root = base / 'repo'; (root / '.local/niko-test').mkdir(parents=True)
        source = root / 'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'; source.parent.mkdir(parents=True)
        self.archive(source, b'original')
        return root, source

    def archive(self, path, payload):
        with zipfile.ZipFile(path, 'w') as target:
            target.writestr('META-INF/MANIFEST.MF', 'synthetic test archive, not executable')
            target.writestr('BOOT-INF/classes/cn/mop/api/MopApplication.class', payload)

    def test_independent_copy_survives_rewrite_and_removal_of_target(self):
        with tempfile.TemporaryDirectory() as d:
            root, source = self.fixture(Path(d)); pinned = artifact.prepare_artifact(root); data = pinned.read_bytes()
            self.assertEqual(source.read_bytes(), data); self.assertNotEqual(source.stat().st_ino, pinned.stat().st_ino)
            source.write_bytes(b'rebuilt or incomplete bytes'); self.assertEqual(data, pinned.read_bytes()); self.assertEqual(pinned, artifact.verified_artifact(root, pinned))
            source.unlink(); self.assertEqual(data, pinned.read_bytes()); self.assertEqual(0o400, pinned.stat().st_mode & 0o777)

    def test_each_launch_gets_a_distinct_copy_without_overwriting_prior_artifact(self):
        with tempfile.TemporaryDirectory() as d:
            root, source = self.fixture(Path(d)); first = artifact.prepare_artifact(root); original = first.read_bytes(); second = artifact.prepare_artifact(root)
            self.assertNotEqual(first, second); self.assertEqual(first.name, second.name)
            self.archive(source, b'new build'); third = artifact.prepare_artifact(root)
            self.assertNotEqual(first.name, third.name); self.assertEqual(original, first.read_bytes())

    def test_corrupt_or_wrong_archive_is_not_a_runnable_artifact(self):
        with tempfile.TemporaryDirectory() as d:
            root, source = self.fixture(Path(d)); source.write_bytes(b'not a ZIP')
            self.assertRaises(zipfile.BadZipFile, artifact.prepare_artifact, root)
            with zipfile.ZipFile(source, 'w') as z: z.writestr('unrelated', b'x')
            self.assertRaises(ValueError, artifact.prepare_artifact, root)
            self.assertEqual([], list((root / '.local/niko-test/runtime-artifacts').glob('api-*/*.jar')))

    def test_source_symlink_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root, source = self.fixture(Path(d)); other = source.with_name('other.jar'); source.rename(other); source.symlink_to(other)
            self.assertRaises(ValueError, artifact.prepare_artifact, root)

    def test_malformed_external_relative_or_target_path_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root, source = self.fixture(Path(d)); pinned = artifact.prepare_artifact(root)
            for value in [source, 'relative.jar', pinned.parent / 'wrong.jar', Path(d) / pinned.name]:
                self.assertRaises((ValueError, OSError), artifact.verified_artifact, root, value)

    def test_modified_bytes_writable_file_or_hardlink_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root, _ = self.fixture(Path(d)); pinned = artifact.prepare_artifact(root)
            pinned.chmod(0o600); self.assertRaises(ValueError, artifact.verified_artifact, root, pinned)
            pinned.write_bytes(b'changed'); pinned.chmod(0o400); self.assertRaises(ValueError, artifact.verified_artifact, root, pinned)
            other = artifact.prepare_artifact(root); os.link(other, other.parent / 'hardlink')
            self.assertRaises(ValueError, artifact.verified_artifact, root, other)

    def test_runtime_symlink_and_shared_directory_are_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root, _ = self.fixture(Path(d)); pinned = artifact.prepare_artifact(root); saved = pinned.with_suffix('.saved'); pinned.rename(saved); pinned.symlink_to(saved)
            self.assertRaises(ValueError, artifact.verified_artifact, root, pinned)
            pinned = artifact.prepare_artifact(root); pinned.parent.chmod(0o777)
            self.assertRaises(ValueError, artifact.verified_artifact, root, pinned)

    def test_wrong_owner_is_refused(self):
        with tempfile.TemporaryDirectory() as d:
            root, source = self.fixture(Path(d))
            with patch.object(artifact.os, 'getuid', return_value=source.stat().st_uid + 1):
                self.assertRaises(ValueError, artifact.prepare_artifact, root)

    def test_build_change_during_copy_is_detected(self):
        with tempfile.TemporaryDirectory() as d:
            root, source = self.fixture(Path(d)); original = artifact.regular_owned; calls = 0
            def change_after_copy(path):
                nonlocal calls
                calls += 1
                if calls == 2: self.archive(source, b'concurrent replacement')
                return original(path)
            with patch.object(artifact, 'regular_owned', side_effect=change_after_copy):
                self.assertRaises(ValueError, artifact.prepare_artifact, root)
            self.assertEqual([], list((root / '.local/niko-test/runtime-artifacts').glob('api-*/*.jar')))

    def test_invalid_start_argument_fails_before_private_config_read(self):
        with tempfile.TemporaryDirectory() as d:
            for args in [[], ['relative.jar']]:
                result = subprocess.run([sys.executable, str(ROOT / 'tools/niko-test/start-api.py'), *args], cwd=d, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode); self.assertNotIn('runtime.json', result.stderr); self.assertFalse((Path(d) / '.local').exists())

    def test_unknown_prepare_mode_does_not_create_files(self):
        with tempfile.TemporaryDirectory() as d:
            result = subprocess.run([sys.executable, str(ROOT / 'tools/niko-test/api_artifact.py'), '--unknown'], cwd=d, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode); self.assertEqual([], list(Path(d).iterdir()))

if __name__ == '__main__': unittest.main()

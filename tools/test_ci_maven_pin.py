"""Credential-free checks for the CI Maven pin; no network or Maven execution."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
START = '      - name: Install checksum-pinned Maven 3.9.9\n'
END = '      - name: Verify the Maven toolchain\n'


def install_body():
    workflow = (ROOT / '.github/workflows/cloud-validation.yml').read_text()
    block = workflow.split(START, 1)[1].split(END, 1)[0]
    return '\n'.join(line[10:] for line in block.splitlines()[1:]) + '\n'


class MavenPinTest(unittest.TestCase):
    def test_pin_precedes_existing_gate_and_verification(self):
        text = (ROOT / '.github/workflows/cloud-validation.yml').read_text()
        self.assertEqual(1, text.count(START))
        self.assertLess(text.index(START), text.index(END))
        self.assertLess(text.index(END), text.index('-Dmop.fixture.unit-test=false verify'))
        body = install_body()
        self.assertIn('https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.tar.gz', body)
        self.assertIn('a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b', body)
        self.assertLess(body.index('sha512sum --check --strict'), body.index('tar -xzf'))
        self.assertLess(body.index('tar -xzf'), body.index('>> "$GITHUB_PATH"'))
        self.assertIn('contents: read', text)

    @unittest.skipUnless(os.name == 'posix' and shutil.which('bash') and shutil.which('sha512sum'), 'Requires POSIX shell and SHA512')
    def test_bad_checksum_never_extracts_or_changes_path(self):
        with tempfile.TemporaryDirectory(prefix='mop-ci-pin-') as temp:
            root = Path(temp)
            bin_dir = root / 'bin'
            bin_dir.mkdir()
            curl = bin_dir / 'curl'
            curl.write_text('#!/bin/sh\nwhile [ "$#" -gt 0 ]; do\n if [ "$1" = "--output" ]; then shift; printf bad > "$1"; exit 0; fi\n shift\ndone\nexit 2\n')
            curl.chmod(0o700)
            tar = bin_dir / 'tar'
            tar.write_text('#!/bin/sh\nprintf invoked > "$RUNNER_TEMP/tar-invoked"\n')
            tar.chmod(0o700)
            path_file = root / 'github-path'
            env = dict(os.environ, RUNNER_TEMP=str(root), GITHUB_PATH=str(path_file), PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])
            result = subprocess.run(['bash', '-e', '-o', 'pipefail'], input=install_body(), text=True, env=env, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('FAILED', result.stdout)
            self.assertFalse((root / 'tar-invoked').exists())
            self.assertFalse(path_file.exists())


if __name__ == '__main__':
    unittest.main()

#!/usr/bin/env python3
"""Export tracked HEAD only for recovery via the same private repository's short-lived CI artifact."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess

OUTPUT = '.local/source-recovery'
MAX_TOTAL_BYTES = 24 * 1024 * 1024


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args])


def export(root: Path):
    root = root.resolve()
    entries = []
    for record in git(root, 'ls-tree', '-rz', '--full-tree', 'HEAD').split(b'\0'):
        if not record:
            continue
        meta, raw_path = record.split(b'\t', 1)
        mode, kind, sha = meta.decode('ascii').split(' ')
        path = raw_path.decode('utf-8')
        parts = PurePosixPath(path).parts
        if not parts or path.startswith('/') or '\\' in path or '..' in parts or any(p in {'.git', '.local', '.aws', 'node_modules', 'private', '.env'} for p in parts) or path.endswith(('.pem', '.key')):
            raise ValueError('Secret/private/generated path excluded from recovery export')
        if kind != 'blob' or mode not in {'100644', '100755'}:
            raise ValueError('Only regular tracked files may be exported')
        entries.append({'path': path, 'mode': mode, 'git_blob_sha1': sha})
    if not entries or len(entries) > 10000:
        raise ValueError('Invalid tracked file count')
    sizes = [int(git(root, 'cat-file', '-s', row['git_blob_sha1'])) for row in entries]
    if sum(sizes) > MAX_TOTAL_BYTES:
        raise ValueError('Source archive exceeds bounded recovery size')
    output = root / OUTPUT
    if output.exists() or output.is_symlink():
        raise FileExistsError('Recovery output must be new; never overwrite prior evidence')
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.parent.is_symlink():
        raise ValueError('Output symlink forbidden')
    output.mkdir()
    archive = output / 'source.zip'
    git(root, 'archive', '--format=zip', '-o', str(archive), 'HEAD')
    commit = git(root, 'cat-file', 'commit', 'HEAD')
    commit_sha = git(root, 'rev-parse', 'HEAD').decode().strip()
    if hashlib.sha1(b'commit ' + str(len(commit)).encode() + b'\0' + commit).hexdigest() != commit_sha:
        raise ValueError('Exact commit bytes mismatch')
    (output / 'head-commit.bin').write_bytes(commit)
    result = {'source_commit': commit_sha, 'head_commit_object_included': True, 'source_tree': git(root, 'rev-parse', 'HEAD^{tree}').decode().strip(), 'file_count': len(entries), 'total_source_bytes': sum(sizes), 'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'entries': entries, 'git_history_included': False, 'untracked_files_included': False, 'components_started': False}
    (output / 'manifest.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return result


if __name__ == '__main__':
    result = export(Path(__file__).resolve().parents[1])
    print(json.dumps({key: value for key, value in result.items() if key != 'entries'}))

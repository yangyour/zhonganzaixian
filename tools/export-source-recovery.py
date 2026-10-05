#!/usr/bin/env python3
"""Export tracked HEAD only for recovery via the same private repository's short-lived CI artifact."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import zipfile

OUTPUT = '.local/source-recovery'
MAX_TOTAL_BYTES = 24 * 1024 * 1024
# Reviewed non-secret templates: only local endpoints, ports and role/client names.
# Any change requires another review; never allow arbitrary .env.* files by suffix.
APPROVED_ENV_EXAMPLE_BLOBS = {
    'apps/web/.env.example': '4ea67afef7ae5654eb176d8442bed008b54f49fe',
    'deploy/.env.example': '0b3ca4c886ca82965be6d88cb47ab326a79f50cd',
}


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args])


def export(root: Path):
    root = root.resolve()
    # Resolve once. A later branch movement must not mix its files, tree and commit.
    commit_sha = git(root, 'rev-parse', '--verify', 'HEAD^{commit}').decode().strip()
    commit = git(root, 'cat-file', 'commit', commit_sha)
    if hashlib.sha1(b'commit ' + str(len(commit)).encode() + b'\0' + commit).hexdigest() != commit_sha:
        raise ValueError('Exact commit bytes mismatch')
    tree_sha = git(root, 'rev-parse', commit_sha + '^{tree}').decode().strip()
    entries = []
    for record in git(root, 'ls-tree', '-rz', '--full-tree', commit_sha).split(b'\0'):
        if not record:
            continue
        meta, raw_path = record.split(b'\t', 1)
        mode, kind, sha = meta.decode('ascii').split(' ')
        path = raw_path.decode('utf-8')
        parts = PurePosixPath(path).parts
        lowered = [part.casefold() for part in parts]
        private_parts = {'.git', '.local', '.aws', '.ssh', '.gnupg', '.azure', '.kube', 'node_modules', 'private', '.env', '.npmrc', '.netrc', '.pypirc'}
        environment_variant = any(part.startswith('.env.') for part in lowered)
        reviewed_example = APPROVED_ENV_EXAMPLE_BLOBS.get(path) == sha
        if not parts or path.startswith('/') or '\\' in path or '..' in parts or any(ord(c) < 32 or ord(c) == 127 for c in path) or any(part in private_parts for part in lowered) or environment_variant and not reviewed_example or path.casefold().endswith(('.pem', '.key', '.p12', '.pfx')):
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
    # git archive honors export-ignore/export-subst and local info/attributes.
    # Recovery promises exact tree blobs, so write those immutable objects directly.
    with zipfile.ZipFile(archive, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zipped:
        for row, size in zip(entries, sizes):
            data = git(root, 'cat-file', 'blob', row['git_blob_sha1'])
            if len(data) != size or hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() != row['git_blob_sha1']:
                raise ValueError('Recovery source blob bytes mismatch')
            info = zipfile.ZipInfo(row['path'], date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.external_attr = int(row['mode'], 8) << 16
            zipped.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
            row['size_bytes'] = size
    (output / 'head-commit.bin').write_bytes(commit)
    result = {'source_commit': commit_sha, 'head_commit_object_included': True, 'source_tree': tree_sha, 'file_count': len(entries), 'total_source_bytes': sum(sizes), 'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(), 'entries': entries, 'exact_tree_blobs_verified': True, 'git_export_attributes_applied': False, 'head_pinned_once': True, 'git_history_included': False, 'untracked_files_included': False, 'components_started': False}
    (output / 'manifest.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return result


if __name__ == '__main__':
    result = export(Path(__file__).resolve().parents[1])
    print(json.dumps({key: value for key, value in result.items() if key != 'entries'}))

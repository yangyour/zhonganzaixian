"""Pin the owned export preparation worker JVM to a validated, per-launch copy, never Maven's mutable target."""
from pathlib import Path
import hashlib, os, re, stat, sys, tempfile, zipfile

def regular_owned(path):
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
        raise ValueError('Artifact must be an owned regular file')
    return info

def directory_owned(path):
    info = path.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or info.st_mode & 0o022:
        raise ValueError('Artifact directory is not an owned non-shared directory')

def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()

def verified_artifact(root, value):
    root = root.resolve()
    path = Path(value)
    base = root / '.local/niko-test/export-worker-artifacts'
    if not path.is_absolute() or path != path.resolve() or path.parent.parent != base:
        raise ValueError('Artifact is outside the per-launch runtime directory')
    for parent in [root / '.local', root / '.local/niko-test', base, path.parent]:
        directory_owned(parent)
    if not re.fullmatch(r'export-worker-[a-z0-9_]{8}', path.parent.name) or not re.fullmatch(r'[0-9a-f]{64}\.jar', path.name):
        raise ValueError('Artifact path is not a pinned launch copy')
    info = regular_owned(path)
    if info.st_mode & 0o777 != 0o400 or info.st_nlink != 1 or digest(path) != path.stem:
        raise ValueError('Artifact permissions or bytes do not match the pinned copy')
    return path

def prepare_artifact(root):
    root = root.resolve()
    source = root / 'server/export-preparation-worker/target/mop-export-preparation-worker-0.1.0-SNAPSHOT.jar'
    if source != source.resolve():
        raise ValueError('Build artifact symlinks are not allowed')
    before = regular_owned(source)
    local = root / '.local/niko-test'
    directory_owned(root / '.local'); directory_owned(local)
    base = local / 'export-worker-artifacts'
    base.mkdir(mode=0o700, exist_ok=True); directory_owned(base)
    launch = Path(tempfile.mkdtemp(prefix='export-worker-', dir=base))
    temporary = launch / 'copy.incomplete'
    hasher = hashlib.sha256()
    with source.open('rb') as incoming, temporary.open('xb') as outgoing:
        opened = os.fstat(incoming.fileno())
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise ValueError('Build artifact changed before copying')
        while chunk := incoming.read(1024 * 1024):
            outgoing.write(chunk); hasher.update(chunk)
        outgoing.flush(); os.fsync(outgoing.fileno())
    after = regular_owned(source)
    if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
        raise ValueError('Build artifact changed during copying; retry after the build finishes')
    with zipfile.ZipFile(temporary) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or 'META-INF/MANIFEST.MF' not in names or 'BOOT-INF/classes/cn/mop/exportworker/ExportPreparationWorkerApplication.class' not in names or archive.testzip() is not None:
            raise ValueError('Build artifact is not a complete MOP boot archive')
        if any(n.startswith('BOOT-INF/lib/mop-worker-') or n.startswith('BOOT-INF/lib/mop-adapters-') or n.startswith('BOOT-INF/lib/minio-') for n in names):
            raise ValueError('Other worker or storage adapter dependencies are not allowed')
        manifest = archive.read('META-INF/MANIFEST.MF').decode('utf-8').replace('\r\n ', '')
        if 'Start-Class: cn.mop.exportworker.ExportPreparationWorkerApplication' not in manifest.splitlines():
            raise ValueError('Artifact is not the dedicated export preparation worker entrypoint')
    target = launch / (hasher.hexdigest() + '.jar')
    temporary.chmod(0o400)
    temporary.rename(target)  # New private launch directory; no prior artifact is replaced.
    return verified_artifact(root, target)

if __name__ == '__main__':
    try:
        if sys.argv[1:] != ['--prepare']:
            raise ValueError('Use --prepare before starting the owned stack')
        print(prepare_artifact(Path.cwd()))
    except Exception as failure:
        print('Runtime artifact preparation failed: ' + type(failure).__name__ + '; details withheld', file=sys.stderr)
        raise SystemExit(1)

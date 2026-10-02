"""Evidence for an owned pinned API surviving a real Maven rebuild; no process signals."""
from pathlib import Path
import datetime, json, os, sys
from api_artifact import digest, verified_artifact
from owned_api import is_owned_api, owns_loopback_listener

ROOT = Path.cwd(); LOCAL = ROOT / '.local/niko-test'
BEFORE = LOCAL / 'runtime-artifact-before.json'
AFTER = ROOT / 'docs/evidence/niko-api-runtime-artifact-20261002.json'

def process_fields(pid):
    value = (Path('/proc') / str(pid) / 'stat').read_text()
    return value[value.rfind(')') + 2:].split()

def snapshot():
    pinned = verified_artifact(ROOT, os.environ.get('MOP_NIKO_API_ARTIFACT', ''))
    # QA shell is this process's parent, the owned stack runner is its parent.
    parent = int(process_fields(os.getppid())[1])
    # This executor exposes stat/cmdline but not /proc/PID/task/PID/children.
    # Read-only enumeration; the existing guard rejects every other parent before reading cmdline.
    children = [entry.name for entry in Path('/proc').iterdir() if entry.name.isdigit()]
    matches = [int(child) for child in children if is_owned_api(int(child), parent, ROOT, artifact=pinned) and owns_loopback_listener(int(child))]
    if len(matches) != 1: raise ValueError('Exactly one verified owned pinned API listener is required')
    pid = matches[0]; target = ROOT / 'server/api/target/mop-api-0.1.0-SNAPSHOT.jar'; info = target.stat()
    return {'api_pid': pid, 'api_start_ticks': process_fields(pid)[19], 'runtime_path': str(pinned), 'runtime_sha256': digest(pinned), 'target_sha256': digest(target), 'target_inode': info.st_ino, 'target_mtime_ns': info.st_mtime_ns}

def write_new(path, value, mode):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, mode)
    with os.fdopen(descriptor, 'w') as stream: json.dump(value, stream, indent=2); stream.write('\n')

try:
    args = sys.argv[1:]
    if args not in [['before'], ['after']]: raise ValueError('Use before or after')
    if AFTER.exists() or AFTER.is_symlink(): raise ValueError('Final evidence already exists')
    if args == ['before']:
        if BEFORE.exists() or BEFORE.is_symlink(): raise ValueError('Private checkpoint already exists')
        current = snapshot()
        if current['runtime_sha256'] != current['target_sha256']: raise ValueError('Initial runtime copy must match the build')
        write_new(BEFORE, current, 0o600)
        print('owned_runtime_artifact_baseline_recorded=true')
    else:
        previous = json.loads(BEFORE.read_text()); current = snapshot()
        for key in ['api_pid', 'api_start_ticks', 'runtime_path', 'runtime_sha256']:
            if previous[key] != current[key]: raise ValueError('Owned API restarted or its runtime copy changed')
        if all(previous[key] == current[key] for key in ['target_sha256', 'target_inode', 'target_mtime_ns']): raise ValueError('Build target was not changed')
        http = json.loads((LOCAL / 'runtime-artifact-http.json').read_text())
        if http.get('passed') is not True or http.get('identity_contexts') != 6: raise ValueError('Fresh-session HTTP verification missing')
        sessions = json.loads((LOCAL / 'logs/runtime-artifact-fresh-sessions.json').read_text())
        if len(sessions.get('real_oidc_http_checks', [])) != 6 or not all(item.get('authenticated') is True for item in sessions['real_oidc_http_checks']): raise ValueError('Fresh OIDC login evidence missing')
        evidence = {'verified_at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'environment': 'niko-local-test', 'passed': True, 'runtime_sha256': current['runtime_sha256'], 'target_sha256_before': previous['target_sha256'], 'target_sha256_after': current['target_sha256'], 'target_bytes_changed': previous['target_sha256'] != current['target_sha256'], 'target_inode_or_mtime_changed': any(previous[key] != current[key] for key in ['target_inode', 'target_mtime_ns']), 'api_pid_and_start_time_unchanged': True, 'runtime_copy_unchanged': True, 'owned_parent_exact_command_uid_and_loopback_listener_verified': True, 'new_real_oidc_identities_after_build': 6, 'http': http, 'credentials_output': False, 'browser_dom_tested': False, 'limitations': ['The prior ThrowableProxy failure is not claimed to be conclusively reproduced', 'No API SIGKILL scenario rerun; previous fault evidence unchanged', 'Not PG/Worker/OSS crash recovery', 'No original server changes']}
        write_new(AFTER, evidence, 0o644)
        print(json.dumps({'passed': True, 'api_process_unchanged': True, 'runtime_copy_unchanged': True, 'target_bytes_changed': evidence['target_bytes_changed']}))
except Exception as failure:
    print(json.dumps({'passed': False, 'exception_type': type(failure).__name__, 'details': 'redacted'})); raise SystemExit(1)

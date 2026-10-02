"""Read-only process identity guard for the explicitly owned local API fault test."""
from pathlib import Path
import os,sys,time,urllib.request
from api_artifact import verified_artifact

def is_owned_api(pid, parent, root, proc=Path('/proc'), artifact=None):
    if not isinstance(pid,int) or not isinstance(parent,int) or pid<=1 or parent<=1:return False
    try:
        base=proc/str(pid)
        if base.stat().st_uid!=os.getuid():return False
        stat=(base/'stat').read_text();end=stat.rfind(')');fields=stat[end+2:].split()
        if end<0 or len(fields)<20 or int(fields[1])!=parent:return False
        java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java'
        if not java.is_file():return False
        if artifact is None:return False
        pinned=verified_artifact(root,artifact)
        expected=[str(java),'-Xms128m','-Xmx512m','-jar',str(pinned)]
        command=(base/'cmdline').read_bytes().split(b'\0')
        if command[-1:] == [b'']:command=command[:-1]
        if [value.decode('utf-8') for value in command]!=expected:return False
        return (base/'exe').resolve()==java.resolve()
    except (OSError,ValueError,UnicodeError):return False

def owns_loopback_listener(pid, proc=Path('/proc')):
    try:
        inodes=set()
        for name in ('tcp','tcp6'):
            path=proc/'net'/name
            if not path.exists():continue
            for line in path.read_text().splitlines()[1:]:
                fields=line.split()
                if len(fields)<10 or fields[3]!='0A':continue
                address,port=fields[1].split(':')
                if port=='1F90' and address in ('0100007F','0000000000000000FFFF00000100007F','00000000000000000000000001000000'):inodes.add(fields[9])
        return any(os.readlink(fd).startswith('socket:[') and os.readlink(fd)[8:-1] in inodes for fd in (proc/str(pid)/'fd').iterdir())
    except (OSError,ValueError):return False

def wait_ready(pid,parent,root,artifact):
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(60):
        if not (Path('/proc')/str(pid)).exists():return False
        if is_owned_api(pid,parent,root,artifact=artifact) and owns_loopback_listener(pid):
            try:
                with opener.open('http://127.0.0.1:8080/api/v1/health/live',timeout=2) as response:
                    if response.status==200:return True
            except OSError:pass
        time.sleep(1)
    return False

if __name__=='__main__':
    try:
        args=sys.argv[1:];mode='identity'
        if args and args[0] in ('--wait-ready','--require-listener'):mode=args.pop(0)
        if len(args)!=3:raise ValueError()
        pid,parent=map(int,args[:2]);artifact=args[2];root=Path.cwd()
        if parent!=os.getppid():raise ValueError()
        valid=wait_ready(pid,parent,root,artifact) if mode=='--wait-ready' else is_owned_api(pid,parent,root,artifact=artifact) and (mode!='--require-listener' or owns_loopback_listener(pid))
        if not valid:raise ValueError()
    except ValueError:raise SystemExit('Owned API identity/listener was not verified; no signal is authorized by this check')
    print('owned_api_identity_and_requested_readiness_verified=true')

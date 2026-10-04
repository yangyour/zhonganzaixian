"""Read-only process identity guard for the explicitly owned export preparation worker process test."""
from pathlib import Path
import os,sys,time,urllib.request
from export_worker_artifact import verified_artifact

def is_owned_export_worker(pid, parent, root, proc=Path('/proc'), artifact=None,start_ticks=None,job_id=None):
    if not isinstance(pid,int) or not isinstance(parent,int) or pid<=1 or parent<=1:return False
    try:
        base=proc/str(pid)
        if base.stat().st_uid!=os.getuid():return False
        stat=(base/'stat').read_text();end=stat.rfind(')');fields=stat[end+2:].split()
        if end<0 or len(fields)<20 or int(fields[1])!=parent:return False
        if start_ticks is not None and str(fields[19])!=str(start_ticks):return False
        java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java'
        if not java.is_file():return False
        if artifact is None:return False
        pinned=verified_artifact(root,artifact)
        import re
        if job_id is None or not re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',job_id):return False
        expected=[str(java),'-Xms64m','-Xmx256m','-jar',str(pinned),'--prepare-one-without-publication',job_id]
        command=(base/'cmdline').read_bytes().split(b'\0')
        if command[-1:] == [b'']:command=command[:-1]
        if [value.decode('utf-8') for value in command]!=expected:return False
        return (base/'exe').resolve()==java.resolve()
    except (OSError,ValueError,UnicodeError):return False

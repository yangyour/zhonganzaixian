"""Start only a previously pinned contact worker; reuse existing approved runtime credentials, no setup or migration."""
from pathlib import Path
import json,os,sys
from contact_worker_artifact import verified_artifact
root=Path.cwd()
if len(sys.argv)!=3:raise SystemExit('Prepared contact worker artifact and explicit lease milliseconds required')
try:
 artifact=verified_artifact(root,sys.argv[1]);lease=sys.argv[2]
 if not lease.isascii() or not lease.isdigit() or str(int(lease))!=lease or not 1000<=int(lease)<=3600000:raise ValueError()
except (OSError,ValueError):raise SystemExit('Contact worker artifact or timing invalid; private configuration not loaded') from None
runtime=json.loads((root/'.local/niko-test/private/runtime.json').read_text())['environment']
# No inherited storage, OIDC, migration credentials, proxy settings or other worker flags enter this process.
java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java'
env={'PATH':str(java.parent)+':/usr/bin:/bin','JAVA_HOME':str(java.parent.parent),'LANG':'C.UTF-8','TZ':'UTC','SPRING_PROFILES_ACTIVE':'remote_test','MOP_CONTACT_IMPORT_WORKER_ENABLED':'true','MOP_CONTACT_IMPORT_LEASE_MILLIS':lease}
for key in ('SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD'):env[key]=runtime[key]
os.execve(java,[str(java),'-Xms64m','-Xmx256m','-jar',str(artifact)],env)

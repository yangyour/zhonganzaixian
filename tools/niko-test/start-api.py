from pathlib import Path
import json,os,sys
from api_artifact import verified_artifact
root=Path.cwd()
if len(sys.argv)!=2:raise SystemExit('A prepared per-launch API artifact is required')
try:artifact=verified_artifact(root,sys.argv[1])
except (OSError,ValueError):raise SystemExit('Runtime artifact validation failed; private configuration was not loaded') from None
runtime=json.loads((root/'.local/niko-test/private/runtime.json').read_text());env=os.environ.copy()
for k,v in runtime['environment'].items():
 if k not in ['MOP_MIGRATION_USERNAME','MOP_MIGRATION_PASSWORD']:env[k]=v
for k in ['MOP_MIGRATION_USERNAME','MOP_MIGRATION_PASSWORD']:env.pop(k,None)
env['TZ']='UTC'
java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java';args=[str(java),'-Xms128m','-Xmx512m','-jar',str(artifact)]
os.execve(java,args,env)

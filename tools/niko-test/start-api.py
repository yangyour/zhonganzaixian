from pathlib import Path
import json,os
root=Path.cwd();runtime=json.loads((root/'.local/niko-test/private/runtime.json').read_text());env=os.environ.copy()
for k,v in runtime['environment'].items():
 if k not in ['MOP_MIGRATION_USERNAME','MOP_MIGRATION_PASSWORD']:env[k]=v
for k in ['MOP_MIGRATION_USERNAME','MOP_MIGRATION_PASSWORD']:env.pop(k,None)
env['TZ']='UTC'
java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin/java';args=[str(java),'-Xms128m','-Xmx512m','-jar',str(root/'server/api/target/mop-api-0.1.0-SNAPSHOT.jar')]
os.execve(java,args,env)

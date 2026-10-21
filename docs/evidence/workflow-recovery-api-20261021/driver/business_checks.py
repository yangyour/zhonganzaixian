import pathlib,subprocess,json,hashlib
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java'
def run(root,evidence,env,sql,snapshot,private,jar):
 seed_env=env.copy();seed_env['MOP_ALLOW_SYNTHETIC_DASHBOARD_SEED']='true'
 base=pathlib.Path('/tmp/za-workflow-recovery-harness');cp=str(base/'classes')+':'+(base/'classpath.txt').read_text()
 r=subprocess.run([JAVA,'-cp',cp,'DashboardWorkflowSeed'],env=seed_env,capture_output=True,text=True,timeout=90)
 (evidence/'seed.json').write_text(json.dumps({'exit':r.returncode,'source_sha256':hashlib.sha256((base/'DashboardWorkflowSeed.java').read_bytes()).hexdigest(),'scope':'SYNTHETIC_DISPOSABLE_ONLY','provider_calls':0})+'\n')
 if r.returncode:
  error=r.stderr[-4000:]
  for k,v in seed_env.items():
   if any(t in k.upper() for t in ['PASSWORD','SECRET','TOKEN']) and v:error=error.replace(v,'[REDACTED]')
  (evidence/'seed-failure.json').write_text(json.dumps({'error':error})+'\n')
 assert r.returncode==0,'Synthetic dashboard workflow seed failed'
 import http_checks;http_checks.run(root,evidence,env,sql,snapshot,private,jar)

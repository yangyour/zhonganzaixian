import pathlib,subprocess,json,xml.etree.ElementTree as ET,hashlib
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java'
def run(root,evidence,env,sql,snapshot,private,jar):
 def write(n,d):(evidence/n).write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n')
 env=env.copy();env['MOP_RUN_CHANNEL_TARGET_PG_TESTS']='true'
 cp=str(root/'server/persistence/target/test-classes')+':'+pathlib.Path('/tmp/za-target-binding-harness/classpath.txt').read_text()
 args=[JAVA,'-jar','/tmp/za-java/junit.jar','execute','--disable-ansi-colors','--config','junit.jupiter.execution.parallel.enabled=false','--class-path',cp,'--reports-dir',str(private/'junit'),'--select-class','cn.mop.persistence.PostgresChannelTargetBindingIntegrationTest']
 source=root/'server/persistence/src/test/java/cn/mop/persistence/PostgresChannelTargetBindingIntegrationTest.java';write('test-source-hash.json',{'path':str(source.relative_to(root)),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
 with (private/'pg-tests.log').open('w') as log:r=subprocess.run(args,env=env,stdout=log,stderr=log,timeout=90)
 cases=[];diagnostics=[]
 for file in (private/'junit').glob('TEST-*.xml'):
  rootxml=ET.parse(file).getroot()
  for tag in ['failure','error','skipped']:
   for item in rootxml.iter(tag):
    message=(item.get('message') or item.text or '')[:3000]
    for key,value in env.items():
     if any(token in key.upper() for token in ['PASSWORD','SECRET','TOKEN']) and value:message=message.replace(value,'[REDACTED]')
    diagnostics.append({'kind':tag,'message':message})
  for case in rootxml.findall('testcase'):
   if case.get('classname','').endswith('PostgresChannelTargetBindingIntegrationTest'):cases.append({'name':case.get('name'),'passed':all(case.find(t) is None for t in ['failure','error','skipped'])})
 write('pg-tests.json',{'exit':r.returncode,'cases':cases,'expected':9,'diagnostics':diagnostics})
 assert r.returncode==0 and len(cases)==9 and all(c['passed'] for c in cases),'Target binding PG regression failed'
 print('TARGET_BINDING_PG_PASS count=9',flush=True)
 import http_checks;http_checks.run(root,evidence,env,sql,snapshot,private,jar)

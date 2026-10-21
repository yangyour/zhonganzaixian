import pathlib,subprocess,json,xml.etree.ElementTree as ET,hashlib
JAVA='/tmp/za-java/jdk-17.0.20.1+1/bin/java'
def run(root,evidence,env,sql,snapshot,private,jar):
 env=env.copy();env['MOP_RUN_AUTOTICKET_CAS_TESTS']='true'
 cp=str(root/'server/persistence/target/test-classes')+':'+pathlib.Path('/tmp/za-cas-harness/classpath.txt').read_text()
 report=evidence/'junit';args=[JAVA,'-jar','/tmp/za-java/junit.jar','execute','--disable-ansi-colors','--class-path',cp,'--reports-dir',str(report),'--select-class','cn.mop.persistence.PostgresAutoTicketCasIntegrationTest']
 (evidence/'test-source-hashes.json').write_text(json.dumps({str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [root/'server/persistence/src/test/java/cn/mop/persistence/PostgresAutoTicketCasIntegrationTest.java',root/'server/persistence/target/test-classes/cn/mop/persistence/PostgresAutoTicketCasIntegrationTest.class']},indent=2)+'\n')
 with (evidence/'cas-tests.log').open('w') as log:r=subprocess.run(args,env=env,stdout=log,stderr=log)
 cases=[]
 for file in report.glob('TEST-*.xml'):
  for case in ET.parse(file).getroot().findall('testcase'):
   if case.get('classname')=='cn.mop.persistence.PostgresAutoTicketCasIntegrationTest':cases.append({'name':case.get('name'),'passed':all(case.find(t) is None for t in ['failure','error','skipped'])})
 (evidence/'cas-tests.json').write_text(json.dumps({'exit':r.returncode,'cases':cases,'scope':'REAL_PG_TRANSACTION_WITH_CONTROLLED_JDBC_ZERO_ROW_UPDATE','real_concurrency':'NOT_RUN','http':'NOT_RUN'},indent=2)+'\n')
 assert r.returncode==0 and len(cases)==2 and all(c['passed'] for c in cases),'Controlled CAS tests failed'
 print('CONTROLLED_CAS_TESTS_PASS count=2',flush=True)

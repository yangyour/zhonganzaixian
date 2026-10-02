from pathlib import Path
import json,os,subprocess,sys,hashlib
root=Path.cwd()
manifest=json.loads((root/'tools/niko-test/frozen-migrations.json').read_text())['sha256']
for name,expected in manifest.items():
 source=root/'server/persistence/src/main/resources/db/migration'/name
 compiled=root/'server/persistence/target/classes/db/migration'/name
 if hashlib.sha256(source.read_bytes()).hexdigest()!=expected or source.read_bytes()!=compiled.read_bytes():raise SystemExit('Frozen migration source/build mismatch; no database action')
runtime=json.loads((root/'.local/niko-test/private/runtime.json').read_text());env=os.environ.copy()
for name in ['SPRING_DATASOURCE_URL','MOP_MIGRATION_USERNAME','MOP_MIGRATION_PASSWORD']:env[name]=runtime['environment'][name]
java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin';cp=os.pathsep.join([str(root/'.local/niko-test'),str(root/'server/persistence/target/classes'),str(root/'server/domain/target/classes'),str(root/'.local/niko-test/java-libs/*')])
if sys.argv[1:] == ['--initialize-new-niko-database']:
 subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(root/'.local/niko-test'),str(root/'tools/niko-test/NikoSchemaBootstrap.java')],check=True,env=env)
 args=[str(java/'java'),'-cp',cp,'NikoSchemaBootstrap']+sys.argv[1:]
elif sys.argv[1:] in [['--check'],['--apply-photos'],['--apply-asset-imports']]:args=[str(java/'java'),'-cp',cp,'cn.mop.persistence.TestSchemaMigration']+sys.argv[1:]
else:raise SystemExit('Explicit migration mode required')
raise SystemExit(subprocess.run(args,env=env).returncode)

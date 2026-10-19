#!/usr/bin/env python3
import os,sys,pathlib,subprocess,re,hashlib,json
base=pathlib.Path('/tmp/dot-test-services-upstream-b72c3cf');repo=pathlib.Path(sys.argv[1]).resolve(); java='/tmp/dot-tools/jdk/bin/'
subprocess.run([sys.executable,str(base/'verify-new-lineage.py'),str(repo)],check=True)
libs=[]
for part in ['org/flywaydb','org/postgresql','com/fasterxml/jackson','com/google/code/gson']:
 libs.extend(str(p) for p in (pathlib.Path('/tmp/dot-tools/m2')/part).rglob('*.jar'))
cp=':'.join([str(base),str(repo/'server/persistence/target/classes'),str(repo/'server/domain/target/classes')]+libs)
subprocess.run([java+'javac','-cp',cp,str(base/'DotEmptyBootstrap.java')],check=True)
subprocess.run([java+'java','-cp',cp,'DotEmptyBootstrap'],check=True)
source=(repo/'server/persistence/src/main/java/cn/mop/persistence/TestSchemaMigration.java').read_text()
enums=dict((name,int(version)) for name,version in re.findall(r'(APPLY_[A-Z_]+)\((\d+)\)',source))
flags=[(enums[name],flag) for flag,name in re.findall(r'"(--apply-[a-z-]+)"\.equals\(args\[0\]\)\)return Mode\.(APPLY_[A-Z_]+)',source)]
assert sorted(version for version,flag in flags if version<=39)==list(range(5,40))
for version,flag in sorted(flags):
 if version>39:continue
 subprocess.run([java+'java','-cp',cp,'cn.mop.persistence.TestSchemaMigration',flag],check=True)
for flag in ['--apply-email-integration','--check']:
 subprocess.run([java+'java','-cp',cp,'cn.mop.persistence.TestSchemaMigration',flag],check=True)
hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (repo/'server/persistence/src/main/resources/db/migration').glob('V*.sql') if int(p.name.split('__')[0][1:])<=39}
(base/'frozen-migration-hashes.json').write_text(json.dumps(hashes,indent=2)+'\n')
print('DOT_MIGRATIONS first=39 repeat=0 readonly=0 frozen_hashes_recorded=true',flush=True)
subprocess.run([java+'javac','-cp',cp,str(base/'DotSyntheticReceipts.java')],check=True)
subprocess.run([java+'java','-cp',cp,'DotSyntheticReceipts'],check=True)
if sys.argv[2:]:sys.exit(subprocess.run(sys.argv[2:],env=os.environ).returncode)

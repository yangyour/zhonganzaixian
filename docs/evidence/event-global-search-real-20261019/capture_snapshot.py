#!/usr/bin/env python3
"""Copy an approved compiled clean worktree. Does not start services."""
import pathlib,subprocess,hashlib,json,datetime,shutil,zipfile,io,sys
BASE=pathlib.Path(__file__).parent
repo=pathlib.Path(sys.argv[1]).resolve();snap=pathlib.Path(sys.argv[2]).resolve()
assert not snap.exists(),'snapshot destination already exists'
status=subprocess.check_output(['git','status','--porcelain'],cwd=repo,text=True);assert not status,'source must be clean'
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip()
baseline='b21bb8c699f62d33cd7ec6ad5b00a3bd278995dc';root='server/persistence/src/main/resources/db/migration/'
names=subprocess.check_output(['git','ls-tree','--name-only',baseline,root],cwd=repo,text=True).splitlines();assert len(names)==41
frozen={pathlib.Path(n).name:hashlib.sha256(subprocess.check_output(['git','show',baseline+':'+n],cwd=repo)).hexdigest() for n in names}
snap.mkdir();subprocess.run(['tar','-xf','-','-C',str(snap)],input=subprocess.check_output(['git','archive','HEAD'],cwd=repo),check=True)
for path in (repo/'server').glob('*/target'):shutil.copytree(path,snap/path.relative_to(repo),ignore=shutil.ignore_patterns('surefire-reports'))
h=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
source={p.name:h(p) for p in (snap/root).glob('V*.sql')};compiled={p.name:h(p) for p in (snap/'server/persistence/target/classes/db/migration').glob('V*.sql')}
assert source==compiled==frozen,'V1–V41 frozen migrations mismatch'
jar=snap/'server/api/target/mop-api-0.1.0-SNAPSHOT.jar';class_counts={}
with zipfile.ZipFile(jar) as z:
 for module in ['api','domain','persistence','adapters']:
  folder=snap/'server'/module/'target/classes'
  if not folder.exists():continue
  if module=='api':
   loaded={n.removeprefix('BOOT-INF/classes/'):z.read(n) for n in z.namelist() if n.startswith('BOOT-INF/classes/') and not n.endswith('/')}
  else:
   libs=[n for n in z.namelist() if n.startswith('BOOT-INF/lib/mop-'+module+'-')];assert len(libs)==1,(module,libs)
   with zipfile.ZipFile(io.BytesIO(z.read(libs[0]))) as n:loaded={x:n.read(x) for x in n.namelist() if not x.endswith('/') and not x.startswith('META-INF/')}
  expected={str(p.relative_to(folder)):p.read_bytes() for p in folder.rglob('*') if p.is_file() and 'META-INF' not in p.parts}
  assert expected==loaded,('compiled/JAR entry mismatch',module,set(expected)^set(loaded))
  class_counts[module]=len(expected)
manifest={str(p.relative_to(snap)):h(p) for p in sorted((snap/'server').rglob('*')) if p.is_file() and ('/src/' in str(p) or '/classes/' in str(p) or '/test-classes/' in str(p) or p.suffix=='.jar')}
report={'snapshot_commit':head,'published_baseline':baseline,'captured_at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'snapshot_path':str(snap),'api_jar_sha256':h(jar),'git_clean_verified':True,'source_classes_jar_migrations_identical':True,'all_compiled_entries_match_api_jar':class_counts,'migrations':source,'files':manifest}
(BASE/'snapshot.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in {'files','migrations'}},indent=2))

#!/usr/bin/env python3
"""Read-only preflight. Does not start services or execute migrations."""
import pathlib,sys,re,json,hashlib
base=pathlib.Path(__file__).resolve().parent;repo=pathlib.Path(sys.argv[1]).resolve()
expected=json.loads((base/'upstream-b72c3cf-frozen-migrations.json').read_text());approved='a24085a280ff822da932904c20cb3189fd813ef1d6ddc2f5458723ba48025756'
seen={}
for folder in ['src/main/resources/db/migration','target/classes/db/migration']:
 files=list((repo/'server/persistence'/folder).glob('V*.sql'));numbers=[int(p.name.split('__')[0][1:]) for p in files]
 assert sorted(numbers)==list(range(1,41)),f'Expected exactly unique contiguous V1–V40 in {folder}'
 current={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
 for name,h in expected.items():assert current.get(name)==h,f'Upstream frozen resource mismatch: {folder}/{name}'
 approval=[p for p in files if p.name.startswith('V40__')];assert len(approval)==1 and current[approval[0].name]==approved,'Approval V40 must be byte-identical to historical disposable SQL'
 seen[folder]=current
assert seen['src/main/resources/db/migration']==seen['target/classes/db/migration'],'Source/compiled resource mismatch'
source=(repo/'server/persistence/src/main/java/cn/mop/persistence/TestSchemaMigration.java').read_text()
enums={name:int(version) for name,version in re.findall(r'(APPLY_[A-Z_]+)\((\d+)\)',source)}
flags=[(enums[name],flag) for flag,name in re.findall(r'"(--apply-[a-z-]+)"\.equals\(args\[0\]\)\)return Mode\.(APPLY_[A-Z_]+)',source)]
assert sorted(v for v,f in flags)==list(range(5,41)),'Exactly one explicit apply CLI mode per V5–V40 required'
assert (38,'--apply-wecom-integration') in flags and (39,'--apply-email-integration') in flags and (40,'--apply-change-notice-approval') in flags
(base/'new-lineage-preflight.json').write_text(json.dumps({'upstream':'b72c3cf','result':'PASS','source_compiled_parity':True,'unique_contiguous_versions':list(range(1,41)),'sql_sha256':seen['src/main/resources/db/migration'],'apply_modes':sorted(flags)},indent=2)+'\n')
print('NEW_LINEAGE_PREFLIGHT PASS upstream_V1_V39_exact=true approval_V40_byte_identical=true duplicate_versions=false')

"""Derive only blank import column metadata; never copy customer rows or contacts to Web."""
import argparse,json,hashlib
from build_customer_cmdb_dictionary import ROOT,SOURCE,catalog
OUTPUT='apps/web/src/generated/cmdbImportSchema.json'
def render():
 d=catalog((ROOT/SOURCE).read_bytes())
 return json.dumps({'catalog_version':d['catalog_version'],'catalog_hash':hashlib.sha256((ROOT/'contracts/customer-cmdb-dictionary-v1.json').read_bytes()).hexdigest(),'source_blob':d['source']['git_blob_sha'],'columns':[{'position':i,'letter':letter,'label':label} for i,(letter,label) in enumerate(d['import_columns'].items())]},ensure_ascii=False,indent=2)+'\n'
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');p.add_argument('--check',action='store_true');a=p.parse_args()
 if a.write==a.check:p.error('Choose --write or --check')
 path=ROOT/OUTPUT;expected=render()
 if a.write:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(expected)
 elif not path.exists() or path.read_text()!=expected:raise SystemExit('CMDB import column metadata is stale')
 print('CMDB import metadata: 33 source columns, no asset values')
